package id.ciganstore.qrislistener.upload

import id.ciganstore.qrislistener.upload.ListenerSettingsValidator.BaseUrlProblem
import id.ciganstore.qrislistener.upload.ListenerSettingsValidator.BaseUrlResult
import id.ciganstore.qrislistener.upload.ListenerSettingsValidator.FormResult
import id.ciganstore.qrislistener.upload.ListenerSettingsValidator.SecretProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerSettingsValidatorTest {
    private fun url(input: String, debug: Boolean = false) = ListenerSettingsValidator.validateBaseUrl(input, debug)

    private fun problem(input: String, debug: Boolean = false) = (url(input, debug) as? BaseUrlResult.Invalid)?.problem

    @Test
    fun `accepts the production origin and normalizes it`() {
        assertEquals(BaseUrlResult.Valid("https://ciganstore.ai.id"), url("https://ciganstore.ai.id"))
        assertEquals(BaseUrlResult.Valid("https://ciganstore.ai.id"), url("  HTTPS://CiganStore.AI.id/  "))
        assertEquals(BaseUrlResult.Valid("https://ciganstore.ai.id"), url("https://ciganstore.ai.id:443"))
        assertEquals(BaseUrlResult.Valid("https://staging.example.com:8443"), url("https://staging.example.com:8443"))
    }

    @Test
    fun `release builds refuse plain http everywhere`() {
        listOf(
            "http://ciganstore.ai.id",
            "http://10.0.2.2:8787",
            "http://127.0.0.1:8787",
            "http://localhost:8787",
        ).forEach { input -> assertEquals(input, BaseUrlProblem.NOT_HTTPS, problem(input, debug = false)) }
    }

    @Test
    fun `debug builds allow http only to the emulator host and loopback`() {
        assertEquals(BaseUrlResult.Valid("http://10.0.2.2:8787"), url("http://10.0.2.2:8787", debug = true))
        assertEquals(BaseUrlResult.Valid("http://127.0.0.1:8787"), url("http://127.0.0.1:8787", debug = true))
        assertEquals(BaseUrlResult.Valid("http://localhost"), url("http://localhost:80", debug = true))
        assertEquals(BaseUrlProblem.NOT_HTTPS, problem("http://192.168.1.10:8787", debug = true))
        assertEquals(BaseUrlProblem.NOT_HTTPS, problem("http://ciganstore.ai.id", debug = true))
        assertEquals(BaseUrlResult.Valid("https://ciganstore.ai.id"), url("https://ciganstore.ai.id", debug = true))
    }

    @Test
    fun `refuses paths queries fragments and credentials`() {
        assertEquals(BaseUrlProblem.HAS_PATH, problem("https://ciganstore.ai.id/api"))
        assertEquals(BaseUrlProblem.HAS_PATH, problem("https://ciganstore.ai.id/api/v1/listener/events"))
        assertEquals(BaseUrlProblem.HAS_PATH, problem("https://ciganstore.ai.id/?a=1"))
        assertEquals(BaseUrlProblem.HAS_PATH, problem("https://ciganstore.ai.id#bagian"))
        assertEquals(BaseUrlProblem.HAS_CREDENTIALS, problem("https://admin:rahasia@ciganstore.ai.id"))
    }

    @Test
    fun `refuses empty and malformed addresses`() {
        assertEquals(BaseUrlProblem.EMPTY, problem(""))
        assertEquals(BaseUrlProblem.EMPTY, problem("   "))
        assertEquals(BaseUrlProblem.MALFORMED, problem("ciganstore.ai.id"))
        assertEquals(BaseUrlProblem.MALFORMED, problem("https://"))
        assertEquals(BaseUrlProblem.MALFORMED, problem("https://cigan store.ai.id"))
        assertEquals(BaseUrlProblem.MALFORMED, problem("mailto:admin@ciganstore.ai.id"))
        assertEquals(BaseUrlProblem.MALFORMED, problem("https://ciganstore.ai.id:0"))
        assertEquals(BaseUrlProblem.MALFORMED, problem("https://ciganstore.ai.id:70000"))
        assertEquals(BaseUrlProblem.NOT_HTTPS, problem("ftp://ciganstore.ai.id"))
    }

    @Test
    fun `device id follows the Worker header pattern`() {
        listOf("primary-android", "abc", "Toko_2-hp", "a".repeat(64)).forEach { id ->
            assertTrue(id, ListenerSettingsValidator.isValidDeviceId(id))
        }
        listOf("", "ab", "a".repeat(65), "primary android", "primary.android", "hp/1").forEach { id ->
            assertFalse(id, ListenerSettingsValidator.isValidDeviceId(id))
        }
    }

    @Test
    fun `decodes the shared secret the way the Worker does`() {
        val key = ListenerSettingsValidator.decodeSecret(ListenerTestVectors.SECRET)!!
        assertEquals(32, key.size)
        assertTrue(key.all { it == 3.toByte() })

        // Surrounding whitespace from copy and paste and optional padding are tolerated.
        val padded = ListenerSettingsValidator.decodeSecret("  ${ListenerTestVectors.SECRET}=\n")
        assertTrue(key.contentEquals(padded))
    }

    @Test
    fun `refuses secrets the Worker cannot use`() {
        listOf(
            "",
            "AwMDAwMD",
            // Standard base64 alphabet: the Worker only accepts base64url.
            "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw+",
            "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw/",
            // Pasted together with its label.
            "LISTENER_SHARED_SECRET=${ListenerTestVectors.SECRET}",
            "${ListenerTestVectors.SECRET} ${ListenerTestVectors.SECRET}",
            // Impossible base64 length.
            "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA",
        ).forEach { input -> assertNull(input, ListenerSettingsValidator.decodeSecret(input)) }
    }

    @Test
    fun `form keeps the stored secret when the field is left empty`() {
        val form = ListenerSettingsValidator.validateForm(
            baseUrl = "https://ciganstore.ai.id/",
            deviceId = " primary-android ",
            secretInput = "",
            hasStoredSecret = true,
            allowDebugCleartext = false,
        )

        assertTrue(form is FormResult.Valid)
        form as FormResult.Valid
        assertEquals("https://ciganstore.ai.id", form.baseUrl)
        assertEquals("primary-android", form.deviceId)
        assertNull(form.secretKey)
    }

    @Test
    fun `form decodes a newly typed secret`() {
        val form = ListenerSettingsValidator.validateForm(
            baseUrl = "https://ciganstore.ai.id",
            deviceId = "primary-android",
            secretInput = ListenerTestVectors.SECRET,
            hasStoredSecret = true,
            allowDebugCleartext = false,
        )

        val key = (form as FormResult.Valid).secretKey
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun `form requires a secret on first setup`() {
        val form = ListenerSettingsValidator.validateForm(
            baseUrl = "https://ciganstore.ai.id",
            deviceId = "primary-android",
            secretInput = "   ",
            hasStoredSecret = false,
            allowDebugCleartext = false,
        )

        assertEquals(FormResult.Invalid(null, false, SecretProblem.REQUIRED), form)
    }

    @Test
    fun `form reports every invalid field at once`() {
        val form = ListenerSettingsValidator.validateForm(
            baseUrl = "http://ciganstore.ai.id",
            deviceId = "x",
            secretInput = "bukan secret!",
            hasStoredSecret = true,
            allowDebugCleartext = false,
        )

        assertEquals(FormResult.Invalid(BaseUrlProblem.NOT_HTTPS, true, SecretProblem.INVALID), form)
    }
}
