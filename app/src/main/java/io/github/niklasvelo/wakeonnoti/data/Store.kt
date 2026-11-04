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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Rules and settings, in filesDir/config.json. Everything (UI, listener, alarm)
 * runs in one process, so this singleton is the only copy. Writes go through a
 * single thread in call order, so the file always ends at the newest state.
 *
 * The file lives in the app's private storage, which an in-place update keeps:
 * that is why the self-updater never has to migrate anything.
 *
 * [prefs] is this phone's own state (the AutoAI relay device id, what was already
 * announced, the doctor's memory). Android's backup carries only [FILE] to a new
 * phone, never [prefs] (res/xml/data_extraction_rules.xml, BackupRulesTest).
 *
 * The AutoAI token is in [config] like any setting, but only there: at rest it is in
 * [vault], encrypted with a key that never leaves the Keystore, so it is in neither [FILE]
 * nor an export. A token found in [FILE] is moved there at the start. That covers updating
 * from a version before the vault, and restoring one of its backups.
 */
object Store {
    private const val TAG = "Store"
    /** The rules and settings - the one file Android's backup carries to a new phone. */
    const val FILE = "config.json"
    /** In noBackupFilesDir: the key stays on this phone, so a copy would be of no use anywhere else. */
    private const val TOKEN_FILE = "autoai_token.enc"
    private lateinit var file: AtomicFile
    private lateinit var vault: TokenVault
    lateinit var prefs: SharedPreferences
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(Config())
    private val problem = MutableStateFlow(TokenProblem.NONE)

    val config: StateFlow<Config> = state
    val tokenProblem: StateFlow<TokenProblem> = problem
    val rules get() = state.value.rules
    val settings get() = state.value.settings

    fun init(context: Context) {
        val dir = context.filesDir
        file = AtomicFile(File(dir, FILE))
        prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        vault = TokenVault(File(context.noBackupFilesDir, TOKEN_FILE), KeystoreSealer) { what, e -> Log.e(TAG, what, e) }
        val loaded = load(dir)
        // a token in the file is never older than the vault's: as soon as the vault has it, it leaves the file
        val unsealed = loaded.settings.autoAiToken
        state.value = loaded.withToken(unsealed.ifBlank { vault.read() })
        noteTokenProblem()
        if (unsealed.isNotBlank()) persist()
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
        state.update(change)
        persist()
    }

    /** Always the newest state, see [retryToken]. The vault first: a plain token leaves the
     *  file only once the vault really has it. */
    private fun persist() = io.execute {
        val config = state.value
        val sealed = vault.store(config.settings.autoAiToken)
        noteTokenProblem()
        write(config.toFile(sealed).toString(2))
    }

    /** The process can live for weeks, and a Keystore that stumbled at its start must not keep
     *  AutoAI away for all of them. Cheap: without that problem it does nothing.
     *  True if the token is back, so that whoever needs it can start up.
     *
     *  On the writer thread, like [persist]: a save running after the vault gave the token
     *  back, but before it reached the state, would take the empty token for the user
     *  clearing it and delete the file. */
    fun retryToken(): Boolean {
        if (problem.value != TokenProblem.UNREADABLE) return false
        return io.submit(Callable {
            val token = vault.read()
            noteTokenProblem()
            if (token.isNotEmpty()) state.update { if (it.settings.autoAiToken.isBlank()) it.withToken(token) else it }
            token.isNotEmpty()
        }).get()
    }

    private fun noteTokenProblem() {
        val now = vault.problem
        if (problem.value == now) return
        problem.value = now
        when (now) {
            TokenProblem.NONE -> {}
            TokenProblem.UNREADABLE -> EventLog.error(
                "AutoAI token cannot be read",
                "AutoAI is off until it can be: WakeOnNoti keeps trying. If this stays, enter the token again in Settings.",
            )
            TokenProblem.NOT_SEALED -> EventLog.error(
                "AutoAI token is not encrypted",
                "Android's key store refused it, so it stays unencrypted in the app's private storage. WakeOnNoti tries again.",
            )
        }
    }

    fun updateSettings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    fun saveRule(rule: Rule) = update { c ->
        val i = c.rules.indexOfFirst { it.id == rule.id }
        c.copy(rules = if (i < 0) c.rules + rule else c.rules.toMutableList().also { it[i] = rule })
    }

    fun deleteRule(id: String) = update { c -> c.copy(rules = c.rules.filterNot { it.id == id }) }

    /** Without the AutoAI token ([Config.toJson]). */
    fun exportJson(): String = state.value.toJson().toString(2)

    /** Throws on anything that is not a settings export, before touching the current config. */
    fun importJson(text: String): Config {
        val imported = Config.fromJson(JSONObject(text))
        update { imported.withTokenOf(it) }
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
