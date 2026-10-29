package io.github.niklasvelo.wakeonnoti.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
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
 */
object Store {
    private const val TAG = "Store"
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
        file = AtomicFile(File(dir, "config.json"))
        prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        state.value = load(dir)
    }

    private fun load(dir: File): Config = try {
        Config.fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (_: FileNotFoundException) {
        Config()
    } catch (e: Exception) {
        // never let a bad file be silently replaced by an empty config on the next save
        Log.e(TAG, "config.json unreadable, keeping a copy", e)
        runCatching { file.baseFile.copyTo(File(dir, "config.broken.json"), overwrite = true) }
        Config()
    }

    fun update(change: (Config) -> Config) {
        val json = state.updateAndGet(change).toJson().toString(2)
        io.execute { write(json) }
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
        val imported = Config.fromJson(JSONObject(text))
        update { imported }
        return imported
    }

    private fun write(json: String) {
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.e(TAG, "cannot open config for writing", e)
            return
        }
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "config write failed", e)
        }
    }
}
