@file:OptIn(ExperimentalLayoutApi::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
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
import androidx.compose.material.icons.filled.Info
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
import io.github.niklasvelo.wakeonnoti.system.ListenerDoctor
import io.github.niklasvelo.wakeonnoti.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

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
    val doctor by ListenerDoctor.status.collectAsState()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Health.tick.value++ }

    return remember(tick, doctor.state, doctor.repairing, doctor.lastRepair) {
        val access = Health.hasAccess(context)
        listOf(
            Check(access, "Notification access",
                "Off – WakeOnNoti sees nothing until you allow it. Toggle greyed out? Open App info → ⋮ → Allow restricted settings, then try again.",
                "Allow") { context.open(Health.accessSettings(context)) },
            // CHECKING is not a problem: Android binds the listener a moment after the process starts
            Check(doctor.state != ListenerDoctor.State.DISCONNECTED, "Listener connection",
                listenerProblem(context, doctor),
                if (doctor.repairing) "Repairing…" else "Repair") { ListenerDoctor.repairNow() },
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
            Check(!Health.backgroundRestricted(context), "Background activity",
                "Restricted – Android stops WakeOnNoti in the background. App info → Battery → “Unrestricted”.",
                "Fix") { context.open(Health.appDetails(context)) },
            Check(Health.hibernationExempt(context), "Pause app activity if unused",
                "On – WakeOnNoti works in the background and is rarely opened, so Android may pause it, " +
                    "listener included. Turn it off: App info → “Pause app activity if unused”.",
                "Fix") { context.open(Health.hibernationSettings(context)) },
        )
    }
}

private fun time(c: Context, at: Long) = DateFormat.getTimeFormat(c).format(Date(at))

private fun listenerProblem(c: Context, s: ListenerDoctor.Status) = buildString {
    append("Android is not delivering notifications to WakeOnNoti (since ${time(c, s.since)}).")
    s.lastRepair?.let { append(" Last repair: $it.") }
    append(" If Repair does not bring it back, turn notification access for WakeOnNoti off and on again")
    append(if (Health.isSamsung) ", and add it to Samsung's Never sleeping apps (below)." else ".")
}

/** One line on what the listener is doing - the answer to "is it actually listening?". */
@Composable
private fun ListenerStatusText(s: ListenerDoctor.Status, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val last = Health.lastNotificationAt
    val lastText = if (last == 0L) "never" else DateUtils.getRelativeTimeSpanString(last).toString()
    val text = when (s.state) {
        ListenerDoctor.State.CONNECTED -> "Listening since ${time(c, s.since)} · last notification $lastText"
        ListenerDoctor.State.CHECKING -> "Checking the notification listener…"
        ListenerDoctor.State.DISCONNECTED ->
            "Not receiving notifications since ${time(c, s.since)}" + if (s.repairing) " – repairing…" else ""
        ListenerDoctor.State.NO_ACCESS -> "Notification access is off"
    } + if (s.checkedAt > 0) " · checked ${time(c, s.checkedAt)}" else ""
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier)
}

/**
 * One UI puts apps to sleep that were not opened for a few days ("Put unused apps to
 * sleep"), and a sleeping app's listener is unbound. Nothing tells an app whether it is
 * on the Never-sleeping list, so this is advice, not a check.
 */
@Composable
private fun SamsungTip(onError: Boolean) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Info, null, modifier = Modifier.size(20.dp),
            tint = if (onError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Samsung: keep WakeOnNoti awake", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Settings → Battery → Background usage limits → Never sleeping apps → add WakeOnNoti. " +
                    "Otherwise One UI puts it to sleep after a few days without being opened, and the listener stops.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { context.open(Health.samsungBatterySettings(context)) }) { Text("Open") }
    }
}
fun Context.open(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Health.appDetails(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Home: what is wrong, with a fix button each - or one quiet line saying it is listening. */
@Composable
fun ProblemsCard() {
    val doctor by ListenerDoctor.status.collectAsState()
    val problems = rememberChecks().filter { !it.ok }
    if (problems.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            ListenerStatusText(doctor)
        }
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Alarms may not fire", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            problems.forEach { CheckRow(it, onError = true) }
            if (Health.isSamsung && doctor.state == ListenerDoctor.State.DISCONNECTED) SamsungTip(onError = true)
        }
    }
}

/** Settings: every check, ok or not. */
@Composable
fun StatusList() {
    val doctor by ListenerDoctor.status.collectAsState()
    rememberChecks().forEach { CheckRow(it, onError = false) }
    if (Health.isSamsung) SamsungTip(onError = false)
    ListenerStatusText(doctor, Modifier.padding(top = 8.dp))
    doctor.lastRepair?.let {
        Text("Last repair: $it", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
    var icon by remember(pkg) { mutableStateOf(iconCache.get(pkg)) }
    LaunchedEffect(pkg) {
        if (icon == null) {
            icon = withContext(Dispatchers.IO) {
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
