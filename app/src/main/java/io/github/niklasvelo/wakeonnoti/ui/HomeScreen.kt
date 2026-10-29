@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.alarm.AlarmInfo
import io.github.niklasvelo.wakeonnoti.alarm.AlarmService
import io.github.niklasvelo.wakeonnoti.data.MatchField
import io.github.niklasvelo.wakeonnoti.data.MatchMode
import io.github.niklasvelo.wakeonnoti.data.Rule
import io.github.niklasvelo.wakeonnoti.data.RuleAction
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.engine.RuleEngine
import io.github.niklasvelo.wakeonnoti.engine.label
import io.github.niklasvelo.wakeonnoti.system.Apps

@Composable
fun HomeScreen(onEdit: (Rule) -> Unit, onNew: (Rule) -> Unit, onSettings: () -> Unit, onLog: () -> Unit) {
    val config by Store.config.collectAsState()
    val ringing by AlarmService.current.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WakeOnNoti") },
                actions = {
                    IconButton(onClick = onLog) { Icon(Icons.AutoMirrored.Filled.List, "Log") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNew(Rule()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New rule") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ringing?.let { item { RingingCard(it) } }
            item { ProblemsCard() }
            item { UpdateBanner(onOpen = onSettings) }
            if (config.rules.isEmpty()) item { EmptyState(onNew) }
            items(config.rules, key = { it.id }) { rule ->
                RuleCard(rule, onClick = { onEdit(rule) }, onToggle = { Store.saveRule(rule.copy(enabled = it)) })
            }
        }
    }
}

@Composable
private fun RingingCard(info: AlarmInfo) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ringing: ${info.rule}", style = MaterialTheme.typography.titleMedium)
                Text(info.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = { AlarmService.stop(context) }) { Text("Stop") }
        }
    }
}

@Composable
private fun EmptyState(onNew: (Rule) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("No rules yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "A rule watches incoming notifications – optionally only from certain apps – for words you choose, " +
                    "and then rings an alarm, calls a webhook, or tells AutoAI.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                onNew(Rule(name = "Wake up", keywords = listOf("wake up", "urgent"), wholeWord = true))
            }) { Text("Start from an example") }
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    Card(onClick = onClick) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary(context, rule), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(rule.enabled, onToggle)
        }
    }
}

fun summary(context: Context, rule: Rule): String {
    val apps = when {
        rule.apps.isEmpty() -> "Any app"
        rule.apps.size <= 2 -> rule.apps.joinToString(", ") { Apps.label(context, it) }
        else -> rule.apps.take(2).joinToString(", ") { Apps.label(context, it) } + " +${rule.apps.size - 2}"
    }
    val where = when (rule.field) {
        MatchField.ANYWHERE -> ""
        MatchField.TITLE -> " in the title"
        MatchField.TEXT -> " in the text"
    }
    val quoted = rule.keywords.map { "“$it”" }
    val condition = when {
        rule.keywords.isEmpty() -> "any notification"
        rule.mode == MatchMode.REGEX -> "matches " + quoted.joinToString(" or ") + where
        rule.mode == MatchMode.ALL -> "contains " + quoted.joinToString(" and ") + where
        else -> "contains " + quoted.joinToString(" or ") + where
    }
    val actions = rule.actions.joinToString(" + ") { RuleEngine.describe(it) }.ifEmpty { "nothing" }
    return "$apps · $condition → $actions"
}
