package io.github.niklasvelo.wakeonnoti.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/**
 * Rules and settings, in filesDir/config.json. Everything (UI, listener, alarm)
 * runs in one process, so this singleton is the only copy. Writes go through a
 * single thread in call order, so the file always ends at the newest state.
 *
 * The file lives in the app's private storage, which an in-place update keeps:
 * that is why the self-updater never has to migrate anything.
 *
 * [prefs] is this phone's own state (the doctor's memory, the update check). Android's
 * backup carries only [FILE] to a new phone, never [prefs]
 * (res/xml/data_extraction_rules.xml, BackupRulesTest).
 */
object Store {
    private const val TAG = "Store"
    /** The rules and settings - the one file Android's backup carries to a new phone. */
    const val FILE = "config.json"
    /** A copy of a [FILE] that could not be read. */
    const val BROKEN = "config.broken.json"
    private lateinit var file: AtomicFile
    lateinit var prefs: SharedPreferences
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(Config())

    val config: StateFlow<Config> = state
    val rules get() = state.value.rules
    val settings get() = state.value.settings

    fun init(context: Context) {
        val dir = context.filesDir
        file = AtomicFile(File(dir, FILE))
        prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        state.value = load(dir)
    }

    private fun load(dir: File): Config = try {
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        Config.fromJson(json).also { dropAutoAi(json, it) }
    } catch (_: FileNotFoundException) {
        Config()
    } catch (e: Exception) {
        // never let a bad file be silently replaced by an empty config on the next save
        Log.e(TAG, "config.json unreadable, keeping a copy", e)
        runCatching { file.baseFile.copyTo(File(dir, BROKEN), overwrite = true) }
        Config()
    }

    /**
     * A file from 0.2.9 or older. [clean] has none of what [AutoAiLeftovers] lists, but the file
     * keeps it until the next save, which may be weeks away, so this one happens now. That is
     * also what makes it run once: the next start finds nothing, and one that failed is simply
     * repeated. An old Android backup restored later is a file like this too.
     *
     * Only ever after a successful read: a config that did not load must not be saved over the
     * rules. Not through [io], nothing else is writing yet and the result is needed here.
     */
    private fun dropAutoAi(json: JSONObject, clean: Config) {
        if (AutoAiLeftovers.inConfig(json) && write(clean.toJson().toString(2))) {
            AutoAiLeftovers.notice(json)?.let { EventLog.error(it) }
        }
    }

    fun update(change: (Config) -> Config) {
        state.update(change)
        io.execute { write(state.value.toJson().toString(2)) }  // always the newest state
    }

    fun updateSettings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    fun saveRule(rule: Rule) = update { c ->
        val i = c.rules.indexOfFirst { it.id == rule.id }
        c.copy(rules = if (i < 0) c.rules + rule else c.rules.toMutableList().also { it[i] = rule })
    }

    fun deleteRule(id: String) = update { c -> c.copy(rules = c.rules.filterNot { it.id == id }) }

    fun exportJson(): String = state.value.toJson().toString(2)

    /** Throws on anything that is not a settings export, before touching the current config. */
    fun importJson(text: String): Config {
        val json = JSONObject(text)
        val imported = Config.fromJson(json)
        update { imported }
        AutoAiLeftovers.notice(json)?.let { EventLog.error(it) }
        return imported
    }

    private fun write(json: String): Boolean {
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.e(TAG, "cannot open config for writing", e)
            return false
        }
        return try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            true
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "config write failed", e)
            false
        }
    }
}
