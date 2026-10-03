package com.cross.app.health

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

enum class SleepStageType(val wireName: String) {
    UNKNOWN("unknown"),
    AWAKE("awake"),
    SLEEPING("sleeping"),
    OUT_OF_BED("out_of_bed"),
    AWAKE_IN_BED("awake_in_bed"),
    LIGHT("light"),
    DEEP("deep"),
    REM("rem");

    companion object {
        fun fromWireName(value: String): SleepStageType =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unsupported sleep stage: $value")
    }
}

enum class DeletedHealthRecordKind(val wireName: String) {
    SLEEP("sleep"),
    WORKOUT("workout");

    companion object {
        fun fromWireName(value: String): DeletedHealthRecordKind =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unsupported deleted health record kind: $value")
    }
}

data class SleepStageInterval(val startMs: Long, val endMs: Long, val stage: SleepStageType)

data class SleepSession(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val stages: List<SleepStageInterval>,
)

data class WorkoutSession(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val activity: String,
    val title: String?,
    val durationMs: Long,
    val activeEnergyKcal: Double?,
    val distanceMeters: Double?,
)

data class DeletedHealthRecord(val id: String, val kind: DeletedHealthRecordKind)

data class HealthSyncBatch(
    val generationMs: Long,
    val sleepSessions: List<SleepSession>,
    val workouts: List<WorkoutSession>,
    val deleted: List<DeletedHealthRecord>,
)

enum class HealthBatchAcceptance { NEEDS_IMPORT, ALREADY_IMPORTED }

internal object HealthSyncFailureMessages {
    const val INVALID_PAYLOAD = "Invalid health_sync payload; update Cross on both devices and retry"
    const val IMPORT_FAILED = "Health Connect import failed; the generation remains pending"
}

internal data class HealthReceiptState(
    val requestGenerations: Map<String, Long>,
    val appliedGenerationHashes: Map<Long, String>,
    val currentGenerationMs: Long?,
    val currentHash: String?,
    val hasPendingSync: Boolean,
)

/** Durable latest-batch queue. A failed Health Connect write remains pending for an idempotent retry. */
class HealthRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _latest = MutableStateFlow(prefs.getString(KEY_LATEST, null)?.let(HealthSyncJson::decode))
    val latest: StateFlow<HealthSyncBatch?> = _latest.asStateFlow()

    val hasPendingSync: Boolean
        get() = prefs.contains(KEY_PENDING) && prefs.getBoolean(KEY_PENDING, false) && _latest.value != null

    fun accept(requestId: String, batch: HealthSyncBatch): HealthBatchAcceptance {
        val current = _latest.value
        val requestGenerations = storedObject(KEY_REQUEST_GENERATIONS).toLongMap()
        val generationHashes = storedObject(KEY_APPLIED_GENERATION_HASHES).toLongKeyMap()
        val acceptance = decideHealthReceipt(
            state = HealthReceiptState(
                requestGenerations = requestGenerations,
                appliedGenerationHashes = generationHashes,
                currentGenerationMs = current?.generationMs,
                currentHash = current?.fingerprint(),
                hasPendingSync = hasPendingSync,
            ),
            requestId = requestId,
            batch = batch,
        )
        val updatedRequestGenerations = boundHealthRequestHistory(
            requestGenerations + (requestId to batch.generationMs),
        )
        if (acceptance == HealthBatchAcceptance.ALREADY_IMPORTED) {
            check(prefs.edit().putString(KEY_REQUEST_GENERATIONS, updatedRequestGenerations.toRequestJsonObject().toString()).commit()) {
                "Could not persist the health_sync request result"
            }
            return acceptance
        }
        check(prefs.edit()
            .putString(KEY_LATEST, HealthSyncJson.encode(batch))
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_REQUEST_GENERATIONS, updatedRequestGenerations.toRequestJsonObject().toString())
            .commit()) { "Could not durably persist health_sync generation ${batch.generationMs}" }
        _latest.value = batch
        return acceptance
    }

    fun markSynced(batch: HealthSyncBatch) {
        require(_latest.value == batch) { "Cannot mark a different health_sync generation as complete" }
        val generationHashes = boundAppliedGenerationHistory(
            storedObject(KEY_APPLIED_GENERATION_HASHES).toLongKeyMap() + (batch.generationMs to batch.fingerprint()),
        )
        check(prefs.edit()
            .putBoolean(KEY_PENDING, false)
            .putString(KEY_APPLIED_GENERATION_HASHES, generationHashes.toGenerationJsonObject().toString())
            .commit()) { "Could not persist the completed health_sync generation" }
    }

    private fun storedObject(key: String): JSONObject {
        // Fallback: a new install has no request/generation result index; an empty history is correct.
        if (!prefs.contains(key)) return JSONObject()
        val stored = requireNotNull(prefs.getString(key, null)) { "Health repository field $key is missing its value" }
        return JSONObject(stored)
    }

    private fun JSONObject.toLongMap(): Map<String, Long> = decodeStoredRequestGenerations(this)

    private fun JSONObject.toLongKeyMap(): Map<Long, String> = decodeStoredAppliedGenerationHashes(this)

    private fun Map<String, Long>.toRequestJsonObject(): JSONObject = JSONObject().also { root ->
        forEach(root::put)
    }

    private fun Map<Long, String>.toGenerationJsonObject(): JSONObject = JSONObject().also { root ->
        forEach { (generation, hash) -> root.put(generation.toString(), hash) }
    }

    private companion object {
        const val PREFERENCES_NAME = "cross_health_sync"
        const val KEY_LATEST = "latest_batch"
        const val KEY_PENDING = "pending"
        const val KEY_REQUEST_GENERATIONS = "request_generations"
        const val KEY_APPLIED_GENERATION_HASHES = "applied_generation_hashes"
    }
}

