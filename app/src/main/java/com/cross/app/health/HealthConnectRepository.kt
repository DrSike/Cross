package com.cross.app.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.meters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset

sealed interface HealthSyncStatus {
    data object Checking : HealthSyncStatus
    data object Ready : HealthSyncStatus
    data class Unavailable(val message: String) : HealthSyncStatus
    data class PermissionRequired(val missingPermissions: Set<String>) : HealthSyncStatus
    data class Syncing(val generationMs: Long) : HealthSyncStatus
    data class Synced(
        val generationMs: Long,
        val sleepSessionCount: Int,
        val workoutCount: Int,
        val deletionCount: Int,
    ) : HealthSyncStatus
    data class Failed(val message: String) : HealthSyncStatus
}

sealed interface HealthSyncOutcome {
    data object Completed : HealthSyncOutcome
    data object Unavailable : HealthSyncOutcome
    data object PermissionRequired : HealthSyncOutcome
}

internal object HealthPermissionRationale {
    const val SHOW_RATIONALE_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    const val VIEW_PERMISSION_USAGE_ACTION = "android.intent.action.VIEW_PERMISSION_USAGE"

    fun handles(action: String?): Boolean =
        action == SHOW_RATIONALE_ACTION || action == VIEW_PERMISSION_USAGE_ACTION
}

internal data class HealthConnectDeletionPlan(
    val sleepClientRecordIds: List<String>,
    val workoutClientRecordIds: List<String>,
    val workoutEnergyClientRecordIds: List<String>,
    val workoutDistanceClientRecordIds: List<String>,
)

internal fun healthConnectDeletionPlan(batch: HealthSyncBatch): HealthConnectDeletionPlan {
    val deletedSleepIds = batch.deleted
        .filter { it.kind == DeletedHealthRecordKind.SLEEP }
        .map { it.id }
    val deletedWorkoutIds = batch.deleted
        .filter { it.kind == DeletedHealthRecordKind.WORKOUT }
        .map { it.id }
    val workoutsWithoutEnergy = batch.workouts.filter { it.activeEnergyKcal == null }.map { it.id }
    val workoutsWithoutDistance = batch.workouts.filter { it.distanceMeters == null }.map { it.id }
    return HealthConnectDeletionPlan(
        sleepClientRecordIds = deletedSleepIds.map(HealthConnectMapper::sleepClientRecordId),
        workoutClientRecordIds = deletedWorkoutIds.map(HealthConnectMapper::workoutClientRecordId),
        workoutEnergyClientRecordIds = (deletedWorkoutIds + workoutsWithoutEnergy)
            .distinct()
            .map(HealthConnectMapper::workoutEnergyClientRecordId),
        workoutDistanceClientRecordIds = (deletedWorkoutIds + workoutsWithoutDistance)
            .distinct()
            .map(HealthConnectMapper::workoutDistanceClientRecordId),
    )
}

internal fun healthConnectRequiredPermissions(batch: HealthSyncBatch?): Set<String> {
    if (batch == null) return HealthConnectRepository.WRITE_PERMISSIONS
    return buildSet {
        if (batch.sleepSessions.isNotEmpty() || batch.deleted.any { it.kind == DeletedHealthRecordKind.SLEEP }) {
            add(HealthConnectRepository.WRITE_SLEEP)
        }
        if (batch.workouts.isNotEmpty() || batch.deleted.any { it.kind == DeletedHealthRecordKind.WORKOUT }) {
            add(HealthConnectRepository.WRITE_EXERCISE)
            add(HealthConnectRepository.WRITE_ACTIVE_CALORIES)
            add(HealthConnectRepository.WRITE_DISTANCE)
        }
    }
}

