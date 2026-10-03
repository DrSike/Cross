package com.cross.app.ble

import org.json.JSONObject
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Cross v2 wire messages. The JSON shape mirrors the watchOS Codable envelope. */
enum class MessageType(val wireName: String) {
    HELLO("hello"),
    NOTIFICATION("notification"),
    NOTIFICATION_REMOVED("notification_removed"),
    NOTIFICATION_SYNC_REQUEST("notification_sync_request"),
    NOTIFICATION_SYNC("notification_sync"),
    NOTIFICATION_ACTION("notification_action"),
    MEDIA_STATE("media_state"),
    MEDIA_COMMAND("media_command"),
    HEALTH_SYNC("health_sync"),
    FIND_DEVICE("find_device"),
    PING("ping"),
    ACK("ack");

    companion object {
        fun fromWireName(value: String): MessageType? = entries.firstOrNull { it.wireName == value }
    }
}

/** A platform-neutral message whose payload is kept as JSON to avoid a shared runtime dependency. */
data class ProtocolMessage(
    val type: MessageType,
    val id: String = UUID.randomUUID().toString(),
    val payloadJson: String = "{}",
    val timestampMs: Long = System.currentTimeMillis(),
    val ackFor: String? = null,
) {
    /** Encodes the message using the envelope consumed by the Swift watch client. */
    fun toJson(): String {
        val requestId = requireBoundedProtocolIdentifier(id, "request_id")
        requirePositiveIntegralTimestamp(timestampMs)
        val payload = JSONObject(payloadJson)
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("type", type.wireName)
            .put("request_id", requestId)
            .put("timestamp_ms", timestampMs)
            .also { root -> addTypedPayload(root, payload, ackFor) }
            .toString()
    }

    companion object {
        /** Decodes the required schema-v2 watch envelope. */
        fun fromJson(json: String): ProtocolMessage {
            val root = JSONObject(json)
            require(root.opt("schema_version") == SCHEMA_VERSION) { "Unsupported Cross schema_version" }
            require(LEGACY_FIELDS.none(root::has)) { "Legacy Cross protocol fields are not supported" }
            val type = MessageType.fromWireName(root.getString("type")) ?: error("Unsupported Cross message type")
            val id = requireBoundedProtocolIdentifier(root.opt("request_id"), "request_id")
            val timestampMs = requirePositiveIntegralTimestamp(root.opt("timestamp_ms"))
            val payload = extractTypedPayload(root, type)
            val ackFor = if (type == MessageType.ACK) {
                requireBoundedProtocolIdentifier(root.opt("ack_for"), "ack_for")
            } else {
                null
            }
            return ProtocolMessage(type, id, payload, timestampMs, ackFor)
        }

        /** Health sync ACKs are sent only after durable import; other non-ACK messages acknowledge receipt. */
        fun shouldAutomaticallyAck(message: ProtocolMessage): Boolean =
            message.type != MessageType.ACK && message.type != MessageType.HEALTH_SYNC && message.id.isNotBlank()

        /** Builds an ACK with an explicit correlation target and result. */
        fun ack(ackFor: String, success: Boolean, error: String? = null): ProtocolMessage = ProtocolMessage(
            type = MessageType.ACK,
            payloadJson = JSONObject().apply {
                put("success", success)
                if (error != null) put("error", error)
            }.toString(),
            ackFor = ackFor,
        )

        fun payload(vararg values: Pair<String, Any?>): String = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }.toString()

        private fun addTypedPayload(root: JSONObject, payload: JSONObject, ackFor: String?) {
            when (MessageType.fromWireName(root.getString("type"))) {
                MessageType.NOTIFICATION -> root.put("notification", payload)
                MessageType.NOTIFICATION_REMOVED, MessageType.NOTIFICATION_SYNC -> copyIfPresent(payload, root, "notification_id", "notification_ids")
                MessageType.NOTIFICATION_SYNC_REQUEST -> Unit
                MessageType.MEDIA_STATE -> root.put("media_state", payload)
                MessageType.HEALTH_SYNC -> root.put("health_sync", payload)
                MessageType.NOTIFICATION_ACTION -> copyIfPresent(payload, root, "notification_id", "action_id", "reply")
                MessageType.MEDIA_COMMAND -> copyIfPresent(payload, root, "command")
                MessageType.FIND_DEVICE -> copyIfPresent(payload, root, "target", "duration_ms")
                MessageType.PING -> copyIfPresent(payload, root, "nonce")
                MessageType.ACK -> {
                    val correlationId = requireBoundedProtocolIdentifier(ackFor, "ack_for")
                    val success = payload.opt("success")
                    require(success is Boolean) { "Cross ACK success must be a Boolean" }
                    root.put("ack_for", correlationId)
                    root.put("success", success)
                    copyOptionalAckError(payload, root)
                }
                MessageType.HELLO -> copyIfPresent(payload, root, "device_id", "device_name", "app_version", "capabilities")
                null -> Unit
            }
        }

        private fun extractTypedPayload(root: JSONObject, type: MessageType): String {
            val payload = when (type) {
                MessageType.NOTIFICATION -> root.optJSONObject("notification")
                MessageType.MEDIA_STATE -> root.optJSONObject("media_state")
                MessageType.HEALTH_SYNC -> root.getJSONObject("health_sync")
                MessageType.ACK -> JSONObject().also { target ->
                    val success = root.opt("success")
                    require(success is Boolean) { "Cross ACK success must be a Boolean" }
                    target.put("ack_for", root.getString("ack_for"))
                    target.put("success", success)
                    copyOptionalAckError(root, target)
                }
                else -> JSONObject().also { target ->
                    copyIfPresent(root, target, "notification_id", "notification_ids", "action_id", "reply", "command", "target", "duration_ms", "nonce", "device_id", "device_name", "app_version", "capabilities")
                }
            }
            return (payload ?: JSONObject()).toString()
        }

        private fun copyIfPresent(source: JSONObject, target: JSONObject, vararg keys: String) {
            keys.forEach { key -> if (source.has(key)) target.put(key, source.get(key)) }
        }

        private fun copyOptionalAckError(source: JSONObject, target: JSONObject) {
            if (!source.has("error") || source.isNull("error")) return
            val error = source.opt("error")
            require(error is String) { "Cross ACK error must be a string when present" }
            target.put("error", error)
        }

        private const val SCHEMA_VERSION = 2
        private val LEGACY_FIELDS = setOf("id", "for", "health_snapshot")
    }
}

