package io.github.niklasvelo.wakeonnoti

import io.github.niklasvelo.wakeonnoti.data.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android's backup and the transfer to a new phone carry only the rules and settings. Anything
 * more - above all shared_prefs, where the AutoAI relay device id lives - would hand a new phone
 * this phone's identity, and two phones polling as one end each other's polls. The rules are
 * resource files, so this reads them as XML (Gradle runs unit tests in the module directory).
 */
class BackupRulesTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val main = File("src/main")
    private val onlyTheConfig = listOf("include file:${Store.FILE}")

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(main, path)).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** A section's rules, like "include file:config.json". */
    private fun Element.rules() = children().map { "${it.tagName} ${it.getAttribute("domain")}:${it.getAttribute("path")}" }

    @Test
    fun `the manifest names both rule files`() {
        val app = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(android, "fullBackupContent"))
    }

    @Test
    fun `Android 12 and newer back up and transfer only the config`() {
        val sections = xml("res/xml/data_extraction_rules.xml").children()
        // a section left out would fall back to Android's default: everything
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName })
        sections.forEach { assertEquals(it.tagName, onlyTheConfig, it.rules()) }
    }

    @Test
    fun `Android 6 to 11 back up and transfer only the config`() {
        val rules = xml("res/xml/backup_rules.xml")
        assertEquals("full-backup-content", rules.tagName)
        assertEquals(onlyTheConfig, rules.rules())
    }
}
