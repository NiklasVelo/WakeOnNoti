package io.github.niklasvelo.wakeonnoti.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Why the AutoAI token is not simply safe in the vault. */
enum class TokenProblem {
    NONE,
    /** The file is there and does not open: the Keystore stumbled, or lost the key. The token is missing for now. */
    UNREADABLE,
    /** The Keystore refused the token. It works, but stays unencrypted in config.json, as before the vault. */
    NOT_SEALED,
}

/** Encrypts the token. On the phone that is [KeystoreSealer]; without Android, tests bring their own. */
interface TokenSealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM, sealed as 12 bytes of IV and then the ciphertext with its tag. */
object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)  // the IV comes from the cipher: the Keystore insists on that
        return cipher.iv + cipher.doFinal(plain)
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}

/**
 * The AutoAI token at rest: [file] holds it sealed, and an empty token is no file at all.
 *
 * A failing Keystore must never cost the token, so nothing here deletes what it cannot read,
 * and [store] reports the truth: on false the vault does not hold the token, and the caller
 * has to keep it somewhere (Store leaves it in config.json).
 */
class TokenVault(
    private val file: File,
    private val sealer: TokenSealer,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) {
    /** What [file] holds, as far as this process knows. After a failed [read] that is "": the
     *  token in memory is empty then too, and saving it must not delete a file that may yet open. */
    private var held = ""

    @Volatile var problem = TokenProblem.NONE
        private set

    /** The token, or "" if there is none or it does not open: [problem] tells which. */
    @Synchronized
    fun read(): String {
        held = ""
        problem = TokenProblem.NONE
        if (!file.exists()) return ""
        try {
            held = String(sealer.open(file.readBytes()), Charsets.UTF_8)
        } catch (e: Exception) {
            log("token does not open", e)
            problem = TokenProblem.UNREADABLE
        }
        return held
    }

    /** True if the vault now holds exactly [token]. Unchanged, it costs nothing. */
    @Synchronized
    fun store(token: String): Boolean {
        if (token == held) {
            if (problem == TokenProblem.NOT_SEALED) problem = TokenProblem.NONE
            return true
        }
        return try {
            if (token.isEmpty()) remove() else replace(sealedAndChecked(token))
            held = token
            problem = TokenProblem.NONE
            true
        } catch (e: Exception) {
            log("token not sealed", e)
            problem = TokenProblem.NOT_SEALED
            false
        }
    }

    /** Opened again before anyone relies on it: the plain copy goes once this has passed. */
    private fun sealedAndChecked(token: String): ByteArray {
        val plain = token.toByteArray(Charsets.UTF_8)
        val sealed = sealer.seal(plain)
        if (!sealer.open(sealed).contentEquals(plain)) throw IOException("sealed token does not open again")
        return sealed
    }

    private fun remove() {
        if (!file.delete() && file.exists()) throw IOException("cannot delete $file")
    }

    /** Through a second file, so that a crash leaves the old token or the new one, never half of it. */
    private fun replace(sealed: ByteArray) {
        val next = File(file.path + ".new")
        FileOutputStream(next).use { it.write(sealed); it.fd.sync() }
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
