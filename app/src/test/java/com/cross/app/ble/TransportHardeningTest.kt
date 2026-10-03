package com.cross.app.ble

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TransportHardeningTest {
    @Test
    fun firstPairPolicyAllowsSecureHandshakeButSuppressesOutboundUntilBinding() {
        assertTrue(PeerSessionPolicy.acceptsConnection(hasBinding = false, isBonded = false, matchesBinding = false))
        assertFalse(PeerSessionPolicy.allowsSubscription(hasBinding = false, isBonded = false, matchesBinding = false))
        assertTrue(PeerSessionPolicy.allowsSubscription(hasBinding = false, isBonded = true, matchesBinding = false))
        assertTrue(PeerSessionPolicy.acceptsAuthenticatedInbound(hasBinding = false, isBonded = true, matchesBinding = false))
        assertFalse(PeerSessionPolicy.allowsBindingClaim(isBonded = true, notificationsEnabled = false, isValidHello = true))
        assertFalse(PeerSessionPolicy.allowsBindingClaim(isBonded = true, notificationsEnabled = true, isValidHello = false))
        assertFalse(PeerSessionPolicy.allowsApplicationData(hasBinding = false, isBonded = true, matchesBinding = false, notificationsEnabled = true, helloValidated = false))
        assertTrue(PeerSessionPolicy.allowsBindingClaim(isBonded = true, notificationsEnabled = true, isValidHello = true))
        assertFalse(PeerSessionPolicy.allowsApplicationData(hasBinding = true, isBonded = true, matchesBinding = true, notificationsEnabled = false, helloValidated = true))
        assertFalse(PeerSessionPolicy.allowsApplicationData(hasBinding = true, isBonded = true, matchesBinding = true, notificationsEnabled = true, helloValidated = false))
        assertTrue(PeerSessionPolicy.allowsApplicationData(hasBinding = true, isBonded = true, matchesBinding = true, notificationsEnabled = true, helloValidated = true))
    }

    @Test
    fun durableBindingRejectsEveryNonmatchingOrUnbondedPeer() {
        assertFalse(PeerSessionPolicy.acceptsConnection(hasBinding = true, isBonded = false, matchesBinding = true))
        assertFalse(PeerSessionPolicy.acceptsConnection(hasBinding = true, isBonded = true, matchesBinding = false))
        assertFalse(PeerSessionPolicy.acceptsAuthenticatedInbound(hasBinding = true, isBonded = true, matchesBinding = false))
        assertFalse(PeerSessionPolicy.allowsSubscription(hasBinding = true, isBonded = true, matchesBinding = false))
        assertTrue(PeerSessionPolicy.acceptsConnection(hasBinding = true, isBonded = true, matchesBinding = true))
    }

    @Test
    fun validHelloRequiresCompleteStrictIdentityPayload() {
        val hello = ProtocolMessage.fromJson(
            ProtocolMessage(
                type = MessageType.HELLO,
                id = "hello-1",
                payloadJson = JSONObject()
                    .put("device_id", "watch-1")
                    .put("device_name", "Watch")
                    .put("app_version", "1.0")
                    .put("capabilities", JSONArray(listOf("media", "health", "find_device")))
                    .toString(),
            ).toJson(),
        )

        assertEquals("watch-1", validateHelloMessage(hello))
    }

    @Test
    fun helloRejectsMissingOrCoercedFields() {
        val validPayload = JSONObject()
            .put("device_id", "watch-1")
            .put("device_name", "Watch")
            .put("app_version", "1.0")
            .put("capabilities", JSONArray(listOf("media", "health", "find_device")))

        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage.fromJson(
                JSONObject(validPayload.toString())
                    .put("schema_version", 2)
                    .put("type", "hello")
                    .put("request_id", 42)
                    .put("timestamp_ms", 1)
                    .toString(),
            )
        }
        val legacyHello = JSONObject(validPayload.toString())
            .put("schema_version", 2)
            .put("type", "hello")
            .put("id", "legacy-hello")
            .put("timestamp_ms", 1)
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolMessage.fromJson(legacyHello.toString())
        }
        listOf("device_id", "device_name", "app_version").forEach { key ->
            val payload = JSONObject(validPayload.toString()).put(key, " ")
            assertThrows(IllegalArgumentException::class.java) {
                validateHelloMessage(ProtocolMessage(MessageType.HELLO, "hello-1", payload.toString()))
            }
        }
        listOf("", "é".repeat(65)).forEach { invalidDeviceId ->
            val payload = JSONObject(validPayload.toString()).put("device_id", invalidDeviceId)
            assertThrows(IllegalArgumentException::class.java) {
                validateHelloMessage(ProtocolMessage(MessageType.HELLO, "hello-1", payload.toString()))
            }
        }
        val nonArrayCapabilities = JSONObject(validPayload.toString()).put("capabilities", "notifications")
        assertThrows(IllegalArgumentException::class.java) {
            validateHelloMessage(ProtocolMessage(MessageType.HELLO, "hello-1", nonArrayCapabilities.toString()))
        }
        val blankCapability = JSONObject(validPayload.toString()).put("capabilities", JSONArray(listOf("")))
        assertThrows(IllegalArgumentException::class.java) {
            validateHelloMessage(ProtocolMessage(MessageType.HELLO, "hello-1", blankCapability.toString()))
        }
        val missingCapability = JSONObject(validPayload.toString())
            .put("capabilities", JSONArray(listOf("media", "find_device")))
        assertThrows(IllegalArgumentException::class.java) {
            validateHelloMessage(ProtocolMessage(MessageType.HELLO, "hello-1", missingCapability.toString()))
        }
    }

    @Test
    fun peerBindingDecodeFailsClosed() {
        val addressKey = "peer_address"
        val deviceIdKey = "peer_device_id"
        assertNull(PeerBindingCodec.decode(emptyMap<String, Any?>(), addressKey, deviceIdKey))
        assertEquals(
            PeerBinding("AA:BB:CC:DD:EE:FF", "watch-1"),
            PeerBindingCodec.decode(
                mapOf(addressKey to "aa:bb:cc:dd:ee:ff", deviceIdKey to "watch-1"),
                addressKey,
                deviceIdKey,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            PeerBindingCodec.decode(mapOf(addressKey to "AA:BB:CC:DD:EE:FF"), addressKey, deviceIdKey)
        }
        assertThrows(IllegalStateException::class.java) {
            PeerBindingCodec.decode(mapOf(deviceIdKey to "watch-1"), addressKey, deviceIdKey)
        }
        assertThrows(IllegalStateException::class.java) {
            PeerBindingCodec.decode(mapOf(addressKey to "AA:BB:CC:DD:EE:FF", deviceIdKey to 7), addressKey, deviceIdKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerBindingCodec.decode(mapOf(addressKey to "not-an-address", deviceIdKey to "watch-1"), addressKey, deviceIdKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerBindingCodec.decode(mapOf(addressKey to "AA:BB:CC:DD:EE:FF", deviceIdKey to "é".repeat(65)), addressKey, deviceIdKey)
        }
    }

    @Test
    fun installIdentityIsCanonicalPersistentAndFailsClosedWhenCorrupt() {
        val initializedKey = "identity_initialized"
        val deviceIdKey = "android_device_id"
        assertNull(InstallIdentityCodec.decode(emptyMap<String, Any?>(), initializedKey, deviceIdKey))

        val generated = InstallIdentityCodec.generate()
        assertEquals(generated, UUID.fromString(generated).toString())
        assertEquals(
            generated,
            InstallIdentityCodec.decode(
                mapOf(initializedKey to true, deviceIdKey to generated),
                initializedKey,
                deviceIdKey,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            InstallIdentityCodec.decode(mapOf(initializedKey to true), initializedKey, deviceIdKey)
        }
        assertThrows(IllegalStateException::class.java) {
            InstallIdentityCodec.decode(mapOf(deviceIdKey to generated), initializedKey, deviceIdKey)
        }
        assertThrows(IllegalStateException::class.java) {
            InstallIdentityCodec.decode(mapOf(initializedKey to false, deviceIdKey to generated), initializedKey, deviceIdKey)
        }
        assertThrows(IllegalStateException::class.java) {
            InstallIdentityCodec.decode(mapOf(initializedKey to true, deviceIdKey to "android"), initializedKey, deviceIdKey)
        }
        assertThrows(IllegalStateException::class.java) {
            InstallIdentityCodec.decode(
                mapOf(initializedKey to true, deviceIdKey to "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE"),
                initializedKey,
                deviceIdKey,
            )
        }
    }

    @Test
    fun bindingStateRejectsChangedDeviceIdAndRequiresHelloOnEveryConnection() {
        val original = PeerBindingCodec.encode("AA:BB:CC:DD:EE:FF", "watch-1")
        var state = PeerBindingState().acceptHello(original)
        assertTrue(state.helloValidated)

        state = state.beginConnection()
        assertFalse(state.helloValidated)
        assertFalse(PeerSessionPolicy.allowsApplicationData(true, true, true, true, state.helloValidated))
        assertThrows(IllegalArgumentException::class.java) {
            state.acceptHello(PeerBindingCodec.encode("AA:BB:CC:DD:EE:FF", "watch-2"))
        }

        state = state.acceptHello(original)
        assertTrue(state.helloValidated)
        state = state.reset()
        assertNull(state.binding)
        assertFalse(state.helloValidated)
    }

    @Test
    fun queueRejectsWholeFrameWithoutMutationAtEitherBound() {
        val queue = BoundedNotificationQueue(maxChunks = 3, maxBytes = 5)
        assertTrue(queue.enqueueFrame(listOf(byteArrayOf(1, 2), byteArrayOf(3))))
        assertFalse(queue.enqueueFrame(listOf(byteArrayOf(4), byteArrayOf(5, 6))))
        assertEquals(2, queue.size())
        assertEquals(3, queue.bytes())

        assertFalse(queue.enqueueFrame(listOf(byteArrayOf(4, 5, 6))))
        assertEquals(2, queue.size())
        assertEquals(3, queue.bytes())
    }

    @Test
    fun queueAdvancesOnlyWhenHeadCompletes() {
        val queue = BoundedNotificationQueue(maxChunks = 3, maxBytes = 6)
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3)
        assertTrue(queue.enqueueFrame(listOf(first, second)))

        assertTrue(first.contentEquals(queue.firstOrNull()))
        assertEquals(2, queue.size())
        queue.completeFirst()
        assertTrue(second.contentEquals(queue.firstOrNull()))
        assertEquals(1, queue.size())
        assertEquals(1, queue.bytes())
    }
}
