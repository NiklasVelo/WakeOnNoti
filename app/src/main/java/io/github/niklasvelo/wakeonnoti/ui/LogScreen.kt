@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.data.EventLog
import io.github.niklasvelo.wakeonnoti.data.LogEntry
import io.github.niklasvelo.wakeonnoti.data.LogKind

@Composable
fun LogScreen(onBack: () -> Unit, onCreateRule: (String) -> Unit) {
    val entries by EventLog.entries.collectAsState()
    var filter by remember { mutableIntStateOf(0) }
    val shown = when (filter) {
        1 -> entries.filter { it.kind == LogKind.NOTIFICATION }
        2 -> entries.filter { it.kind != LogKind.NOTIFICATION }
        else -> entries
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { EventLog.clear() }) { Icon(Icons.Filled.Delete, "Clear log") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Notifications", "Rules & errors").forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (filter == 1 || entries.isEmpty()) "Nothing yet. Incoming notifications show up here – tap one to build a rule from it."
                    else "Nothing here yet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown) { e ->
                    val tappable = e.kind == LogKind.NOTIFICATION && e.pkg.isNotBlank()
                    LogRow(e, if (tappable) ({ onCreateRule(e.pkg) }) else null)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val color = when (e.kind) {
        LogKind.FIRED -> MaterialTheme.colorScheme.primary
        LogKind.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val kind = when (e.kind) {
        LogKind.NOTIFICATION -> "Notification"
        LogKind.FIRED -> "Rule fired"
        LogKind.INFO -> "Info"
        LogKind.ERROR -> "Problem"
    }
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.kind == LogKind.NOTIFICATION && e.pkg.isNotBlank()) {
            AppIcon(e.pkg, 28.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "$kind · " + DateUtils.formatDateTime(
                    context, e.time,
                    DateUtils.FORMAT_SHOW_TIME or (if (DateUtils.isToday(e.time)) 0 else DateUtils.FORMAT_SHOW_DATE),
                ),
                style = MaterialTheme.typography.labelSmall, color = color,
            )
            Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.text.isNotBlank()) {
                Text(e.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) {
            Text("+ Rule", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}
