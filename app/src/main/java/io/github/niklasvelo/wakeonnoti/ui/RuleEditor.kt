@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import io.github.niklasvelo.wakeonnoti.actions.Webhook
import io.github.niklasvelo.wakeonnoti.data.MatchField
import io.github.niklasvelo.wakeonnoti.data.MatchMode
import io.github.niklasvelo.wakeonnoti.data.Rule
import io.github.niklasvelo.wakeonnoti.data.RuleAction
import io.github.niklasvelo.wakeonnoti.data.Store
import io.github.niklasvelo.wakeonnoti.engine.Matcher
import io.github.niklasvelo.wakeonnoti.engine.NotiEvent
import io.github.niklasvelo.wakeonnoti.engine.RuleEngine
import io.github.niklasvelo.wakeonnoti.engine.label

private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

@Composable
fun RuleEditor(initial: Rule, isNew: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var rule by remember { mutableStateOf(initial) }
    // the text fields keep what was typed; the rule gets the parsed lists on save
    var keywordsText by remember { mutableStateOf(initial.keywords.joinToString("\n")) }
    var excludesText by remember { mutableStateOf(initial.excludes.joinToString("\n")) }
    var cooldownText by remember { mutableStateOf(initial.cooldownSec.takeIf { it > 0 }?.toString().orEmpty()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun current() = rule.copy(
        name = rule.name.trim(),
        keywords = lines(keywordsText),
        excludes = lines(excludesText),
        cooldownSec = cooldownText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    )
    val dirty = current() != initial
    fun close() = if (dirty) confirmDiscard = true else onClose()
    fun save() {
        Store.saveRule(current())
        onClose()
    }
    fun updateAction(i: Int, change: (RuleAction) -> RuleAction) {
        rule = rule.copy(actions = rule.actions.toMutableList().also { it[i] = change(it[i]) })
    }

    BackHandler { close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New rule" else "Edit rule") },
                navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { save() }) { Icon(Icons.Filled.Check, "Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                rule.name, { rule = rule.copy(name = it) }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            SwitchRow("Enabled", null, rule.enabled) { rule = rule.copy(enabled = it) }

            SectionTitle("When a notification comes from")
            AppChips(rule.apps, "Any app") { rule = rule.copy(apps = it) }

            SectionTitle("and it contains")
            Segmented(
                listOf("Anywhere", "Title", "Text"),
                MatchField.entries.indexOf(rule.field),
            ) { rule = rule.copy(field = MatchField.entries[it]) }
            Spacer(Modifier.height(8.dp))
            Segmented(
                listOf("Any word", "All words", "Regex"),
                MatchMode.entries.indexOf(rule.mode),
            ) { rule = rule.copy(mode = MatchMode.entries[it]) }
            val words = lines(keywordsText)
            val badPattern = if (rule.mode == MatchMode.REGEX) words.firstOrNull { Matcher.pattern(rule, it) == null } else null
            OutlinedTextField(
                keywordsText, { keywordsText = it },
                label = { Text(if (rule.mode == MatchMode.REGEX) "Patterns, one per line" else "Words or phrases, one per line") },
                isError = badPattern != null,
                supportingText = {
                    Text(
                        when {
                            badPattern != null -> "Not a valid pattern: $badPattern"
                            words.isEmpty() && rule.apps.isEmpty() -> "Empty and no app chosen: EVERY notification fires this rule."
                            words.isEmpty() -> "Empty: every notification from the chosen apps fires this rule."
                            else -> "Title and text are searched; for chats, only the newest message."
                        }
                    )
                },
                minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            CheckRow("Case sensitive", rule.caseSensitive) { rule = rule.copy(caseSensitive = it) }
            if (rule.mode != MatchMode.REGEX) {
                CheckRow("Whole words only (“wake” does not match “awake”)", rule.wholeWord) { rule = rule.copy(wholeWord = it) }
            }
            OutlinedTextField(
                excludesText, { excludesText = it },
                label = { Text("But not if it contains (optional)") },
                minLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                cooldownText, { cooldownText = it.filter(Char::isDigit).take(6) },
                label = { Text("Cooldown in seconds (optional)") },
                supportingText = { Text("Don't fire again within this time. Empty = every matching notification fires.") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionTitle("Then")
            rule.actions.forEachIndexed { i, action ->
                // keyed on the count too: after a removal every card starts fresh instead of
                // inheriting the typed-in state of the card that used to sit at its index
                key(i, rule.actions.size) {
                    ActionCard(
                        action, rule,
                        onChange = { updateAction(i) { _ -> it } },
                        onRemove = { rule = rule.copy(actions = rule.actions.filterIndexed { j, _ -> j != i }) },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (rule.actions.isEmpty()) Text("No actions – this rule would only show up in the log.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rule = rule.copy(actions = rule.actions + RuleAction.Alarm()) }) { Text("+ Alarm") }
                OutlinedButton(onClick = { rule = rule.copy(actions = rule.actions + RuleAction.Webhook()) }) { Text("+ Webhook") }
                OutlinedButton(onClick = { rule = rule.copy(actions = rule.actions + RuleAction.AutoAi()) }) { Text("+ AutoAI") }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = {
                    val r = current()
                    RuleEngine.fire(context, r, NotiEvent.sample(context, r.apps.firstOrNull(), r.keywords.firstOrNull()), test = true)
                    context.toast("Fired “${r.label}” – see the log for results")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test this rule now") }
            if (!isNew) {
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete rule") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Save changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; save() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${rule.label}”?") },
            confirmButton = {
                TextButton(onClick = { Store.deleteRule(rule.id); onClose() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionCard(action: RuleAction, rule: Rule, onChange: (RuleAction) -> Unit, onRemove: () -> Unit) {
    Card {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (action) {
                        is RuleAction.Alarm -> "Ring an alarm"
                        is RuleAction.Webhook -> "Call a webhook"
                        is RuleAction.AutoAi -> "Tell AutoAI"
                    },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove action") }
            }
            Column(Modifier.padding(end = 8.dp)) {
                when (action) {
                    is RuleAction.Alarm -> AlarmFields(action, onChange)
                    is RuleAction.Webhook -> WebhookFields(action, rule, onChange)
                    is RuleAction.AutoAi -> AutoAiFields(action, onChange)
                }
            }
        }
    }
}

private fun soundName(context: Context, uri: String): String =
    if (uri.isBlank()) "Default alarm sound"
    else runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull() ?: "Custom sound"

@Composable
private fun AlarmFields(a: RuleAction.Alarm, onChange: (RuleAction) -> Unit) {
    val context = LocalContext.current
    var minutes by remember { mutableStateOf(a.autoStopMin.toString()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            val value = uri?.takeIf { it != Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString().orEmpty()
            onChange(a.copy(soundUri = value))
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable {
            picker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        a.soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                    )
            )
        }.padding(vertical = 8.dp),
    ) {
        Column {
            Text("Sound", style = MaterialTheme.typography.bodyLarge)
            Text(soundName(context, a.soundUri), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    SwitchRow("Full volume", "Turns the alarm volume all the way up while it rings", a.maxVolume) { onChange(a.copy(maxVolume = it)) }
    SwitchRow("Vibrate", null, a.vibrate) { onChange(a.copy(vibrate = it)) }
    OutlinedTextField(
        minutes,
        {
            minutes = it.filter(Char::isDigit).take(4)
            onChange(a.copy(autoStopMin = minutes.toIntOrNull() ?: 0))
        },
        label = { Text("Stop by itself after (minutes)") },
        supportingText = { Text("0 = ring until you stop it") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WebhookFields(a: RuleAction.Webhook, rule: Rule, onChange: (RuleAction) -> Unit) {
    val context = LocalContext.current
    OutlinedTextField(
        a.url, { onChange(a.copy(url = it.trim())) }, label = { Text("URL") },
        placeholder = { Text("https://ntfy.sh/my-topic") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Segmented(Webhook.METHODS, Webhook.METHODS.indexOf(a.method).coerceAtLeast(0)) { onChange(a.copy(method = Webhook.METHODS[it])) }
    OutlinedTextField(
        a.headers, { onChange(a.copy(headers = it)) }, label = { Text("Headers (optional)") },
        placeholder = { Text("Authorization: Bearer …") },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    if (a.method != "GET") {
        OutlinedTextField(
            a.body, { onChange(a.copy(body = it)) }, label = { Text("Body") },
            minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
    Text(
        "Placeholders: ${Webhook.PLACEHOLDERS}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    TextButton(
        enabled = a.url.startsWith("http"),
        onClick = {
            Webhook.send(a, rule.label, NotiEvent.sample(context, rule.apps.firstOrNull(), rule.keywords.firstOrNull())) {
                context.toast("Webhook: $it")
            }
        },
    ) { Text("Send test") }
}

@Composable
private fun AutoAiFields(a: RuleAction.AutoAi, onChange: (RuleAction) -> Unit) {
    OutlinedTextField(
        a.ask, { onChange(a.copy(ask = it)) }, label = { Text("Instruction for AutoAI (optional)") },
        placeholder = { Text("e.g. Ping me on Discord") },
        supportingText = {
            Text("Empty: AutoAI is just told about it. Filled: AutoAI acts on it right away, with this instruction.")
        },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    if (!Store.settings.autoAiReady) {
        Text("Set up AutoAI in Settings first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
