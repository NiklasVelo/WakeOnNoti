package io.github.niklasvelo.wakeonnoti.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.niklasvelo.wakeonnoti.actions.AutoAiInbox
import io.github.niklasvelo.wakeonnoti.data.Rule
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.system.Apps
import io.github.niklasvelo.wakeonnoti.system.Health
import io.github.niklasvelo.wakeonnoti.system.ListenerDoctor
import io.github.niklasvelo.wakeonnoti.update.Updater

class MainActivity : ComponentActivity() {
    private val showUpdate = mutableStateOf(false)
    private val showInbox = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showUpdate.value = intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)
        showInbox.value = intent.getBooleanExtra(EXTRA_SHOW_INBOX, false)
        setContent { AppTheme { AppRoot(showUpdate, showInbox) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) showUpdate.value = true
        if (intent.getBooleanExtra(EXTRA_SHOW_INBOX, false)) showInbox.value = true
    }

    override fun onResume() {
        super.onResume()
        Health.tick.value++
        // the user is looking: if the listener is gone, repair now rather than after the backoff
        ListenerDoctor.checkSoon(userPresent = true)
        Updater.check(quiet = true)
        Store.retryToken()
        AutoAiInbox.sync()
        AutoAiInbox.refresh()  // the whole open list, in case something got lost
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "showUpdate"
        const val EXTRA_SHOW_INBOX = "showInbox"
    }
}

sealed interface Screen {
    data object Home : Screen
    data class Edit(val rule: Rule, val isNew: Boolean) : Screen
    data object Settings : Screen
    data object Log : Screen
    data object Inbox : Screen
    data object Board : Screen
    data class BoardTask(val id: Int) : Screen
    /** [id] null: a new ticket. */
    data class BoardEdit(val id: Int?) : Screen
}

@Composable
private fun AppRoot(showUpdate: MutableState<Boolean>, showInbox: MutableState<Boolean>) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { stack = stack.dropLast(1) }

    LaunchedEffect(showUpdate.value) {
        if (showUpdate.value) {
            if (stack.last() != Screen.Settings) push(Screen.Settings)
            showUpdate.value = false
        }
    }
    LaunchedEffect(showInbox.value) {
        if (showInbox.value) {
            if (stack.last() != Screen.Inbox) push(Screen.Inbox)
            showInbox.value = false
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(
            onEdit = { push(Screen.Edit(it, isNew = false)) },
            onNew = { push(Screen.Edit(it, isNew = true)) },
            onSettings = { push(Screen.Settings) },
            onLog = { push(Screen.Log) },
            onInbox = { push(Screen.Inbox) },
            onBoard = { push(Screen.Board) },
        )
        Screen.Inbox -> InboxScreen(onBack = { pop() })
        Screen.Board -> BoardScreen(
            onBack = { pop() },
            onOpen = { push(Screen.BoardTask(it)) },
            onNew = { push(Screen.BoardEdit(null)) },
        )
        is Screen.BoardTask -> BoardTaskScreen(s.id, onBack = { pop() }, onEdit = { push(Screen.BoardEdit(s.id)) })
        is Screen.BoardEdit -> BoardEditScreen(s.id, onClose = { pop() })
        is Screen.Edit -> RuleEditor(s.rule, s.isNew, onClose = { pop() })
        Screen.Settings -> SettingsScreen(onBack = { pop() })
        Screen.Log -> LogScreen(
            onBack = { pop() },
            onCreateRule = { pkg -> push(Screen.Edit(Rule(name = Apps.label(context, pkg), apps = listOf(pkg)), isNew = true)) },
        )
    }
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFFFC94D), onPrimary = Color(0xFF3F2E00))
        else -> lightColorScheme(primary = Color(0xFF7A5900))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Safe from any thread (webhook and AutoAI results arrive on workers). */
fun Context.toast(message: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
