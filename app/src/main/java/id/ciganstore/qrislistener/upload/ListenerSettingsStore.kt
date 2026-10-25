package id.ciganstore.qrislistener.upload

import android.content.Context
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Uploader settings in private SharedPreferences (excluded from backup). The shared secret is only
 * stored as AES-256-GCM ciphertext + IV from [KeystoreSecretBox]; it is never stored in plain text,
 * logged, or shown again after saving.
 */
class ListenerSettingsStore(
    context: Context,
    private val allowDebugCleartext: Boolean,
    private val secretBox: KeystoreSecretBox = KeystoreSecretBox(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    data class Summary(
        val baseUrl: String?,
        val deviceId: String,
        val hasSecret: Boolean,
    ) {
        val isComplete: Boolean get() = baseUrl != null && hasSecret
    }

    /** Decrypted settings for one upload run. Wipe [secretKey] as soon as the signer is built. */
    class Config(
        val baseUrl: String,
        val deviceId: String,
        val secretKey: ByteArray,
    )

    sealed interface LoadResult {
        class Ready(val config: Config) : LoadResult
        data object NotConfigured : LoadResult
        data object SecretUnavailable : LoadResult
    }

    fun summary(): Summary = Summary(
        baseUrl = preferences.getString(KEY_BASE_URL, null),
        deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: ListenerProtocol.DEFAULT_DEVICE_ID,
        hasSecret = preferences.contains(KEY_SECRET_CIPHERTEXT) && preferences.contains(KEY_SECRET_IV),
    )

    /**
     * Persists already validated settings. A null [secretKey] keeps the stored secret. The caller owns
     * [secretKey] and should wipe it afterwards.
     */
    @Throws(GeneralSecurityException::class)
    fun save(baseUrl: String, deviceId: String, secretKey: ByteArray?): Boolean {
        val editor = preferences.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_DEVICE_ID, deviceId)
        if (secretKey != null) {
            val sealed = secretBox.seal(secretKey)
            editor.putString(KEY_SECRET_CIPHERTEXT, encode(sealed.ciphertext))
            editor.putString(KEY_SECRET_IV, encode(sealed.iv))
        }
        return editor.commit()
    }

    fun load(): LoadResult {
        val summary = summary()
        val storedUrl = summary.baseUrl ?: return LoadResult.NotConfigured
        val validUrl = ListenerSettingsValidator.validateBaseUrl(storedUrl, allowDebugCleartext)
        val baseUrl = (validUrl as? ListenerSettingsValidator.BaseUrlResult.Valid)?.normalized
            ?: return LoadResult.NotConfigured
        if (!ListenerSettingsValidator.isValidDeviceId(summary.deviceId)) return LoadResult.NotConfigured
        if (!summary.hasSecret) return LoadResult.NotConfigured

        val ciphertext = decode(preferences.getString(KEY_SECRET_CIPHERTEXT, null))
        val iv = decode(preferences.getString(KEY_SECRET_IV, null))
        if (ciphertext == null || iv == null) return LoadResult.SecretUnavailable

        val secretKey = try {
            secretBox.open(KeystoreSecretBox.Sealed(ciphertext, iv))
        } catch (error: GeneralSecurityException) {
            return LoadResult.SecretUnavailable
        } catch (error: RuntimeException) {
            // Keystore failures can also surface as ProviderException or IllegalStateException.
            return LoadResult.SecretUnavailable
        }
        if (secretKey.size < ListenerProtocol.MIN_SECRET_BYTES) {
            secretKey.fill(0)
            return LoadResult.SecretUnavailable
        }
        return LoadResult.Ready(Config(baseUrl, summary.deviceId, secretKey))
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String?): ByteArray? {
        if (value == null) return null
        return try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "listener_upload_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SECRET_CIPHERTEXT = "secret_ciphertext"
        const val KEY_SECRET_IV = "secret_iv"
    }
}