internal fun validateHelloMessage(message: ProtocolMessage): String {
    require(message.type == MessageType.HELLO) { "Expected a HELLO message" }
    requireBoundedProtocolIdentifier(message.id, "request_id")
    val payload = JSONObject(message.payloadJson)
    val deviceId = requireBoundedProtocolIdentifier(payload.opt("device_id"), "device_id")
    mapOf("device_name" to 128, "app_version" to 64).forEach { (key, maxLength) ->
        require(payload.has(key) && payload.get(key) is String && payload.getString(key).isNotBlank() && payload.getString(key).length <= maxLength) {
            "HELLO is missing nonblank $key"
        }
    }
    require(payload.has("capabilities") && payload.get("capabilities") is JSONArray) {
        "HELLO capabilities must be an array"
    }
    val capabilities = payload.getJSONArray("capabilities")
    require(capabilities.length() > 0) { "HELLO capabilities must not be empty" }
    for (index in 0 until capabilities.length()) {
        require(capabilities.get(index) is String && capabilities.getString(index).isNotBlank()) {
            "HELLO capabilities must contain only nonblank strings"
        }
    }
    val advertisedCapabilities = (0 until capabilities.length()).map(capabilities::getString).toSet()
    require(advertisedCapabilities.containsAll(setOf("media", "health", "find_device"))) {
        "HELLO is missing required capabilities"
    }
    return deviceId
}

internal fun requireBoundedProtocolIdentifier(value: Any?, field: String): String {
    require(value is String && value.isNotBlank()) { "Cross message $field must be a nonblank string" }
    require(StandardCharsets.UTF_8.newEncoder().canEncode(value)) { "Cross message $field is not valid UTF-8" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROTOCOL_IDENTIFIER_UTF8_BYTES) {
        "Cross message $field must be at most $MAX_PROTOCOL_IDENTIFIER_UTF8_BYTES UTF-8 bytes"
    }
    return value
}

