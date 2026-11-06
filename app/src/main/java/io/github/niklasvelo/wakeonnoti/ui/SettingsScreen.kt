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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.data.Config
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.system.Health
import io.github.niklasvelo.wakeonnoti.system.ListenerDoctor
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
                ListenerDoctor.repairNow()
                context.toast("Checking the notification listener…")
            }) { Text("Repair notification listener") }

            SectionTitle("Backup")
            Text(
                "Rules and settings survive in-app updates. Export a copy before reinstalling or moving to a new phone. " +
                    "The file includes webhook URLs and headers – keep it private.",
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
                "WakeOnNoti ${BuildConfig.VERSION_NAME} – open source. Everything stays on this phone unless a rule's " +
                    "webhook sends it somewhere.",
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