internal fun boundHealthRequestHistory(
    history: Map<String, Long>,
    limit: Int = MAX_HEALTH_RECEIPT_HISTORY,
): Map<String, Long> {
    require(limit > 0) { "Health receipt history limit must be positive" }
    return history.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(limit)
        .associate { it.toPair() }
}

internal fun boundAppliedGenerationHistory(
    history: Map<Long, String>,
    limit: Int = MAX_HEALTH_RECEIPT_HISTORY,
): Map<Long, String> {
    require(limit > 0) { "Health receipt history limit must be positive" }
    return history.entries
        .sortedByDescending { it.key }
        .take(limit)
        .associate { it.toPair() }
}

internal const val MAX_HEALTH_RECEIPT_HISTORY = 256

internal fun decodeStoredRequestGenerations(root: JSONObject): Map<String, Long> =
    root.keys().asSequence().associateWith { requestId ->
        val stored = root.get(requestId)
        val generation = when (stored) {
            is Int -> stored.toLong()
            is Long -> stored
            else -> throw IllegalStateException("Stored health request generation has the wrong type")
        }
        check(generation > 0L) { "Stored health request generation must be positive" }
        generation
    }

internal fun decodeStoredAppliedGenerationHashes(root: JSONObject): Map<Long, String> =
    root.keys().asSequence().associate { storedGeneration ->
        val generation = storedGeneration.toLongOrNull()
            ?: error("Stored applied health generation key is not a signed 64-bit integer")
        check(generation > 0L && generation.toString() == storedGeneration) {
            "Stored applied health generation key must be a canonical positive integer"
        }
        val hash = root.get(storedGeneration) as? String
            ?: error("Stored applied health generation hash has the wrong type")
        check(LOWERCASE_SHA256.matches(hash)) { "Stored applied health generation hash is invalid" }
        generation to hash
    }

private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")

