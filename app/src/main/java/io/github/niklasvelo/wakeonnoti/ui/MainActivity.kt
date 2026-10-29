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
import io.github.niklasvelo.wakeonnoti.data.Rule
import io.github.niklasvelo.wakeonnoti.system.Apps
import io.github.niklasvelo.wakeonnoti.system.Health
import io.github.niklasvelo.wakeonnoti.update.Updater

class MainActivity : ComponentActivity() {
    private val showUpdate = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showUpdate.value = intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)
        setContent { AppTheme { AppRoot(showUpdate) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) showUpdate.value = true
    }

    override fun onResume() {
        super.onResume()
        Health.tick.value++
        if (Health.hasAccess(this) && !Health.connected.value) Health.requestRebind(this)
        Updater.check(quiet = true)
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "showUpdate"
    }
}

sealed interface Screen {
    data object Home : Screen
    data class Edit(val rule: Rule, val isNew: Boolean) : Screen
    data object Settings : Screen
    data object Log : Screen
}

@Composable
private fun AppRoot(showUpdate: MutableState<Boolean>) {
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
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(
            onEdit = { push(Screen.Edit(it, isNew = false)) },
            onNew = { push(Screen.Edit(it, isNew = true)) },
            onSettings = { push(Screen.Settings) },
            onLog = { push(Screen.Log) },
        )
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
