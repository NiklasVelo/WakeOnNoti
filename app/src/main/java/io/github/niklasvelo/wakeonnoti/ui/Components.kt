@file:OptIn(ExperimentalLayoutApi::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.niklasvelo.wakeonnoti.BuildConfig
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.system.Apps
import io.github.niklasvelo.wakeonnoti.system.Health
import io.github.niklasvelo.wakeonnoti.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

// ------------------------------------------------------------------ status --

private class Check(val ok: Boolean, val title: String, val detail: String, val fix: String, val action: () -> Unit)

@Composable
private fun rememberChecks(): List<Check> {
    val context = LocalContext.current
    val tick by Health.tick.collectAsState()
    val connected by Health.connected.collectAsState()
    // right after the process starts Android may not have bound the listener yet
    var settled by remember { mutableStateOf(processAgeMs() > 5000) }
    LaunchedEffect(Unit) { if (!settled) { delay(5000); settled = true } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Health.tick.value++ }

    return remember(tick, connected, settled) {
        val access = Health.hasAccess(context)
        listOf(
            Check(access, "Notification access",
                "Off – WakeOnNoti sees nothing until you allow it. Toggle greyed out? Open App info → ⋮ → Allow restricted settings, then try again.",
                "Allow") { context.open(Health.accessSettings(context)) },
            Check(!access || connected || !settled, "Listener connection",
                "Access is granted, but Android is not delivering notifications right now.",
                "Repair") { Health.forceRebind(context); context.toast("Re-enabled the listener") },
            Check(Health.canNotify(context), "Notification permission",
                "Off – needed to show the alarm and its Stop button.", "Allow") {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.open(Health.notificationSettings(context))
            },
            Check(Health.ignoresBatteryOptimizations(context), "Battery optimization",
                "On – Android may put WakeOnNoti to sleep and stop the listener. Choose “Allow” / “Unrestricted”.",
                "Fix") { context.open(Health.batterySettings(context)) },
            Check(Health.canFullScreen(context), "Full-screen alarms",
                "Not allowed – the alarm cannot show over the lock screen (it still rings).",
                "Allow") { context.open(Health.fullScreenSettings(context)) },
        )
    }
}

private fun processAgeMs() =
    if (Build.VERSION.SDK_INT >= 24) SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() else Long.MAX_VALUE

fun Context.open(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Health.appDetails(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Home: only what is wrong, with a fix button each. Nothing when all is well. */
@Composable
fun ProblemsCard() {
    val problems = rememberChecks().filter { !it.ok }
    if (problems.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Alarms may not fire", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            problems.forEach { CheckRow(it, onError = true) }
        }
    }
}

/** Settings: every check, ok or not. */
@Composable
fun StatusList() {
    rememberChecks().forEach { CheckRow(it, onError = false) }
    val last = Health.lastNotificationAt
    Text(
        "Last notification received: " +
            if (last == 0L) "never" else DateUtils.getRelativeTimeSpanString(last).toString(),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun CheckRow(c: Check, onError: Boolean) {
    val tint = when {
        c.ok -> MaterialTheme.colorScheme.primary
        onError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (c.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge)
            if (!c.ok) Text(c.detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!c.ok) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = c.action) { Text(c.fix) }
        }
    }
}

// ----------------------------------------------------------------- updates --

@Composable
fun UpdatePanel() {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    Text("Installed: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    when (val s = state) {
        Updater.State.Idle -> OutlinedButton(onClick = { Updater.check() }) { Text("Check for updates") }
        Updater.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Checking…")
        }
        Updater.State.UpToDate -> {
            Text("You have the newest version.")
            TextButton(onClick = { Updater.check() }) { Text("Check again") }
        }
        is Updater.State.Available -> UpdateAvailable(s.release) { Updater.install(context) }
        is Updater.State.Downloading -> {
            Text("Downloading ${s.release.version}… ${s.percent}%")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is Updater.State.Installing -> Text("Confirm the install prompt to finish updating to ${s.release.version}.")
        is Updater.State.Failed -> {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            if (s.release != null) Button(onClick = { Updater.install(context) }) { Text("Retry update") }
            else OutlinedButton(onClick = { Updater.check() }) { Text("Check again") }
        }
    }
}

@Composable
private fun UpdateAvailable(release: Updater.Release, onInstall: () -> Unit) {
    val context = LocalContext.current
    Text("Version ${release.version} is available", style = MaterialTheme.typography.titleMedium)
    if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onInstall) { Text("Install update") }
    Text(
        if (Health.canInstall(context)) "Your rules and settings are kept."
        else "The first time, Android asks you to allow installs from WakeOnNoti – allow it, come back and tap again. Your rules and settings are kept.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Home: a compact banner while an update waits. */
@Composable
fun UpdateBanner(onOpen: () -> Unit) {
    val state by Updater.state.collectAsState()
    val release = (state as? Updater.State.Available)?.release ?: return
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available: ${release.version}", style = MaterialTheme.typography.titleMedium)
                Text("Rules and settings are kept", style = MaterialTheme.typography.bodySmall)
            }
            val context = LocalContext.current
            Button(onClick = { Updater.install(context) }) { Text("Install") }
        }
    }
}

// -------------------------------------------------------------------- apps --

private val iconCache = LruCache<String, ImageBitmap>(200)

@Composable
fun AppIcon(pkg: String, size: Dp) {
    val context = LocalContext.current
    val icon by produceState(iconCache.get(pkg), pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                Apps.icon(context, pkg)?.toBitmap(96, 96)?.asImageBitmap()?.also { iconCache.put(pkg, it) }
            }
        }
    }
    icon?.let { Image(it, null, Modifier.size(size)) } ?: Spacer(Modifier.size(size))
}

/** Chosen apps as removable chips, plus a chip that opens the picker. */
@Composable
fun AppChips(pkgs: List<String>, emptyLabel: String, onChange: (List<String>) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pkgs.isEmpty()) {
            Text(emptyLabel, Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
        }
        pkgs.forEach { pkg ->
            InputChip(
                selected = false,
                onClick = { onChange(pkgs - pkg) },
                label = { Text(Apps.label(context, pkg), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { AppIcon(pkg, 18.dp) },
                trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
            )
        }
        AssistChip(onClick = { picking = true }, label = { Text(if (pkgs.isEmpty()) "Choose apps" else "Change") })
    }
    if (picking) {
        AppPickerDialog(pkgs.toSet(), onDismiss = { picking = false }) {
            picking = false
            onChange(it)
        }
    }
}

@Composable
fun AppPickerDialog(initial: Set<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<Apps.AppInfo>?>(null) {
        // apps that sent notifications show up even when they have no launcher icon
        val seen = EventLog.entries.value.map { it.pkg }.filter { it.isNotBlank() }
        value = withContext(Dispatchers.IO) { Apps.list(context, seen + initial) }
    }
    fun toggle(pkg: String) { selected = if (pkg in selected) selected - pkg else selected + pkg }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose apps") },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search, or type a package name") },
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else {
                    val shown = list.filter {
                        query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)
                    }.sortedByDescending { it.pkg in initial }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (query.contains('.') && list.none { it.pkg == query }) {
                            item {
                                TextButton(onClick = { toggle(query) }) {
                                    Text(if (query in selected) "Remove package $query" else "Add package $query")
                                }
                            }
                        }
                        items(shown, key = { it.pkg }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable { toggle(app.pkg) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.pkg, 32.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(app.pkg in selected, onCheckedChange = { toggle(app.pkg) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(selected.toList()) }) { Text("Done (${selected.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
