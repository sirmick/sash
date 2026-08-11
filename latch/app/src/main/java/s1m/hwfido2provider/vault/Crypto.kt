package s1m.hwfido2provider.vault

import com.google.crypto.tink.subtle.AesGcmJce
import java.security.GeneralSecurityException
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id parameters, stored in meta.json in the clear.
 *
 * They are not secret — an attacker holding the folder can read them either way
 * — and storing them is what lets a device with different defaults still open a
 * vault another device created. Hard-coding them instead means the day we raise
 * the cost, every existing vault becomes unopenable.
 */
@Serializable
data class KdfParams(
    val algo: String = "argon2id",
    val memoryKb: Int = 64 * 1024,
    val iterations: Int = 3,
    val parallelism: Int = 1
)

class VaultCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Crypto {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /**
     * Passphrase to key.
     *
     * Bouncy Castle rather than a native Argon2: it is already on the classpath,
     * and being pure Java means the vault's tests run on the JVM rather than
     * needing a device. The cost is speed, which buys nothing here — this runs
     * once per device, and every other unlock comes from the Keystore.
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(params.algo == "argon2id") { "unsupported kdf: ${params.algo}" }
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKb)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()
        )
        val key = ByteArray(KEY_BYTES)
        generator.generateBytes(passphrase, key)
        return key
    }

    /**
     * AES-256-GCM through Tink's primitive rather than a hand-assembled JCE
     * call: it generates its own nonce per encryption and prepends it, which is
     * the mistake we would otherwise be one refactor away from making.
     *
     * [associatedData] is the entry's id. It authenticates the binding between a
     * file's name and its contents, so a ciphertext cannot be renamed over a
     * different credential and still open.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        try {
            AesGcmJce(key).encrypt(plaintext, associatedData)
        } catch (e: GeneralSecurityException) {
            throw VaultCryptoException("encrypt failed", e)
        }

    /** Returns null when the key is wrong or the ciphertext has been altered. */
    fun open(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray? =
        try {
            AesGcmJce(key).decrypt(sealed, associatedData)
        } catch (e: GeneralSecurityException) {
            null
        }
}
