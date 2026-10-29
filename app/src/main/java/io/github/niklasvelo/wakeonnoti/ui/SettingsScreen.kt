@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.actions.AutoAiClient
import io.github.niklasvelo.wakeonnoti.data.Config
import io.github.niklasvelo.wakeonnoti.data.ForwardMode
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.engine.NotiEvent
import io.github.niklasvelo.wakeonnoti.system.Health
import io.github.niklasvelo.wakeonnoti.system.KeepAliveService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by Store.config.collectAsState()
    val s = config.settings
    var pendingImport by remember { mutableStateOf<Pair<String, Config>?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(Store.exportJson().toByteArray()) }
        }.fold({ context.toast("Exported ${config.rules.size} rules") }, { context.toast("Export failed: ${it.message}") })
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            text to Config.fromJson(JSONObject(text))
        }.fold({ pendingImport = it }, { context.toast("Not a WakeOnNoti settings file") })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionTitle("Status")
            StatusList()

            SectionTitle("Reliability")
            SwitchRow(
                "Keep-alive service",
                "A silent, permanent notification that stops Android from killing WakeOnNoti in the background. " +
                    "Turn it on if alarms sometimes don't fire.",
                s.keepAlive,
            ) { on ->
                Store.updateSettings { it.copy(keepAlive = on) }
                KeepAliveService.sync(context)
            }
            SwitchRow(
                "Log incoming notifications",
                "Lists every notification in the log (only on this phone) – handy for writing rules.",
                s.logNotifications,
            ) { on -> Store.updateSettings { it.copy(logNotifications = on) } }
            OutlinedButton(onClick = {
                Health.forceRebind(context)
                context.toast("Re-enabled the notification listener")
            }) { Text("Repair notification listener") }

            SectionTitle("AutoAI")
            AutoAiSection()

            SectionTitle("Backup")
            Text(
                "Rules and settings survive in-app updates. Export a copy before reinstalling or moving to a new phone. " +
                    "The file includes webhook URLs and the AutoAI token – keep it private.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exporter.launch("wakeonnoti-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json")
                }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) {
                    Text("Import")
                }
            }

            SectionTitle("Updates")
            UpdatePanel()

            SectionTitle("About")
            Text(
                "WakeOnNoti ${BuildConfig.VERSION_NAME} – open source. Everything stays on this phone unless a rule " +
                    "sends it somewhere (webhook, AutoAI).",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { context.open(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.REPO_URL))) }) {
                Text("Source code on GitHub")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    pendingImport?.let { (text, imported) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import settings?") },
            text = { Text("This replaces your ${config.rules.size} rules and all settings with the ${imported.rules.size} rules from the file.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.importJson(text)
                    KeepAliveService.sync(context)
                    pendingImport = null
                    context.toast("Imported ${imported.rules.size} rules")
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AutoAiSection() {
    val context = LocalContext.current
    val config by Store.config.collectAsState()
    val s = config.settings
    // local copies, so typing is never fighting the store round-trip
    var url by remember { mutableStateOf(s.autoAiUrl) }
    var token by remember { mutableStateOf(s.autoAiToken) }
    var target by remember { mutableStateOf(s.autoAiTarget) }
    var showToken by remember { mutableStateOf(false) }

    Text(
        "AutoAI is an AI advisor app, currently in closed beta – without access, just leave this empty. " +
            "With access, notifications reach AutoAI through its server's relay, like AutoAI's own phone app, " +
            "and rules can also tell it directly with a “Tell AutoAI” action.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        url, { url = it.trim(); Store.updateSettings { st -> st.copy(autoAiUrl = url) } },
        label = { Text("Server URL") }, placeholder = { Text("https://your-autoai-server") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        token, { token = it.trim(); Store.updateSettings { st -> st.copy(autoAiToken = token) } },
        label = { Text("Device token") }, singleLine = true,
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        target, { target = it.trim(); Store.updateSettings { st -> st.copy(autoAiTarget = target.ifBlank { "pc" }) } },
        label = { Text("Deliver to device") }, supportingText = { Text("The AutoAI device that should get them – normally “pc”") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Text("Forward automatically", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    Segmented(listOf("Off", "Rule matches", "Everything"), ForwardMode.entries.indexOf(s.forward)) { i ->
        Store.updateSettings { it.copy(forward = ForwardMode.entries[i]) }
    }
    Text(
        when (s.forward) {
            ForwardMode.OFF -> "Only rules with a “Tell AutoAI” action reach AutoAI."
            ForwardMode.MATCHES -> "Every notification that fires a rule also reaches AutoAI."
            ForwardMode.ALL -> "Every notification reaches AutoAI (ongoing ones like music players excepted)."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (s.forward == ForwardMode.ALL) {
        Text("Never forward these apps", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        AppChips(s.forwardExclude, "None excluded") { pkgs -> Store.updateSettings { it.copy(forwardExclude = pkgs) } }
    }
    TextButton(
        enabled = s.autoAiReady,
        onClick = {
            AutoAiClient.send(
                NotiEvent.sample(context, null, "Test from WakeOnNoti – you can ignore this."), "Test", null, report = true,
            ) { context.toast("AutoAI: $it") }
        },
    ) { Text("Send test notification") }
}
