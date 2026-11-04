package io.github.niklasvelo.wakeonnoti.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Seals the AutoAI token with an AES key in the Android Keystore. The key cannot be read out
 * and stays on this phone: no backup, export or copy of the app's files can open the token.
 *
 * On purpose no setUserAuthenticationRequired or setUnlockedDeviceRequired. The token is read
 * when the process starts, and that is a locked phone at night as often as not, which is
 * exactly when AutoAI's urgent items have to ring. For the same reason no StrongBox: it is
 * optional hardware, and this needs one key that works everywhere.
 */
object KeystoreSealer : TokenSealer {
    private const val TAG = "KeystoreSealer"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "autoai_token"

    override fun seal(plain: ByteArray): ByteArray = try {
        AesGcm.seal(key(), plain)
    } catch (e: Exception) {
        // Keystore keys are known to go bad, after an OS update for instance. Sealing replaces
        // whatever that key sealed, so nothing is lost by starting over with a new one.
        Log.w(TAG, "sealing failed, trying a new key", e)
        keyStore().deleteEntry(ALIAS)
        AesGcm.seal(key(), plain)
    }

    override fun open(sealed: ByteArray): ByteArray = AesGcm.open(key(), sealed)

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey = keyStore().getKey(ALIAS, null) as SecretKey? ?: generate()

    private fun generate(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }
}
