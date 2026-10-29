package io.github.niklasvelo.wakeonnoti.system

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

object Apps {
    data class AppInfo(val pkg: String, val label: String)

    private val labels = ConcurrentHashMap<String, String>()

    fun label(c: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun icon(c: Context, pkg: String): Drawable? = runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Launchable apps, plus any extra packages (e.g. senders seen in the log), sorted by name. */
    fun list(c: Context, extra: Collection<String> = emptyList()): List<AppInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = c.packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet() + extra
        return (pkgs - c.packageName).map { AppInfo(it, label(c, it)) }.sortedBy { it.label.lowercase() }
    }
}
