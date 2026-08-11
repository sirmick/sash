package s1m.hwfido2provider.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the derived key usable between unlocks without keeping the passphrase.
 *
 * Two unlock paths exist on purpose. The **passphrase** is what makes a new
 * device work with nothing but the sync folder — it is the whole "log into your
 * home sync and away you go" claim. The **Keystore key** is what makes daily use
 * bearable. Type the passphrase once per device; authenticate after that.
 *
 * The wrapping key never leaves the Keystore, and requires the user to have
 * authenticated recently, so the wrapped blob in SharedPreferences is inert to
 * anyone who merely reads the file.
 */
object Keystore {
    private const val TAG = "latch"
    private const val ALIAS = "latch.wrap"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    /** How long after authenticating the wrapping key stays usable. */
    private const val AUTH_VALIDITY_SECONDS = 15

    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * True when the device can hold a user-authenticated key at all. Without a
     * screen lock there is no user to authenticate, and key generation fails —
     * so the passphrase remains the only way in, which is correct rather than
     * broken.
     */
    fun isAvailable(): Boolean = runCatching { wrappingKey() }.isSuccess

    fun wrap(key: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val sealed = cipher.doFinal(key)
        return java.util.Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }

    /**
     * Returns null when the user has not authenticated recently enough, or when
     * the key is gone.
     *
     * A changed screen lock or a new biometric enrolment **permanently
     * invalidates** the wrapping key — that is the point of it, since otherwise
     * enrolling a new fingerprint would silently grant access to the vault. When
     * it happens the wrapped blob is dead and the passphrase is the way back in,
     * so the caller should clear it rather than keep offering a path that cannot
     * work.
     */
    fun unwrap(wrapped: String): ByteArray? = try {
        val bytes = java.util.Base64.getDecoder().decode(wrapped)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(),
            GCMParameterSpec(128, bytes, 0, IV_BYTES)
        )
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.w(TAG, "keystore: wrapping key invalidated, passphrase required: ${e.message}")
        null
    } catch (e: Exception) {
        Log.d(TAG, "keystore: unwrap failed: ${e.message}")
        null
    }

    fun clear() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrappingKey(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
                // Enrolling a new fingerprint must not silently grant access to
                // an existing vault.
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return generator.generateKey()
    }
}
