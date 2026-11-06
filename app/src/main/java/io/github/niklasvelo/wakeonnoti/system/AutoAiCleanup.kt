package io.github.niklasvelo.wakeonnoti.system

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import io.github.niklasvelo.wakeonnoti.data.AutoAiLeftovers
import io.github.niklasvelo.wakeonnoti.data.Store
import java.security.KeyStore

/**
 * Removes what versions up to 0.2.9 left on this phone outside config.json ([Store] cleans that
 * one as it reads it, see [AutoAiLeftovers]). Runs at the first start after the update, which an
 * update triggers by itself, so nobody has to open the app.
 *
 * Every step can be repeated, and a step that failed is tried again at the next start. Nothing
 * here may keep the alarms from running, so nothing here throws.
 */
object AutoAiCleanup {
    private const val TAG = "AutoAiCleanup"
    private const val KEY_DONE = "leftoversRemoved"

    fun run(c: Context) {
        val prefs = Store.prefs
        if (prefs.getBoolean(KEY_DONE, false)) return
        val done = listOf(
            step("files") { AutoAiLeftovers.deleteFiles(c.filesDir, c.noBackupFilesDir) },
            // also takes away any inbox notification still showing
            step("channel") { c.getSystemService(NotificationManager::class.java).deleteNotificationChannel(AutoAiLeftovers.CHANNEL); true },
            step("key") { deleteKey(); true },
        ).all { it }
        prefs.edit().apply {
            AutoAiLeftovers.PREFS.forEach { remove(it) }
            if (done) putBoolean(KEY_DONE, true)
        }.apply()
    }

    private fun step(what: String, work: () -> Boolean) =
        runCatching(work).onFailure { Log.w(TAG, "$what failed", it) }.getOrDefault(false)

    private fun deleteKey() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(AutoAiLeftovers.KEY_ALIAS)) keys.deleteEntry(AutoAiLeftovers.KEY_ALIAS)
    }
}