internal fun HealthSyncBatch.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(HealthSyncJson.encode(this).toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun decideHealthReceipt(
    state: HealthReceiptState,
    requestId: String,
    batch: HealthSyncBatch,
): HealthBatchAcceptance {
    require(requestId.isNotBlank()) { "health_sync request_id must not be blank" }
    val recordedRequestGeneration = state.requestGenerations[requestId]
    require(recordedRequestGeneration == null || recordedRequestGeneration == batch.generationMs) {
        "health_sync request_id was reused for a different generation"
    }

    val batchHash = batch.fingerprint()
    state.appliedGenerationHashes[batch.generationMs]?.let { appliedHash ->
        require(appliedHash == batchHash) { "health_sync generation_ms was reused for different content" }
        return HealthBatchAcceptance.ALREADY_IMPORTED
    }

    state.currentGenerationMs?.let { currentGeneration ->
        require(batch.generationMs >= currentGeneration) { "health_sync generation_ms must not move backwards" }
        if (batch.generationMs == currentGeneration) {
            val currentHash = requireNotNull(state.currentHash) { "Current health_sync state is missing its content hash" }
            require(currentHash == batchHash) { "health_sync generation_ms was reused for different content" }
        }
    }
    if (state.hasPendingSync) {
        val pendingGeneration = requireNotNull(state.currentGenerationMs) { "Pending health_sync state is missing its generation" }
        require(pendingGeneration == batch.generationMs) { "health_sync generation $pendingGeneration is still pending" }
    }
    return HealthBatchAcceptance.NEEDS_IMPORT
}

/** Strict JSON boundary for the schema-v2 health_sync payload and its durable retry queue. */
internal object HealthSyncJson {
    fun decode(json: String): HealthSyncBatch {
        val root = JSONObject(json)
        val generationMs = root.requiredPositiveLong("generation_ms")
        val sleepSessions = root.requiredArray("sleep_sessions").mapObjects(::decodeSleepSession)
        val workouts = root.requiredArray("workouts").mapObjects(::decodeWorkout)
        val deleted = root.requiredArray("deleted").mapObjects(::decodeDeletedRecord)

        val liveIds = buildSet {
            sleepSessions.forEach { require(add(DeletedHealthRecordKind.SLEEP to it.id)) { "Duplicate sleep session id: ${it.id}" } }
            workouts.forEach { require(add(DeletedHealthRecordKind.WORKOUT to it.id)) { "Duplicate workout id: ${it.id}" } }
        }
        deleted.forEach {
            require((it.kind to it.id) !in liveIds) { "A health record cannot be updated and deleted in the same generation: ${it.id}" }
        }
        require(deleted.distinct().size == deleted.size) { "Duplicate deleted health record" }

        return HealthSyncBatch(generationMs, sleepSessions, workouts, deleted)
    }

    fun encode(batch: HealthSyncBatch): String = JSONObject()
        .put("generation_ms", batch.generationMs)
        .put("sleep_sessions", JSONArray().also { array -> batch.sleepSessions.forEach { array.put(encodeSleepSession(it)) } })
        .put("workouts", JSONArray().also { array -> batch.workouts.forEach { array.put(encodeWorkout(it)) } })
        .put("deleted", JSONArray().also { array -> batch.deleted.forEach { array.put(encodeDeletedRecord(it)) } })
        .toString()

    private fun decodeSleepSession(item: JSONObject): SleepSession {
        val id = item.requiredNonBlankString("id")
        val startMs = item.requiredPositiveLong("start_ms")
        val endMs = item.requiredPositiveLong("end_ms")
        require(startMs < endMs) { "Sleep session $id must end after it starts" }
        val stages = item.requiredArray("stages").mapObjects { stage ->
            val stageStartMs = stage.requiredPositiveLong("start_ms")
            val stageEndMs = stage.requiredPositiveLong("end_ms")
            require(stageStartMs < stageEndMs) { "Sleep stage in $id must end after it starts" }
            require(stageStartMs >= startMs && stageEndMs <= endMs) { "Sleep stage in $id must be inside its session" }
            SleepStageInterval(stageStartMs, stageEndMs, SleepStageType.fromWireName(stage.requiredNonBlankString("stage")))
        }
        stages.zipWithNext().forEach { (previous, next) ->
            require(previous.startMs <= next.startMs) { "Sleep stages in $id must be chronological" }
            require(previous.endMs <= next.startMs) { "Sleep stages in $id must not overlap" }
        }
        return SleepSession(
            id = id,
            startMs = startMs,
            endMs = endMs,
            startZoneOffsetSeconds = item.optionalZoneOffsetSeconds("start_zone_offset_seconds"),
            endZoneOffsetSeconds = item.optionalZoneOffsetSeconds("end_zone_offset_seconds"),
            stages = stages,
        )
    }

    private fun decodeWorkout(item: JSONObject): WorkoutSession {
        val id = item.requiredNonBlankString("id")
        val startMs = item.requiredPositiveLong("start_ms")
        val endMs = item.requiredPositiveLong("end_ms")
        require(startMs < endMs) { "Workout $id must end after it starts" }
        return WorkoutSession(
            id = id,
            startMs = startMs,
            endMs = endMs,
            startZoneOffsetSeconds = item.optionalZoneOffsetSeconds("start_zone_offset_seconds"),
            endZoneOffsetSeconds = item.optionalZoneOffsetSeconds("end_zone_offset_seconds"),
            activity = item.requiredNonBlankString("activity"),
            title = item.optionalString("title"),
            durationMs = item.requiredNonNegativeLong("duration_ms"),
            activeEnergyKcal = item.optionalNonNegativeDouble("active_energy_kcal"),
            distanceMeters = item.optionalNonNegativeDouble("distance_meters"),
        )
    }

    private fun decodeDeletedRecord(item: JSONObject): DeletedHealthRecord = DeletedHealthRecord(
        id = item.requiredNonBlankString("id"),
        kind = DeletedHealthRecordKind.fromWireName(item.requiredNonBlankString("kind")),
    )

    private fun encodeSleepSession(session: SleepSession): JSONObject = JSONObject()
        .put("id", session.id).put("start_ms", session.startMs).put("end_ms", session.endMs)
        .putNullable("start_zone_offset_seconds", session.startZoneOffsetSeconds)
        .putNullable("end_zone_offset_seconds", session.endZoneOffsetSeconds)
        .put("stages", JSONArray().also { array ->
            session.stages.forEach { stage ->
                array.put(JSONObject().put("start_ms", stage.startMs).put("end_ms", stage.endMs).put("stage", stage.stage.wireName))
            }
        })

    private fun encodeWorkout(workout: WorkoutSession): JSONObject = JSONObject()
        .put("id", workout.id).put("start_ms", workout.startMs).put("end_ms", workout.endMs)
        .putNullable("start_zone_offset_seconds", workout.startZoneOffsetSeconds)
        .putNullable("end_zone_offset_seconds", workout.endZoneOffsetSeconds)
        .put("activity", workout.activity).putNullable("title", workout.title).put("duration_ms", workout.durationMs)
        .putNullable("active_energy_kcal", workout.activeEnergyKcal).putNullable("distance_meters", workout.distanceMeters)

    private fun encodeDeletedRecord(record: DeletedHealthRecord): JSONObject =
        JSONObject().put("id", record.id).put("kind", record.kind.wireName)

    private fun JSONObject.requiredArray(key: String): JSONArray {
        require(has(key) && !isNull(key)) { "health_sync is missing required field: $key" }
        return getJSONArray(key)
    }

    private fun JSONObject.requiredNonBlankString(key: String): String {
        require(has(key) && !isNull(key)) { "health_sync is missing required field: $key" }
        val value = get(key)
        require(value is String) { "health_sync field $key must be a string" }
        return value.also { require(it.isNotBlank()) { "health_sync field $key must not be blank" } }
    }

    private fun JSONObject.requiredPositiveLong(key: String): Long = requiredLong(key).also {
        require(it > 0L) { "health_sync field $key must be positive" }
    }

    private fun JSONObject.requiredNonNegativeLong(key: String): Long = requiredLong(key).also {
        require(it >= 0L) { "health_sync field $key must not be negative" }
    }

    private fun JSONObject.requiredLong(key: String): Long {
        require(has(key) && !isNull(key)) { "health_sync is missing required field: $key" }
        val value = get(key)
        require(value is Number) { "health_sync field $key must be a number" }
        return try {
            BigDecimal(value.toString()).longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("health_sync field $key must be an exact signed 64-bit integer", error)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("health_sync field $key must be an exact signed 64-bit integer", error)
        }
    }

    private fun JSONObject.optionalZoneOffsetSeconds(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val seconds = requiredLong(key)
        require(seconds in -64_800L..64_800L) { "health_sync field $key is outside the valid zone-offset range" }
        return seconds.toInt()
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        require(value is String) { "health_sync field $key must be a string when present" }
        return value
    }

    private fun JSONObject.optionalNonNegativeDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        require(value is Number) { "health_sync field $key must be a number" }
        return value.toDouble().also {
            require(it.isFinite() && it >= 0.0) { "health_sync field $key must be a finite non-negative number" }
        }
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index -> transform(getJSONObject(index)) }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
}