private fun requirePositiveIntegralTimestamp(value: Any?): Long {
    val timestamp = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Cross timestamp_ms must be an integral JSON number")
    }
    require(timestamp > 0L) { "Cross timestamp_ms must be positive" }
    return timestamp
}

internal const val MAX_PROTOCOL_IDENTIFIER_UTF8_BYTES = 128

/** JSON chunk codec compatible with CrossFrameCodec on watchOS. */
object FrameCodec {
    // Keep the raw slice aligned with CrossFrameCodec.maxChunkDataBytes on watchOS.
    // The JSON envelope and base64 expansion must fit in common BLE notifications.
    private const val DEFAULT_CHUNK_SIZE = 48
    private const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val MAX_CHUNK_COUNT = (MAX_MESSAGE_BYTES + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE
    private const val MAX_FRAME_ENVELOPE_BYTES = 1024
    private const val DEFAULT_REASSEMBLY_TIMEOUT_MS = 5_000L

    data class Frame(val messageId: String, val index: Int, val total: Int, val data: ByteArray)

    class Reassembler(
        private val nowMs: () -> Long = { System.currentTimeMillis() },
        private val timeoutMs: Long = DEFAULT_REASSEMBLY_TIMEOUT_MS,
    ) {
        private var messageId: String? = null
        private var total = 0
        private val parts = mutableMapOf<Int, ByteArray>()
        private var payloadBytes = 0
        private var lastAcceptedAtMs: Long? = null

        init {
            require(timeoutMs > 0) { "timeoutMs must be positive" }
        }

        fun accept(frameBytes: ByteArray): ByteArray? {
            val frame = decodeFrame(frameBytes) ?: return null
            val now = nowMs()
            if (messageId != null && lastAcceptedAtMs != null && now - lastAcceptedAtMs!! >= timeoutMs) reset()
            if (messageId != frame.messageId || total != frame.total) {
                reset()
                messageId = frame.messageId
                total = frame.total
            }
            if (parts.containsKey(frame.index)) return null
            if (payloadBytes + frame.data.size > MAX_MESSAGE_BYTES) {
                reset()
                return null
            }
            parts[frame.index] = frame.data
            payloadBytes += frame.data.size
            lastAcceptedAtMs = now
            if (parts.size != total || (0 until total).any { it !in parts }) return null
            val result = ByteArray(payloadBytes)
            var offset = 0
            parts.toSortedMap().values.forEach { bytes ->
                bytes.copyInto(result, offset)
                offset += bytes.size
            }
            reset()
            return result
        }

        /** Drops any incomplete frame so a new BLE connection can start cleanly. */
        fun reset() {
            messageId = null
            total = 0
            payloadBytes = 0
            lastAcceptedAtMs = null
            parts.clear()
        }
    }

    fun encode(message: ProtocolMessage, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<ByteArray> {
        require(chunkSize in 1..DEFAULT_CHUNK_SIZE) { "chunkSize must be between 1 and $DEFAULT_CHUNK_SIZE bytes" }
        val bytes = message.toJson().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_MESSAGE_BYTES) { "Cross message is too large" }
        val count = maxOf(1, (bytes.size + chunkSize - 1) / chunkSize)
        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(bytes.size, start + chunkSize)
            JSONObject()
                .put("frame_id", message.id)
                .put("chunk_index", index)
                .put("chunk_count", count)
                .put("payload", Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end)))
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun decodeFrame(bytes: ByteArray): Frame? {
        // Fallback: an untrusted BLE write can contain a truncated or malformed chunk; dropping that
        // chunk is recoverable because only a complete, independently validated frame is delivered.
        return runCatching {
            if (bytes.size > MAX_FRAME_ENVELOPE_BYTES) return@runCatching null
            val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            val id = root.opt("frame_id")
            if (id !is String) return@runCatching null
            val index = root.opt("chunk_index")
            if (index !is Int) return@runCatching null
            val total = root.opt("chunk_count")
            if (total !is Int) return@runCatching null
            val encodedPayload = root.opt("payload")
            if (encodedPayload !is String) return@runCatching null
            val data = Base64.getDecoder().decode(encodedPayload)
            if (id.isBlank() || total !in 1..MAX_CHUNK_COUNT || index !in 0 until total || data.size > DEFAULT_CHUNK_SIZE) return@runCatching null
            Frame(id, index, total, data)
        }.getOrNull()
    }
}
