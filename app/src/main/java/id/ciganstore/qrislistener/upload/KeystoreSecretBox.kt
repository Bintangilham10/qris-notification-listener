package id.ciganstore.qrislistener.upload

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets with an AES-256-GCM key generated inside Android Keystore. The key material
 * never leaves the Keystore (it is not exportable); only ciphertext and IV are stored by the app.
 * The key needs no user authentication, so background upload work can use it while the phone is
 * locked.
 */
class KeystoreSecretBox(private val keyAlias: String = DEFAULT_KEY_ALIAS) {

    class Sealed(val ciphertext: ByteArray, val iv: ByteArray)

    @Throws(GeneralSecurityException::class)
    fun seal(plaintext: ByteArray): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        val ciphertext = cipher.doFinal(plaintext)
        return Sealed(ciphertext = ciphertext, iv = cipher.iv)
    }

    @Throws(GeneralSecurityException::class)
    fun open(sealed: Sealed): ByteArray {
        val key = existingKey() ?: throw GeneralSecurityException("The listener secret key is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, sealed.iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(sealed.ciphertext)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "id.ciganstore.qrislistener.listener-secret.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
        val ASSOCIATED_DATA = "qris-listener/listener-shared-secret/v1".toByteArray(Charsets.UTF_8)
    }
}
