package com.cross.app.protocol

import com.cross.app.ble.MessageType
import com.cross.app.ble.ProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** JVM coverage for the stable JSON envelope and all supported wire names. */
class ProtocolTest {
    @Test
    fun envelopeRoundTrips() {
        val original = ProtocolMessage(MessageType.MEDIA_COMMAND, "command-1", ProtocolMessage.payload("command" to "next"))
        val decoded = ProtocolMessage.fromJson(original.toJson())
        assertEquals(2, JSONObject(original.toJson()).getInt("schema_version"))
        assertEquals(original.type, decoded.type)
        assertEquals(original.id, decoded.id)
        assertEquals("next", org.json.JSONObject(decoded.payloadJson).getString("command"))
    }

    @Test
    fun helloPayloadEncodesCapabilitiesAsAnExactJsonArray() {
        val encoded = JSONObject(
            ProtocolMessage(
                type = MessageType.HELLO,
                id = "hello-1",
                timestampMs = 1,
                payloadJson = ProtocolMessage.payload(
                    "device_id" to "android-install-1",
                    "device_name" to "Cross Android",
                    "app_version" to "1.0",
                    "capabilities" to JSONArray()
                        .put("notifications")
                        .put("media")
                        .put("volume")
                        .put("health")
                        .put("find_device"),
                ),
            ).toJson(),
        )

        assertEquals(2, encoded.getInt("schema_version"))
        assertEquals("hello", encoded.getString("type"))
        assertEquals("hello-1", encoded.getString("request_id"))
        assertEquals(1L, encoded.getLong("timestamp_ms"))
        assertEquals("android-install-1", encoded.getString("device_id"))
        assertEquals("Cross Android", encoded.getString("device_name"))
        assertEquals("1.0", encoded.getString("app_version"))
        val capabilitiesValue = encoded.get("capabilities")
        assertTrue(capabilitiesValue is JSONArray)
        val capabilities = capabilitiesValue as JSONArray
        assertEquals(
            listOf("notifications", "media", "volume", "health", "find_device"),
            (0 until capabilities.length()).map(capabilities::getString),
        )
    }

    @Test
    fun ackRoundTripsWithCorrelationAndResult() {
        val original = ProtocolMessage.ack("request-42", success = false, error = "unsupported")
        val encoded = JSONObject(original.toJson())
        val decoded = ProtocolMessage.fromJson(original.toJson())

        assertEquals("request-42", encoded.getString("ack_for"))
        assertEquals(false, encoded.getBoolean("success"))
        assertEquals("request-42", decoded.ackFor)
        assertEquals(false, JSONObject(decoded.payloadJson).getBoolean("success"))
        assertEquals("unsupported", JSONObject(decoded.payloadJson).getString("error"))
    }

    @Test
    fun ackForFieldIsAcceptedAndNormalized() {
        val decoded = ProtocolMessage.fromJson(
            "{\"schema_version\":2,\"type\":\"ack\",\"request_id\":\"ack-1\",\"timestamp_ms\":1735689600000,\"ack_for\":\"request-42\",\"success\":true}",
        )

        assertEquals("request-42", decoded.ackFor)
        assertEquals("request-42", JSONObject(decoded.payloadJson).getString("ack_for"))
        assertEquals("request-42", JSONObject(ProtocolMessage.ack(decoded.ackFor!!, success = true).toJson()).getString("ack_for"))
    }

    @Test
    fun automaticAckOnlyAppliesToNonAckMessages() {
        val request = ProtocolMessage(MessageType.PING, id = "request-1")
        val healthSync = ProtocolMessage(MessageType.HEALTH_SYNC, id = "health-1")
        val incomingAck = ProtocolMessage.fromJson(
            "{\"schema_version\":2,\"type\":\"ack\",\"request_id\":\"ack-1\",\"timestamp_ms\":1735689600000,\"ack_for\":\"request-1\",\"success\":true}",
        )

        assertEquals(true, ProtocolMessage.shouldAutomaticallyAck(request))
        assertEquals(false, ProtocolMessage.shouldAutomaticallyAck(healthSync))
        assertEquals(false, ProtocolMessage.shouldAutomaticallyAck(incomingAck))
        assertEquals("request-1", JSONObject(ProtocolMessage.ack(request.id, success = true).toJson()).getString("ack_for"))
        assertThrows(Exception::class.java) {
            ProtocolMessage.fromJson("{\"schema_version\":2,\"type\":\"ping\",\"timestamp_ms\":1735689600000}")
        }
    }

    @Test
    fun strictV2EnvelopeRejectsCoercedLegacyAndOversizedCorrelationIds() {
        val oversized = "x".repeat(129)
        val validBase = JSONObject()
            .put("type", "ping")
            .put("timestamp_ms", 1)

        listOf(
            JSONObject(validBase.toString()).put("schema_version", "2").put("request_id", "request-1"),
            JSONObject(validBase.toString()).put("schema_version", 1).put("request_id", "request-1"),
            JSONObject(validBase.toString()).put("schema_version", 2).put("request_id", 7),
            JSONObject(validBase.toString()).put("schema_version", 2).put("request_id", ""),
            JSONObject(validBase.toString()).put("schema_version", 2).put("request_id", " \t"),
            JSONObject(validBase.toString()).put("schema_version", 2).put("request_id", oversized),
        ).forEach { envelope ->
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolMessage.fromJson(envelope.toString())
            }
        }

