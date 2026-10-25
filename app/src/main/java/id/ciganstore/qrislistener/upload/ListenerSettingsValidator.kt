package id.ciganstore.qrislistener.upload

import java.net.URI
import java.net.URISyntaxException
import java.util.Base64
import java.util.Locale

/** Validation for the uploader settings typed on the phone. */
object ListenerSettingsValidator {
    /** Plain HTTP is only allowed to these hosts, and only in debug builds (local `wrangler dev`). */
    val DEBUG_CLEARTEXT_HOSTS: Set<String> = setOf("10.0.2.2", "127.0.0.1", "localhost")

    private val base64UrlPattern = Regex("^[A-Za-z0-9_-]+$")

    enum class BaseUrlProblem {
        EMPTY,
        MALFORMED,
        NOT_HTTPS,
        HAS_CREDENTIALS,
        HAS_PATH,
    }

    sealed interface BaseUrlResult {
        data class Valid(val normalized: String) : BaseUrlResult
        data class Invalid(val problem: BaseUrlProblem) : BaseUrlResult
    }

    enum class SecretProblem {
        /** No secret typed and none stored yet. */
        REQUIRED,

        /** Not base64url, or shorter than 32 bytes. */
        INVALID,
    }

    sealed interface FormResult {
        /** [secretKey] is null when the field was left empty to keep the stored secret. Wipe it after use. */
        class Valid(val baseUrl: String, val deviceId: String, val secretKey: ByteArray?) : FormResult

        data class Invalid(
            val baseUrlProblem: BaseUrlProblem?,
            val deviceIdInvalid: Boolean,
            val secretProblem: SecretProblem?,
        ) : FormResult
    }

    /** Validates the settings form. An empty secret field keeps the stored secret, if there is one. */
    fun validateForm(
        baseUrl: String,
        deviceId: String,
        secretInput: String,
        hasStoredSecret: Boolean,
        allowDebugCleartext: Boolean,
    ): FormResult {
        val url = validateBaseUrl(baseUrl, allowDebugCleartext)
        val trimmedDeviceId = deviceId.trim()
        val deviceIdValid = isValidDeviceId(trimmedDeviceId)
        var secretKey: ByteArray? = null
        val secretProblem = if (secretInput.isBlank()) {
            if (hasStoredSecret) null else SecretProblem.REQUIRED
        } else {
            secretKey = decodeSecret(secretInput)
            if (secretKey == null) SecretProblem.INVALID else null
        }

        if (url is BaseUrlResult.Valid && deviceIdValid && secretProblem == null) {
            return FormResult.Valid(url.normalized, trimmedDeviceId, secretKey)
        }
        secretKey?.fill(0)
        return FormResult.Invalid(
            baseUrlProblem = (url as? BaseUrlResult.Invalid)?.problem,
            deviceIdInvalid = !deviceIdValid,
            secretProblem = secretProblem,
        )
    }

    /**
     * Accepts an origin such as `https://ciganstore.ai.id` (an optional trailing slash is dropped).
     * The listener paths are fixed and signed, so a base path, query, fragment or user info is refused.
     */
    fun validateBaseUrl(input: String, allowDebugCleartext: Boolean): BaseUrlResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return BaseUrlResult.Invalid(BaseUrlProblem.EMPTY)

        val uri = try {
            URI(trimmed)
        } catch (error: URISyntaxException) {
            return BaseUrlResult.Invalid(BaseUrlProblem.MALFORMED)
        }
        if (uri.isOpaque) return BaseUrlResult.Invalid(BaseUrlProblem.MALFORMED)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return BaseUrlResult.Invalid(BaseUrlProblem.MALFORMED)
        if (scheme != "https" && scheme != "http") return BaseUrlResult.Invalid(BaseUrlProblem.NOT_HTTPS)
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: return BaseUrlResult.Invalid(BaseUrlProblem.MALFORMED)
        if (uri.rawUserInfo != null) return BaseUrlResult.Invalid(BaseUrlProblem.HAS_CREDENTIALS)

        val path = uri.rawPath.orEmpty()
        if ((path.isNotEmpty() && path != "/") || uri.rawQuery != null || uri.rawFragment != null) {
            return BaseUrlResult.Invalid(BaseUrlProblem.HAS_PATH)
        }
        val port = uri.port
        if (port == 0 || port > MAX_PORT) return BaseUrlResult.Invalid(BaseUrlProblem.MALFORMED)
        if (scheme == "http" && (!allowDebugCleartext || host !in DEBUG_CLEARTEXT_HOSTS)) {
            return BaseUrlResult.Invalid(BaseUrlProblem.NOT_HTTPS)
        }

        val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
        val portSuffix = if (port == -1 || port == defaultPort) "" else ":$port"
        return BaseUrlResult.Valid("$scheme://$host$portSuffix")
    }

    fun isValidDeviceId(value: String): Boolean = ListenerProtocol.DEVICE_ID_PATTERN.matches(value)

    /**
     * Decodes the shared secret the way the Worker does (base64url, padding optional) and requires at
     * least 32 bytes. Returns null when invalid. The caller should wipe the returned bytes after use.
     */
    fun decodeSecret(input: String): ByteArray? {
        val normalized = input.trim().trimEnd('=')
        if (!base64UrlPattern.matches(normalized) || normalized.length % 4 == 1) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(normalized)
        } catch (error: IllegalArgumentException) {
            return null
        }
        if (bytes.size < ListenerProtocol.MIN_SECRET_BYTES) {
            bytes.fill(0)
            return null
        }
        return bytes
    }

    private const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80
    private const val MAX_PORT = 65_535
}
