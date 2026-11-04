package io.github.niklasvelo.wakeonnoti.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/**
 * The vault with the phone's own AES-GCM, only that a software key stands in for the Keystore's.
 * What matters most here is what a Keystore that fails must never do: lose the token.
 */
class TokenVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private val token = "autoai-device-token-4711"
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** A Keystore that can stumble, and that counts what it is asked to do. */
    private inner class Sealer : TokenSealer {
        var sealFails = false
        var openFails = false
        var seals = 0

        override fun seal(plain: ByteArray): ByteArray {
            seals++
            if (sealFails) throw ProviderException("keystore down")
            return AesGcm.seal(key, plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            if (openFails) throw ProviderException("keystore down")
            return AesGcm.open(key, sealed)
        }
    }

    private val sealer = Sealer()
    private val file by lazy { File(tmp.root, "autoai_token.enc") }
    private fun vault(s: TokenSealer = sealer) = TokenVault(file, s)

    // ------------------------------------------------------------- AES-GCM --
    @Test
    fun `sealing twice gives two different results, both open`() {
        val plain = token.toByteArray()
        val one = AesGcm.seal(key, plain)
        val two = AesGcm.seal(key, plain)
        assertFalse(one.contentEquals(two))
        assertArrayEquals(plain, AesGcm.open(key, one))
        assertArrayEquals(plain, AesGcm.open(key, two))
    }

    @Test(expected = AEADBadTagException::class)
    fun `a changed byte does not open`() {
        val sealed = AesGcm.seal(key, token.toByteArray())
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        AesGcm.open(key, sealed)
    }

    @Test(expected = AEADBadTagException::class)
    fun `another key does not open it - a restored file on another phone`() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        AesGcm.open(other, AesGcm.seal(key, token.toByteArray()))
    }

    // -------------------------------------------------------------- normal --
    @Test
    fun `no file means no token and no problem`() {
        val v = vault()
        assertEquals("", v.read())
        assertEquals(TokenProblem.NONE, v.problem)
    }

    @Test
    fun `a stored token comes back after a restart, and the file does not show it`() {
        assertTrue(vault().store(token))
        val v = vault()
        assertEquals(token, v.read())
        assertEquals(TokenProblem.NONE, v.problem)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun `non-ASCII survives`() {
        vault().store("töken-✓")
        assertEquals("töken-✓", vault().read())
    }

    @Test
    fun `a new token replaces the old one, and nothing is left beside the file`() {
        val v = vault()
        v.store(token)
        v.store("newer")
        assertEquals("newer", vault().read())
        assertEquals(listOf(file.name), tmp.root.list()!!.toList())
    }

    @Test
    fun `an empty token removes the file`() {
        val v = vault()
        v.store(token)
        assertTrue(v.store(""))
        assertFalse(file.exists())
    }

    @Test
    fun `saving the rules does not seal the unchanged token again`() {
        val v = vault()
        v.store(token)
        v.store(token)
        v.store(token)
        assertEquals(1, sealer.seals)
    }

    @Test
    fun `a token read from the file is not sealed again either`() {
        vault().store(token)
        val v = vault()
        val seals = sealer.seals
        v.read()
        assertTrue(v.store(token))
        assertEquals(seals, sealer.seals)
    }

    // ------------------------------------------- a Keystore that cannot seal --
    @Test
    fun `a refused token reports it, and the old file stays`() {
        val v = vault()
        v.store(token)
        val before = file.readBytes()
        sealer.sealFails = true
        assertFalse(v.store("newer"))
        assertEquals(TokenProblem.NOT_SEALED, v.problem)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `a refused token is tried again on the next save`() {
        val v = vault()
        sealer.sealFails = true
        assertFalse(v.store(token))
        assertFalse(v.store(token))
        sealer.sealFails = false
        assertTrue(v.store(token))
        assertEquals(TokenProblem.NONE, v.problem)
        assertEquals(token, vault().read())
    }

    @Test
    fun `clearing a refused token clears the problem`() {
        val v = vault()
        sealer.sealFails = true
        v.store(token)
        assertTrue(v.store(""))
        assertEquals(TokenProblem.NONE, v.problem)
    }

    @Test
    fun `a seal that does not open again counts as refused`() {
        val broken = object : TokenSealer {
            override fun seal(plain: ByteArray) = AesGcm.seal(key, plain)
            override fun open(sealed: ByteArray) = "something else".toByteArray()
        }
        val v = vault(broken)
        assertFalse(v.store(token))
        assertEquals(TokenProblem.NOT_SEALED, v.problem)
        assertFalse(file.exists())
    }

    @Test
    fun `a failure is passed on for the log, never the token`() {
        val seen = mutableListOf<String>()
        sealer.sealFails = true
        TokenVault(file, sealer) { message, e -> seen += "$message ${e.message}" }.store(token)
        assertEquals(1, seen.size)
        assertFalse(seen.single().contains(token))
    }

    // ------------------------------------------- a Keystore that cannot open --
    @Test
    fun `a file that does not open is reported and kept`() {
        vault().store(token)
        val before = file.readBytes()
        sealer.openFails = true
        val v = vault()
        assertEquals("", v.read())
        assertEquals(TokenProblem.UNREADABLE, v.problem)
        assertArrayEquals(before, file.readBytes())
    }

    /** The empty token in memory after a failed read is not the user clearing it. */
    @Test
    fun `saving the rules after a failed read does not delete the token`() {
        vault().store(token)
        sealer.openFails = true
        val v = vault()
        v.read()
        assertTrue(v.store(""))
        assertTrue(file.exists())
        assertEquals(TokenProblem.UNREADABLE, v.problem)
        sealer.openFails = false
        assertEquals(token, v.read())
        assertEquals(TokenProblem.NONE, v.problem)
    }

    @Test
    fun `a token entered after a failed read replaces the dead file`() {
        file.writeBytes(ByteArray(40) { 7 })
        val v = vault()
        v.read()
        assertEquals(TokenProblem.UNREADABLE, v.problem)
        assertTrue(v.store(token))
        assertEquals(TokenProblem.NONE, v.problem)
        assertEquals(token, vault().read())
    }

    @Test
    fun `an empty or cut file is unreadable, not a crash`() {
        file.writeBytes(ByteArray(0))
        assertEquals("", vault().read())
        file.writeBytes(ByteArray(5))
        val v = vault()
        assertEquals("", v.read())
        assertEquals(TokenProblem.UNREADABLE, v.problem)
    }
}
