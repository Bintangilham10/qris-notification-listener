package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.upload.ListenerTestVectors.DEVICE_ID
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_BODY
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_BODY_SHA256
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_NONCE
import id.ciganstore.qrislistener.upload.ListenerTestVectors.EVENT_SIGNATURE
import id.ciganstore.qrislistener.upload.ListenerTestVectors.HEARTBEAT_BODY_SHA256
import id.ciganstore.qrislistener.upload.ListenerTestVectors.HEARTBEAT_NONCE
import id.ciganstore.qrislistener.upload.ListenerTestVectors.HEARTBEAT_SIGNATURE
import id.ciganstore.qrislistener.upload.ListenerTestVectors.TIMESTAMP
import id.ciganstore.qrislistener.upload.ListenerTestVectors.TIMESTAMP_MILLIS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class QrisSignerTest {
    private val eventBytes = EVENT_BODY.toByteArray(StandardCharsets.UTF_8)
    private val heartbeatBytes = "{}".toByteArray(StandardCharsets.UTF_8)

    private fun signer() = QrisSigner(ListenerTestVectors.secretKey())

    private fun factory(
        nowMillis: Long = TIMESTAMP_MILLIS,
        nonce: (() -> String)? = null,
        appVersion: String? = "0.2.0",
    ) = if (nonce == null) {
        ListenerRequestFactory("https://ciganstore.ai.id", DEVICE_ID, signer(), appVersion, { nowMillis })
    } else {
        ListenerRequestFactory("https://ciganstore.ai.id", DEVICE_ID, signer(), appVersion, { nowMillis }, nonce)
    }

    @Test
    fun `test secret decodes to the 32 key bytes`() {
        val key = ListenerTestVectors.secretKey()

        assertEquals(32, key.size)
        assertTrue(key.all { it == 3.toByte() })
    }

    @Test
    fun `event body hash matches the Worker vector`() {
        assertEquals(64, ListenerTestVectors.EVENT_ID.length)
        assertEquals(EVENT_BODY_SHA256, QrisSigner.sha256Hex(eventBytes))
    }

    @Test
    fun `event signature matches the Worker vector`() {
        val canonical = QrisSigner.canonicalString(
            path = "/api/v1/listener/events",
            deviceId = DEVICE_ID,
            timestamp = TIMESTAMP,
            nonce = EVENT_NONCE,
            bodySha256Hex = QrisSigner.sha256Hex(eventBytes),
        )

        assertEquals(
            "QRIS1\nPOST\n/api/v1/listener/events\nprimary-android\n1792900800\nabcdefghijklmnopQRSTUV\n" +
                EVENT_BODY_SHA256,
            canonical,
        )
        assertEquals(EVENT_SIGNATURE, signer().sign(canonical))
    }

    @Test
    fun `heartbeat signature matches the Worker vector`() {
        val bodyHash = QrisSigner.sha256Hex(heartbeatBytes)
        val canonical = QrisSigner.canonicalString(
            path = "/api/v1/listener/heartbeat",
            deviceId = DEVICE_ID,
            timestamp = TIMESTAMP,
            nonce = HEARTBEAT_NONCE,
            bodySha256Hex = bodyHash,
        )

        assertEquals(HEARTBEAT_BODY_SHA256, bodyHash)
        assertEquals(HEARTBEAT_SIGNATURE, signer().sign(canonical))
    }

    @Test
    fun `signed event request carries every Worker header`() {
        // 999 ms past the vector second: the timestamp must be floored, never rounded up.
        val request = factory(nowMillis = TIMESTAMP_MILLIS + 999, nonce = { EVENT_NONCE })
            .create(ListenerProtocol.EVENTS_PATH, eventBytes)

        assertEquals("https://ciganstore.ai.id/api/v1/listener/events", request.url)
        assertEquals(
            mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "X-QRIS-Version" to "1",
                "X-QRIS-Device-Id" to "primary-android",
                "X-QRIS-Timestamp" to "1792900800",
                "X-QRIS-Nonce" to "abcdefghijklmnopQRSTUV",
                "X-QRIS-Signature" to EVENT_SIGNATURE,
                "X-QRIS-App-Version" to "0.2.0",
            ),
            request.headers,
        )
        assertArrayEquals(eventBytes, request.body)
    }

    @Test
    fun `signed heartbeat request matches the Worker vector`() {
        val request = factory(nonce = { HEARTBEAT_NONCE }).create(ListenerProtocol.HEARTBEAT_PATH, heartbeatBytes)

        assertEquals("https://ciganstore.ai.id/api/v1/listener/heartbeat", request.url)
        assertEquals(HEARTBEAT_SIGNATURE, request.headers["X-QRIS-Signature"])
        assertEquals("{}", String(request.body, StandardCharsets.UTF_8))
    }

    @Test
    fun `every request is signed again with a fresh nonce`() {
        val requests = factory()
        val first = requests.create(ListenerProtocol.EVENTS_PATH, eventBytes)
        val second = requests.create(ListenerProtocol.EVENTS_PATH, eventBytes)

        assertNotEquals(first.headers["X-QRIS-Nonce"], second.headers["X-QRIS-Nonce"])
        assertNotEquals(first.headers["X-QRIS-Signature"], second.headers["X-QRIS-Signature"])
        listOf(first, second).forEach { request ->
            assertTrue(ListenerProtocol.NONCE_PATTERN.matches(request.headers.getValue("X-QRIS-Nonce")))
            assertTrue(ListenerProtocol.SIGNATURE_PATTERN.matches(request.headers.getValue("X-QRIS-Signature")))
            assertTrue(ListenerProtocol.TIMESTAMP_PATTERN.matches(request.headers.getValue("X-QRIS-Timestamp")))
        }
    }

    @Test
    fun `random nonces fit the Worker pattern and do not repeat`() {
        val nonces = List(500) { QrisSigner.newNonce() }

        nonces.forEach { nonce ->
            assertTrue(nonce, ListenerProtocol.NONCE_PATTERN.matches(nonce))
            assertEquals(32, nonce.length)
        }
        assertEquals(nonces.size, nonces.toSet().size)
    }

    @Test
    fun `timestamp is ten digit unix seconds`() {
        assertEquals("1792900800", ListenerRequestFactory.unixTimestampSeconds(TIMESTAMP_MILLIS))
        assertEquals("1792900800", ListenerRequestFactory.unixTimestampSeconds(TIMESTAMP_MILLIS + 999))
        assertEquals("1000000000", ListenerRequestFactory.unixTimestampSeconds(1_000_000_000_000L))
    }

    @Test
    fun `a clock outside the ten digit range cannot sign`() {
        listOf(999_999_999_999L, 10_000_000_000_000L, 0L, -1L).forEach { millis ->
            assertThrows(InvalidDeviceClockException::class.java) {
                ListenerRequestFactory.unixTimestampSeconds(millis)
            }
        }
        assertThrows(InvalidDeviceClockException::class.java) {
            factory(nowMillis = 946_684_800_000L).create(ListenerProtocol.HEARTBEAT_PATH, heartbeatBytes)
        }
    }

    @Test
    fun `invalid app version is left out instead of sent`() {
        val request = factory(appVersion = "0.2.0 beta").create(ListenerProtocol.HEARTBEAT_PATH, heartbeatBytes)

        assertFalse(request.headers.containsKey("X-QRIS-App-Version"))
    }

    @Test
    fun `short secrets and invalid device ids are refused`() {
        assertThrows(IllegalArgumentException::class.java) { QrisSigner(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) {
            ListenerRequestFactory("https://ciganstore.ai.id", "ab", signer(), null, { TIMESTAMP_MILLIS })
        }
    }

    @Test
    fun `signatures are 43 character base64url without padding`() {
        val signature = signer().sign("anything")

        assertEquals(43, signature.length)
        assertTrue(ListenerProtocol.SIGNATURE_PATTERN.matches(signature))
    }
}