        val legacyAck = JSONObject()
            .put("schema_version", 2)
            .put("type", "ack")
            .put("request_id", "ack-1")
            .put("timestamp_ms", 1)
            .put("ack_for", "request-1")
            .put("success", true)
            .put("for", "request-1")
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage.fromJson(legacyAck.toString())
        }
        listOf("id", "health_snapshot").forEach { legacyField ->
            val envelope = JSONObject(validBase.toString())
                .put("schema_version", 2)
                .put("request_id", "request-1")
                .put(legacyField, if (legacyField == "id") "legacy" else JSONObject())
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolMessage.fromJson(envelope.toString())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage.ack(oversized, success = true).toJson()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage(MessageType.PING, id = " \t").toJson()
        }
    }

    @Test
    fun identifiersUseTheCanonicalUtf8ByteLimit() {
        val exactly128Bytes = "é".repeat(64)
        val over128Bytes = "é".repeat(65)

        assertEquals(exactly128Bytes, ProtocolMessage.fromJson(
            JSONObject()
                .put("schema_version", 2)
                .put("type", "ping")
                .put("request_id", exactly128Bytes)
                .put("timestamp_ms", 1)
                .toString(),
        ).id)
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage(MessageType.PING, id = over128Bytes).toJson()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage.ack(over128Bytes, success = true).toJson()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage(MessageType.PING, id = "\uD800").toJson()
        }
    }

    @Test
    fun timestampMustBeAPositiveIntegralJsonNumber() {
        fun envelope(timestamp: Any): String = JSONObject()
            .put("schema_version", 2)
            .put("type", "ping")
            .put("request_id", "request-1")
            .put("timestamp_ms", timestamp)
            .toString()

        assertEquals(1L, ProtocolMessage.fromJson(envelope(1)).timestampMs)
        assertEquals(Long.MAX_VALUE, ProtocolMessage.fromJson(envelope(Long.MAX_VALUE)).timestampMs)
        listOf("0", "-1", "\"1\"", "1.0", "1.5").forEach { timestampLiteral ->
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolMessage.fromJson(
                    "{\"schema_version\":2,\"type\":\"ping\",\"request_id\":\"request-1\",\"timestamp_ms\":$timestampLiteral}",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage(MessageType.PING, timestampMs = 0).toJson()
        }
    }

    @Test
    fun ackRequiresAnExplicitBooleanSuccess() {
        fun ackEnvelope(success: Any?): String = JSONObject()
            .put("schema_version", 2)
            .put("type", "ack")
            .put("request_id", "ack-1")
            .put("timestamp_ms", 1)
            .put("ack_for", "health-1")
            .also { if (success != null) it.put("success", success) }
            .toString()

        assertEquals(true, JSONObject(ProtocolMessage.fromJson(ackEnvelope(true)).payloadJson).getBoolean("success"))
        assertEquals(false, JSONObject(ProtocolMessage.fromJson(ackEnvelope(false)).payloadJson).getBoolean("success"))
        listOf(null, "true", 1).forEach { success ->
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolMessage.fromJson(ackEnvelope(success))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage(MessageType.ACK, ackFor = "health-1").toJson()
        }
        listOf(1, true, JSONObject()).forEach { nonStringError ->
            val envelope = JSONObject(ackEnvelope(true)).put("error", nonStringError)
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolMessage.fromJson(envelope.toString())
            }
        }
        val nullError = JSONObject(ackEnvelope(true)).put("error", JSONObject.NULL)
        assertEquals(false, JSONObject(ProtocolMessage.fromJson(nullError.toString()).payloadJson).has("error"))
    }

    @Test
    fun healthSyncUsesTheRequiredV2NestedPayload() {
        val payload = ProtocolMessage.payload(
            "generation_ms" to 1_735_689_600_000L,
            "sleep_sessions" to JSONArray(),
            "workouts" to JSONArray(),
            "deleted" to JSONArray(),
        )
        val encoded = JSONObject(ProtocolMessage(MessageType.HEALTH_SYNC, "health-1", payload).toJson())
        val decoded = ProtocolMessage.fromJson(encoded.toString())

        assertEquals(2, encoded.getInt("schema_version"))
        assertEquals(1_735_689_600_000L, encoded.getJSONObject("health_sync").getLong("generation_ms"))
        assertEquals(1_735_689_600_000L, JSONObject(decoded.payloadJson).getLong("generation_ms"))
    }

    @Test
    fun allProtocolTypesHaveWireNames() = MessageType.entries.forEach { assertEquals(it, MessageType.fromWireName(it.wireName)) }
}