/** Writes lossless watch-originated sessions to Health Connect with retry-safe source identities. */
class HealthConnectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val syncMutex = Mutex()
    private val _status = MutableStateFlow<HealthSyncStatus>(HealthSyncStatus.Checking)
    val status: StateFlow<HealthSyncStatus> = _status.asStateFlow()

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    fun reportInvalidPayloadFailure() {
        _status.value = HealthSyncStatus.Failed(HealthSyncFailureMessages.INVALID_PAYLOAD)
    }

    fun reportImportFailure() {
        _status.value = HealthSyncStatus.Failed(HealthSyncFailureMessages.IMPORT_FAILED)
    }

    suspend fun refreshStatus(batch: HealthSyncBatch?) {
        val client = availableClientOrUpdateStatus() ?: return
        val missing = healthConnectRequiredPermissions(batch) - client.permissionController.getGrantedPermissions()
        _status.value = if (missing.isEmpty()) HealthSyncStatus.Ready else HealthSyncStatus.PermissionRequired(missing)
    }

    suspend fun sync(batch: HealthSyncBatch): HealthSyncOutcome = syncMutex.withLock {
        val client = availableClientOrUpdateStatus() ?: return@withLock HealthSyncOutcome.Unavailable
        val missing = healthConnectRequiredPermissions(batch) - client.permissionController.getGrantedPermissions()
        if (missing.isNotEmpty()) {
            _status.value = HealthSyncStatus.PermissionRequired(missing)
            return@withLock HealthSyncOutcome.PermissionRequired
        }

        _status.value = HealthSyncStatus.Syncing(batch.generationMs)
        try {
            val records = HealthConnectMapper.records(batch)
            deleteRecords(client, healthConnectDeletionPlan(batch))
            if (records.isNotEmpty()) client.insertRecords(records)
            _status.value = HealthSyncStatus.Synced(
                generationMs = batch.generationMs,
                sleepSessionCount = batch.sleepSessions.size,
                workoutCount = batch.workouts.size,
                deletionCount = batch.deleted.size,
            )
            HealthSyncOutcome.Completed
        } catch (error: SecurityException) {
            // Fallback: Health Connect permission can be revoked between preflight and the write;
            // requesting it again is recoverable because the durable batch remains pending.
            _status.value = HealthSyncStatus.PermissionRequired(healthConnectRequiredPermissions(batch))
            HealthSyncOutcome.PermissionRequired
        } catch (error: Exception) {
            _status.value = HealthSyncStatus.Failed(HealthSyncFailureMessages.IMPORT_FAILED)
            throw error
        }
    }

    private fun availableClientOrUpdateStatus(): HealthConnectClient? = when (HealthConnectClient.getSdkStatus(appContext)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(appContext)
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
            _status.value = HealthSyncStatus.Unavailable("Health Connect must be installed or updated before health sessions can sync.")
            null
        }
        else -> {
            _status.value = HealthSyncStatus.Unavailable("Health Connect is not available on this device.")
            null
        }
    }

    private suspend fun deleteRecords(client: HealthConnectClient, plan: HealthConnectDeletionPlan) {
        if (plan.sleepClientRecordIds.isNotEmpty()) {
            client.deleteRecords(SleepSessionRecord::class, recordIdsList = emptyList(), clientRecordIdsList = plan.sleepClientRecordIds)
        }
        if (plan.workoutClientRecordIds.isNotEmpty()) {
            client.deleteRecords(
                ExerciseSessionRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = plan.workoutClientRecordIds,
            )
        }
        if (plan.workoutEnergyClientRecordIds.isNotEmpty()) {
            client.deleteRecords(
                ActiveCaloriesBurnedRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = plan.workoutEnergyClientRecordIds,
            )
        }
        if (plan.workoutDistanceClientRecordIds.isNotEmpty()) {
            client.deleteRecords(
                DistanceRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = plan.workoutDistanceClientRecordIds,
            )
        }
    }

    companion object {
        val WRITE_SLEEP: String = HealthPermission.getWritePermission(SleepSessionRecord::class)
        val WRITE_EXERCISE: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val WRITE_ACTIVE_CALORIES: String = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
        val WRITE_DISTANCE: String = HealthPermission.getWritePermission(DistanceRecord::class)
        val WRITE_PERMISSIONS: Set<String> = setOf(WRITE_SLEEP, WRITE_EXERCISE, WRITE_ACTIVE_CALORIES, WRITE_DISTANCE)
    }
}

