package io.github.niklasvelo.wakeonnoti.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the AutoAI token may turn up in the JSON: in neither config.json nor an export. A file
 * from a version before the vault still holds it, and that must keep working.
 */
class ConfigTokenTest {
    private val token = "autoai-device-token-4711"
    private val url = "https://relay.example"
    private val config = Config(
        settings = Settings(
            autoAiUrl = url, autoAiToken = token, autoAiTarget = "laptop",
            forward = ForwardMode.MATCHES, forwardExclude = listOf("com.example"), autoAiReceive = true,
        ),
    )

    /** config.json or an export as the versions before the vault wrote them. */
    private fun oldFile(url: String = this.url) = JSONObject(
        """{"format": 1, "rules": [], "settings": {"autoAiUrl": "$url", "autoAiToken": "$token", "autoAiReceive": true}}""",
    )

    // -------------------------------------------------------------- export --
    @Test
    fun `the export does not hold the token, in any field`() {
        val text = config.toJson().toString(2)
        assertFalse(text.contains(token))
        assertFalse(text.contains("autoAiToken"))
    }

    @Test
    fun `the export holds everything else`() {
        val back = Config.fromJson(JSONObject(config.toJson().toString()))
        assertEquals(config.settings.copy(autoAiToken = ""), back.settings)
    }

    // --------------------------------------------------------- config.json --
    @Test
    fun `config json does not hold a sealed token`() {
        val text = config.toFile(tokenSealed = true).toString(2)
        assertFalse(text.contains(token))
        assertFalse(text.contains("autoAiToken"))
    }

    @Test
    fun `a token the Keystore refused stays in config json rather than being lost`() {
        val back = Config.fromJson(JSONObject(config.toFile(tokenSealed = false).toString()))
        assertEquals(config, back)
    }

    @Test
    fun `an old config json is read with its token, ready to move into the vault`() {
        val read = Config.fromJson(oldFile())
        assertEquals(token, read.settings.autoAiToken)
        assertTrue(read.settings.autoAiReady)
    }

    @Test
    fun `withToken only changes the token`() {
        val back = config.withToken("other")
        assertEquals("other", back.settings.autoAiToken)
        assertEquals(config, back.withToken(token))
    }

    /** The order Store keeps: the vault first, and only what it holds leaves the file. */
    @Test
    fun `the old file becomes one without a token, and the token still works`() {
        val tmp = java.io.File.createTempFile("token", ".enc").apply { delete() }
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealer = object : TokenSealer {
            override fun seal(plain: ByteArray) = AesGcm.seal(key, plain)
            override fun open(sealed: ByteArray) = AesGcm.open(key, sealed)
        }
        try {
            val read = Config.fromJson(oldFile())
            val sealed = TokenVault(tmp, sealer).store(read.settings.autoAiToken)
            val newFile = read.toFile(sealed).toString()
            assertFalse(newFile.contains(token))

            // the next start: the file has none, the vault gives it back
            val again = Config.fromJson(JSONObject(newFile))
            assertEquals("", again.settings.autoAiToken)
            val running = again.withToken(TokenVault(tmp, sealer).read())
            assertEquals(read, running)
        } finally {
            tmp.delete()
        }
    }

    // -------------------------------------------------------------- import --
    @Test
    fun `importing a file without a token keeps the token of this phone`() {
        val file = Config.fromJson(config.toJson())
        assertEquals(token, file.withTokenOf(config).settings.autoAiToken)
    }

    @Test
    fun `the server counts as the same with a trailing slash or spaces`() {
        val file = Config.fromJson(config.toJson()).let { it.copy(settings = it.settings.copy(autoAiUrl = " $url/ ")) }
        assertEquals(token, file.withTokenOf(config).settings.autoAiToken)
    }

    @Test
    fun `an old export brings its own token along`() {
        val phone = config.withToken("the one on this phone")
        assertEquals(token, Config.fromJson(oldFile()).withTokenOf(phone).settings.autoAiToken)
    }

    /** A file from someone else could otherwise collect this phone's token on its own server. */
    @Test
    fun `a file naming another server does not get the token of this phone`() {
        val file = Config.fromJson(config.toJson()).let {
            it.copy(settings = it.settings.copy(autoAiUrl = "https://somewhere.else"))
        }
        val imported = file.withTokenOf(config)
        assertEquals("", imported.settings.autoAiToken)
        assertEquals("https://somewhere.else", imported.settings.autoAiUrl)
        assertFalse(imported.settings.autoAiReady)
    }

    @Test
    fun `a file without a server does not get the token either`() {
        assertEquals("", Config.fromJson(JSONObject("""{"rules": []}""")).withTokenOf(config).settings.autoAiToken)
    }

    @Test
    fun `nothing else of the file changes on import`() {
        val file = Config.fromJson(config.toJson())
        assertEquals(file.settings, file.withTokenOf(config).settings.copy(autoAiToken = ""))
    }
}
