package io.github.niklasvelo.wakeonnoti.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.actions.Http
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.system.Health
import io.github.niklasvelo.wakeonnoti.system.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import android.util.Log
import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Same flow as UmAi's hub updater: fetch a manifest {version, file, url, sha256,
 * size, notes}, compare versions as integer tuples, download, verify the sha256,
 * then hand the APK to PackageInstaller. The update is in place (same package,
 * same signing key), so rules and settings in filesDir survive untouched.
 *
 * The manifest is release/version.json in the GitHub repo, written by
 * scripts/publish.ps1 next to the APK it describes.
 */
object Updater {
    /** [urls] are mirrors of the same APK, tried in order. */
    data class Release(val version: String, val urls: List<String>, val sha256: String, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private const val TAG = "Updater"
    private const val KEY_CHECKED = "updateCheckedAt"
    private const val KEY_NOTIFIED = "updateNotified"
    private const val BACKGROUND_EVERY_MS = 6 * 3600_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    val currentVersion get() = BuildConfig.VERSION_NAME.substringBefore('-')

    fun isNewer(candidate: String, current: String = currentVersion): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int>? =
        v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

    /**
     * Blocking. The CDN in front of raw.githubusercontent.com caches for up to 5 minutes and
     * ignores query strings (tested: every `?t=` variant was a cache HIT), so [fresh] does not
     * actually bypass it - a new release can take that long to show up. Kept as a harmless hint.
     */
    private fun fetch(fresh: Boolean): Release {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL + if (fresh) "?t=${System.currentTimeMillis()}" else ""
        val r = Http.request(manifestUrl)
        if (r.code != 200) throw IOException("update server answered HTTP ${r.code}")
        val o = JSONObject(r.body)
        // "urls" lists mirrors, raw.githubusercontent.com first: github.com and its release
        // downloads have no IPv6 address, so on IPv6-only mobile data they do not even resolve
        val urls = LinkedHashSet<String>()
        o.optJSONArray("urls")?.let { arr -> (0 until arr.length()).forEach { urls += arr.optString(it) } }
        urls += o.optString("url")
        urls.remove("")
        if (urls.isEmpty()) urls += URL(URL(BuildConfig.UPDATE_MANIFEST_URL), o.optString("file")).toString()
        return Release(
            version = o.getString("version"),
            urls = urls.toList(),
            sha256 = o.optString("sha256").lowercase(),
            size = o.optLong("size"),
            notes = o.optString("notes"),
        )
    }

    /** From the UI. [quiet]: an automatic check on app start, which shows nothing unless there is an update. */
    fun check(quiet: Boolean = false) {
        val s = mutableState.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return
        if (quiet && s !is State.Idle) return
        worker.execute {
            if (!quiet) mutableState.value = State.Checking
            mutableState.value = try {
                val r = fetch(fresh = !quiet)
                Store.prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                if (isNewer(r.version)) State.Available(r) else if (quiet) State.Idle else State.UpToDate
            } catch (e: Exception) {
                if (quiet) State.Idle else State.Failed("Could not check for updates: ${e.message ?: e}", null)
            }
        }
    }

    /** From the watchdog job, at most every 6 h; notifies once per new version. Blocking. */
    fun backgroundCheck(c: Context) {
        if (BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        if (now - Store.prefs.getLong(KEY_CHECKED, 0) < BACKGROUND_EVERY_MS) return
        val r = runCatching { fetch(fresh = false) }.getOrNull() ?: return
        Store.prefs.edit().putLong(KEY_CHECKED, now).apply()
        if (!isNewer(r.version)) return
        val s = mutableState.value
        if (s is State.Idle || s is State.UpToDate) mutableState.value = State.Available(r)
        if (Store.prefs.getString(KEY_NOTIFIED, "") != r.version) {
            Notifications.updateAvailable(c, r.version)
            Store.prefs.edit().putString(KEY_NOTIFIED, r.version).apply()
        }
    }

    fun install(c: Context) {
        val known = when (val s = mutableState.value) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> null
        } ?: return
        val app = c.applicationContext
        if (!Health.canInstall(app)) {
            // one-time: "Install unknown apps" for WakeOnNoti; the button works once that is on
            c.startActivity(Health.installSettings(app))
            return
        }
        worker.execute {
            mutableState.value = State.Downloading(known, 0)
            // re-read the manifest: a retry must not reuse URLs that already failed, and the
            // channel may have been fixed or moved on to a newer version since the check
            val release = runCatching { fetch(fresh = true) }.getOrNull()?.takeIf { isNewer(it.version) } ?: known
            try {
                val apk = download(app, release)
                mutableState.value = State.Installing(release)
                Installer.install(app, apk)
            } catch (e: Exception) {
                fail("Update failed: ${e.message ?: e}", release)
            }
        }
    }

    internal fun fail(message: String, release: Release? = (mutableState.value as? State.Installing)?.release) {
        mutableState.value = State.Failed(message, release)
        EventLog.error(message)
    }

    /** Tries every mirror; only when all fail does the last error reach the user. */
    private fun download(c: Context, r: Release): File {
        var last: Exception? = null
        for (url in r.urls) {
            try {
                return downloadFrom(c, r, url)
            } catch (e: Exception) {
                Log.w(TAG, "download from $url failed", e)
                last = e
            }
        }
        throw if (last is UnknownHostException) {
            IOException("cannot reach the download server – check the internet connection and try again")
        } else {
            last ?: IOException("no download address in the update manifest")
        }
    }

    private fun downloadFrom(c: Context, r: Release, url: String): File {
        val dir = File(c.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val part = File(dir, "WakeOnNoti-${r.version}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        mutableState.value = State.Downloading(r, 0)
        Http.download(url, part) { buf, n, done, total ->
            digest.update(buf, 0, n)
            val size = if (total > 0) total else r.size
            if (size > 0) mutableState.value = State.Downloading(r, (done * 100 / size).toInt().coerceIn(0, 99))
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256.isNotEmpty() && sha != r.sha256) {
            part.delete()
            throw IOException("download corrupted (sha256 mismatch) – try again in a few minutes")
        }
        return File(dir, "WakeOnNoti-${r.version}.apk").also { part.renameTo(it) }
    }
}

/** An app may replace itself only with an APK signed by the same key - which is what keeps the data. */
object Installer {
    fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,  // the system fills in the status
            )
            session.commit(status.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // the system "Do you want to update this app?" prompt
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the installer is about to restart us
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Updater.fail(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled" else "Install failed: $msg")
            }
        }
    }
}