/** Pure record mapping kept separate so identity/version and enum behavior can be regression-tested. */
internal object HealthConnectMapper {
    fun records(batch: HealthSyncBatch): List<Record> = buildList {
        batch.sleepSessions.forEach { add(sleepRecord(it, batch.generationMs)) }
        batch.workouts.forEach { workout ->
            add(workoutRecord(workout, batch.generationMs))
            workout.activeEnergyKcal?.let { calories ->
                add(
                    ActiveCaloriesBurnedRecord(
                        energy = Energy.kilocalories(calories),
                        startTime = Instant.ofEpochMilli(workout.startMs),
                        startZoneOffset = zoneOffset(workout.startZoneOffsetSeconds),
                        endTime = Instant.ofEpochMilli(workout.endMs),
                        endZoneOffset = zoneOffset(workout.endZoneOffsetSeconds),
                        metadata = metadata(workoutEnergyClientRecordId(workout.id), batch.generationMs),
                    ),
                )
            }
            workout.distanceMeters?.let { distance ->
                add(
                    DistanceRecord(
                        distance = distance.meters,
                        startTime = Instant.ofEpochMilli(workout.startMs),
                        startZoneOffset = zoneOffset(workout.startZoneOffsetSeconds),
                        endTime = Instant.ofEpochMilli(workout.endMs),
                        endZoneOffset = zoneOffset(workout.endZoneOffsetSeconds),
                        metadata = metadata(workoutDistanceClientRecordId(workout.id), batch.generationMs),
                    ),
                )
            }
        }
    }

    fun sleepClientRecordId(sourceId: String): String = "cross-watch-sleep:$sourceId"
    fun workoutClientRecordId(sourceId: String): String = "cross-watch-workout:$sourceId"
    fun workoutEnergyClientRecordId(sourceId: String): String = "cross-watch-workout-energy:$sourceId"
    fun workoutDistanceClientRecordId(sourceId: String): String = "cross-watch-workout-distance:$sourceId"

    fun stageType(stage: SleepStageType): Int = when (stage) {
        SleepStageType.UNKNOWN -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
        SleepStageType.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
        SleepStageType.SLEEPING -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        SleepStageType.OUT_OF_BED -> SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        SleepStageType.AWAKE_IN_BED -> SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
        SleepStageType.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        SleepStageType.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        SleepStageType.REM -> SleepSessionRecord.STAGE_TYPE_REM
    }

    fun exerciseType(activity: String): Int = when (activity.lowercase()) {
        "walking" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "cycling", "biking" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "hiking" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "swimming", "swimming_pool" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        "swimming_open_water" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
        "yoga" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        "pilates" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        "elliptical" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        "rowing" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
        "rowing_machine" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        "stair_climbing" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING
        "high_intensity_interval_training" -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        "functional_strength_training", "strength_training" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "traditional_strength_training", "weightlifting" -> ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
        "dance", "dancing" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        "boxing" -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
        "martial_arts" -> ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
        "tennis" -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        "badminton" -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        "basketball" -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        "soccer" -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        "golf" -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
        "table_tennis" -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        "volleyball" -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        "wheelchair" -> ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR
        // Fallback: HealthKit can add or emit activity identifiers that Health Connect does not model;
        // OTHER_WORKOUT preserves the session interval instead of dropping a valid workout.
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    private fun sleepRecord(session: SleepSession, generationMs: Long): SleepSessionRecord = SleepSessionRecord(
        startTime = Instant.ofEpochMilli(session.startMs),
        startZoneOffset = zoneOffset(session.startZoneOffsetSeconds),
        endTime = Instant.ofEpochMilli(session.endMs),
        endZoneOffset = zoneOffset(session.endZoneOffsetSeconds),
        title = "Apple Watch sleep",
        stages = session.stages.sortedWith(compareBy(SleepStageInterval::startMs, SleepStageInterval::endMs)).map { stage ->
            SleepSessionRecord.Stage(
                startTime = Instant.ofEpochMilli(stage.startMs),
                endTime = Instant.ofEpochMilli(stage.endMs),
                stage = stageType(stage.stage),
            )
        },
        metadata = metadata(sleepClientRecordId(session.id), generationMs),
    )

    private fun workoutRecord(workout: WorkoutSession, generationMs: Long): ExerciseSessionRecord = ExerciseSessionRecord(
        startTime = Instant.ofEpochMilli(workout.startMs),
        startZoneOffset = zoneOffset(workout.startZoneOffsetSeconds),
        endTime = Instant.ofEpochMilli(workout.endMs),
        endZoneOffset = zoneOffset(workout.endZoneOffsetSeconds),
        exerciseType = exerciseType(workout.activity),
        title = workout.title,
        metadata = metadata(workoutClientRecordId(workout.id), generationMs),
    )

    private fun zoneOffset(seconds: Int?): ZoneOffset? = seconds?.let(ZoneOffset::ofTotalSeconds)

    private fun metadata(clientRecordId: String, generationMs: Long): Metadata = Metadata.autoRecorded(
        device = Device(type = Device.TYPE_WATCH),
        clientRecordId = clientRecordId,
        clientRecordVersion = generationMs,
    )
}
