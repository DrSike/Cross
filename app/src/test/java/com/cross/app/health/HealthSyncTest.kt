package com.cross.app.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthSyncTest {
    @Test
    fun jsonRoundTripPreservesSessionsStagesWorkoutsAndDeletions() {
        val batch = sampleBatch()

        assertEquals(batch, HealthSyncJson.decode(HealthSyncJson.encode(batch)))
    }

    @Test
    fun parserRejectsMissingRequiredFieldsAndUnknownStages() {
        val missingGeneration = JSONObject()
            .put("sleep_sessions", JSONArray())
            .put("workouts", JSONArray())
            .put("deleted", JSONArray())
            .toString()
        val unknownStage = JSONObject(HealthSyncJson.encode(sampleBatch())).also { root ->
            root.getJSONArray("sleep_sessions").getJSONObject(0)
                .getJSONArray("stages").getJSONObject(0).put("stage", "core")
        }.toString()

        assertThrows(IllegalArgumentException::class.java) { HealthSyncJson.decode(missingGeneration) }
        assertThrows(IllegalStateException::class.java) { HealthSyncJson.decode(unknownStage) }
    }

    @Test
    fun parserRejectsOverflowFractionalAndStringIntegerFields() {
        fun payload(generation: Any): String = JSONObject()
            .put("generation_ms", generation)
            .put("sleep_sessions", JSONArray())
            .put("workouts", JSONArray())
            .put("deleted", JSONArray())
            .toString()

        listOf(1e100, 1.5, "1").forEach { invalidGeneration ->
            assertThrows(IllegalArgumentException::class.java) {
                HealthSyncJson.decode(payload(invalidGeneration))
            }
        }
        assertEquals(1L, HealthSyncJson.decode(payload(1.0)).generationMs)
        assertEquals(Long.MAX_VALUE, HealthSyncJson.decode(payload(Long.MAX_VALUE)).generationMs)
    }

    @Test
    fun parserRejectsNumericValuesForRequiredAndOptionalStrings() {
        val invalidPayloads = listOf<(JSONObject) -> Unit>(
            { root -> root.getJSONArray("sleep_sessions").getJSONObject(0).put("id", 7) },
            { root -> root.getJSONArray("sleep_sessions").getJSONObject(0).getJSONArray("stages").getJSONObject(0).put("stage", 7) },
            { root -> root.getJSONArray("workouts").getJSONObject(0).put("id", 7) },
            { root -> root.getJSONArray("workouts").getJSONObject(0).put("activity", 7) },
            { root -> root.getJSONArray("workouts").getJSONObject(0).put("title", 7) },
            { root -> root.getJSONArray("deleted").getJSONObject(0).put("id", 7) },
            { root -> root.getJSONArray("deleted").getJSONObject(0).put("kind", 7) },
        )

        invalidPayloads.forEach { mutate ->
            val payload = JSONObject(HealthSyncJson.encode(sampleBatch())).also(mutate).toString()
            assertThrows(IllegalArgumentException::class.java) {
                HealthSyncJson.decode(payload)
            }
        }
    }

    @Test
    fun parserRejectsOverlappingAndOutOfOrderSleepStages() {
        val overlap = JSONObject(HealthSyncJson.encode(sampleBatch())).also { root ->
            val stages = root.getJSONArray("sleep_sessions").getJSONObject(0).getJSONArray("stages")
            stages.getJSONObject(1).put("start_ms", stages.getJSONObject(0).getLong("end_ms") - 1L)
        }.toString()
        val outOfOrder = JSONObject(HealthSyncJson.encode(sampleBatch())).also { root ->
            val session = root.getJSONArray("sleep_sessions").getJSONObject(0)
            val stages = session.getJSONArray("stages")
            session.put("stages", JSONArray().put(stages.getJSONObject(1)).put(stages.getJSONObject(0)))
        }.toString()

        assertThrows(IllegalArgumentException::class.java) { HealthSyncJson.decode(overlap) }
        assertThrows(IllegalArgumentException::class.java) { HealthSyncJson.decode(outOfOrder) }
    }

    @Test
    fun mapsEverySleepStageAndFallsBackUnknownActivityToOtherWorkout() {
        val expectedStages = listOf(
            SleepSessionRecord.STAGE_TYPE_UNKNOWN,
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )

        assertEquals(expectedStages, SleepStageType.entries.map(HealthConnectMapper::stageType))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, HealthConnectMapper.exerciseType("running"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthConnectMapper.exerciseType("underwater_rugby"))
    }

    @Test
    fun repeatedMappingKeepsClientIdsAndVersionsStable() {
        val batch = sampleBatch()
        val first = HealthConnectMapper.records(batch)
        val retry = HealthConnectMapper.records(batch)
        val nextGeneration = HealthConnectMapper.records(batch.copy(generationMs = batch.generationMs + 1L))

        assertEquals(first.map { it.metadata.clientRecordId }, retry.map { it.metadata.clientRecordId })
        assertEquals(
            listOf(
                "cross-watch-sleep:sleep-1",
                "cross-watch-workout:workout-1",
                "cross-watch-workout-energy:workout-1",
                "cross-watch-workout-distance:workout-1",
            ),
            first.map { it.metadata.clientRecordId },
        )
        assertEquals(first.map { it.metadata.clientRecordVersion }, retry.map { it.metadata.clientRecordVersion })
        assertEquals(first.map { it.metadata.clientRecordId }, nextGeneration.map { it.metadata.clientRecordId })
        assertEquals(List(first.size) { batch.generationMs + 1L }, nextGeneration.map { it.metadata.clientRecordVersion })
        assertEquals(
            listOf(SleepSessionRecord::class, ExerciseSessionRecord::class, ActiveCaloriesBurnedRecord::class, DistanceRecord::class),
            first.map { it::class },
        )
        val sleep = first[0] as SleepSessionRecord
        assertEquals(Instant.ofEpochMilli(batch.sleepSessions.single().startMs), sleep.startTime)
        assertEquals(
            batch.sleepSessions.single().stages.map { it.startMs to it.endMs },
            sleep.stages.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() },
        )
        val workout = first[1] as ExerciseSessionRecord
        assertEquals("Morning run", workout.title)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, workout.exerciseType)
        assertNull(workout.notes)
    }

    @Test
    fun workoutReplacementDeletesDerivedRecordsThatAreNoLongerPresent() {
        val workoutWithoutDerivedValues = sampleBatch().workouts.single().copy(
            activeEnergyKcal = null,
            distanceMeters = null,
        )
        val batch = sampleBatch().copy(
            workouts = listOf(workoutWithoutDerivedValues),
            deleted = emptyList(),
        )

        val plan = healthConnectDeletionPlan(batch)

        assertEquals(emptyList<String>(), plan.workoutClientRecordIds)
        assertEquals(
            listOf("cross-watch-workout-energy:workout-1"),
            plan.workoutEnergyClientRecordIds,
        )
        assertEquals(
            listOf("cross-watch-workout-distance:workout-1"),
            plan.workoutDistanceClientRecordIds,
        )
        assertEquals(
            listOf("cross-watch-sleep:sleep-1", "cross-watch-workout:workout-1"),
            HealthConnectMapper.records(batch).map { it.metadata.clientRecordId },
        )
        assertEquals(
            setOf(
                HealthConnectRepository.WRITE_EXERCISE,
                HealthConnectRepository.WRITE_ACTIVE_CALORIES,
                HealthConnectRepository.WRITE_DISTANCE,
            ),
            healthConnectRequiredPermissions(batch.copy(sleepSessions = emptyList())),
        )
    }

    @Test
    fun workoutTombstoneDeletesSessionAndEveryDerivedRecord() {
        val batch = sampleBatch().copy(
            sleepSessions = emptyList(),
            workouts = emptyList(),
            deleted = listOf(DeletedHealthRecord("workout-1", DeletedHealthRecordKind.WORKOUT)),
        )

        val plan = healthConnectDeletionPlan(batch)

        assertEquals(listOf("cross-watch-workout:workout-1"), plan.workoutClientRecordIds)
        assertEquals(listOf("cross-watch-workout-energy:workout-1"), plan.workoutEnergyClientRecordIds)
        assertEquals(listOf("cross-watch-workout-distance:workout-1"), plan.workoutDistanceClientRecordIds)
    }

    @Test
    fun receiptStateReplaysDuplicatesAndRejectsIdentityOrGenerationConflicts() {
        val batch = sampleBatch()
        val applied = HealthReceiptState(
            requestGenerations = mapOf("request-1" to batch.generationMs),
            appliedGenerationHashes = mapOf(batch.generationMs to batch.fingerprint()),
            currentGenerationMs = batch.generationMs,
            currentHash = batch.fingerprint(),
            hasPendingSync = false,
        )

        assertEquals(HealthBatchAcceptance.ALREADY_IMPORTED, decideHealthReceipt(applied, "request-1", batch))
        assertEquals(HealthBatchAcceptance.ALREADY_IMPORTED, decideHealthReceipt(applied, "request-2", batch))
        assertThrows(IllegalArgumentException::class.java) {
            decideHealthReceipt(applied, "request-1", batch.copy(generationMs = batch.generationMs + 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decideHealthReceipt(applied, "request-2", batch.copy(workouts = emptyList()))
        }

        val newerCurrent = applied.copy(
            requestGenerations = emptyMap(),
            appliedGenerationHashes = emptyMap(),
            currentGenerationMs = batch.generationMs + 1L,
            currentHash = batch.copy(generationMs = batch.generationMs + 1L).fingerprint(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            decideHealthReceipt(newerCurrent, "request-old", batch)
        }

        val pending = applied.copy(
            requestGenerations = emptyMap(),
            appliedGenerationHashes = emptyMap(),
            hasPendingSync = true,
        )
        assertEquals(HealthBatchAcceptance.NEEDS_IMPORT, decideHealthReceipt(pending, "request-retry", batch))
        assertThrows(IllegalArgumentException::class.java) {
            decideHealthReceipt(pending, "request-new", batch.copy(generationMs = batch.generationMs + 1L))
        }
    }

    @Test
    fun receiptHistoriesRetainOnlyTheNewestDeterministicEntries() {
        val requestHistory = (1L..300L).associate { generation -> "request-${generation.toString().padStart(3, '0')}" to generation }
        val generationHistory = (1L..300L).associateWith { generation -> "hash-$generation" }

        val boundedRequests = boundHealthRequestHistory(requestHistory)
        val boundedGenerations = boundAppliedGenerationHistory(generationHistory)

        assertEquals(MAX_HEALTH_RECEIPT_HISTORY, boundedRequests.size)
        assertEquals(MAX_HEALTH_RECEIPT_HISTORY, boundedGenerations.size)
        assertEquals((45L..300L).toList(), boundedRequests.values.sorted())
        assertEquals((45L..300L).toList(), boundedGenerations.keys.sorted())
        assertEquals(
            listOf("request-a", "request-b"),
            boundHealthRequestHistory(mapOf("request-b" to 2L, "request-a" to 2L, "request-old" to 1L), limit = 2).keys.toList(),
        )
        assertFalse(boundedRequests.containsKey("request-044"))
        assertFalse(boundedGenerations.containsKey(44L))
    }

    @Test
    fun persistedReceiptHistoryRejectsCoercionAndInvalidHashes() {
        val hash = "a".repeat(64)
        assertEquals(
            mapOf("request-small" to 1L, "request-large" to Long.MAX_VALUE),
            decodeStoredRequestGenerations(
                JSONObject()
                    .put("request-small", 1)
                    .put("request-large", Long.MAX_VALUE),
            ),
        )
        listOf(
            JSONObject().put("request-1", "1"),
            JSONObject().put("request-1", 1.0),
            JSONObject().put("request-1", 0),
            JSONObject().put("request-1", -1L),
        ).forEach { corruptHistory ->
            assertThrows(IllegalStateException::class.java) {
                decodeStoredRequestGenerations(corruptHistory)
            }
        }

        assertEquals(
            mapOf(1L to hash, Long.MAX_VALUE to hash),
            decodeStoredAppliedGenerationHashes(
                JSONObject()
                    .put("1", hash)
                    .put(Long.MAX_VALUE.toString(), hash),
            ),
        )
        listOf(
            JSONObject().put("0", hash),
            JSONObject().put("01", hash),
            JSONObject().put("9223372036854775808", hash),
            JSONObject().put("1", "A".repeat(64)),
            JSONObject().put("1", "a".repeat(63)),
            JSONObject().put("1", "g".repeat(64)),
            JSONObject().put("1", 7),
        ).forEach { corruptHistory ->
            assertThrows(IllegalStateException::class.java) {
                decodeStoredAppliedGenerationHashes(corruptHistory)
            }
        }
    }

    @Test
    fun publicHealthFailuresDoNotContainParserOrRecordDetails() {
        val sensitiveRecordId = "watch-source-id-123"

        assertFalse(HealthSyncFailureMessages.INVALID_PAYLOAD.contains(sensitiveRecordId))
        assertFalse(HealthSyncFailureMessages.IMPORT_FAILED.contains(sensitiveRecordId))
        assertEquals(
            "Invalid health_sync payload; update Cross on both devices and retry",
            HealthSyncFailureMessages.INVALID_PAYLOAD,
        )
        assertEquals(
            "Health Connect import failed; the generation remains pending",
            HealthSyncFailureMessages.IMPORT_FAILED,
        )
    }

    @Test
    fun healthPrivacyRouteRecognizesBothPlatformActionsOnly() {
        assertTrue(HealthPermissionRationale.handles(HealthPermissionRationale.SHOW_RATIONALE_ACTION))
        assertTrue(HealthPermissionRationale.handles(HealthPermissionRationale.VIEW_PERMISSION_USAGE_ACTION))
        assertFalse(HealthPermissionRationale.handles(null))
        assertFalse(HealthPermissionRationale.handles("android.intent.action.MAIN"))
    }

    @Test
    fun mapperSortsValidatedSleepStagesDeterministically() {
        val batch = sampleBatch()
        val reversedStages = batch.sleepSessions.single().stages.reversed()
        val directModel = batch.copy(sleepSessions = listOf(batch.sleepSessions.single().copy(stages = reversedStages)))

        val sleep = HealthConnectMapper.records(directModel).first() as SleepSessionRecord

        assertEquals(reversedStages.sortedBy { it.startMs }.map { it.startMs }, sleep.stages.map { it.startTime.toEpochMilli() })
    }

    private fun sampleBatch(): HealthSyncBatch = HealthSyncBatch(
        generationMs = 1_735_689_600_000L,
        sleepSessions = listOf(
            SleepSession(
                id = "sleep-1",
                startMs = 1_735_660_800_000L,
                endMs = 1_735_689_600_000L,
                startZoneOffsetSeconds = 28_800,
                endZoneOffsetSeconds = 28_800,
                stages = listOf(
                    SleepStageInterval(1_735_660_800_000L, 1_735_668_000_000L, SleepStageType.LIGHT),
                    SleepStageInterval(1_735_668_000_000L, 1_735_675_200_000L, SleepStageType.DEEP),
                ),
            ),
        ),
        workouts = listOf(
            WorkoutSession(
                id = "workout-1",
                startMs = 1_735_650_000_000L,
                endMs = 1_735_653_600_000L,
                startZoneOffsetSeconds = 28_800,
                endZoneOffsetSeconds = 28_800,
                activity = "running",
                title = "Morning run",
                durationMs = 3_300_000L,
                activeEnergyKcal = 420.5,
                distanceMeters = 7_500.0,
            ),
        ),
        deleted = listOf(DeletedHealthRecord("old-workout", DeletedHealthRecordKind.WORKOUT)),
    )
}
