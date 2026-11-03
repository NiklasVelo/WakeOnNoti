@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.niklasvelo.wakeonnoti.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.actions.AutoAiInbox
import io.github.niklasvelo.wakeonnoti.actions.InboxItem
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** While the inbox is on screen, ask AutoAI for the whole open list this often. */
private const val ON_SCREEN_REFRESH_MS = 60_000L
/** An ask unanswered this long: AutoAI is probably not running on the PC. */
private const val NO_ANSWER_MS = 20_000L

/** What AutoAI needs from you. Each answer goes straight back to it. */
@Composable
fun InboxScreen(onBack: () -> Unit) {
    val items by AutoAiInbox.items.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // new items arrive by themselves; this catches one lost on the way
    LaunchedEffect(Unit) {
        var lastAsk = 0L
        while (true) {
            now = System.currentTimeMillis()
            if (now - lastAsk >= ON_SCREEN_REFRESH_MS) {
                AutoAiInbox.refresh()
                lastAsk = now
            }
            delay(5_000L)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoAI inbox") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { AutoAiInbox.refresh() }, enabled = AutoAiInbox.enabled) {
                        Icon(Icons.Filled.Refresh, "Reload")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (AutoAiInbox.enabled) item { SyncLine(now) }
            if (items.isEmpty()) item { EmptyInbox() }
            items(items, key = { it.id }) { item -> InboxCard(item) }
        }
    }
}

/** How fresh this copy is: the reload button's answer, too. */
@Composable
private fun SyncLine(now: Long) {
    val listed by AutoAiInbox.lastList.collectAsState()
    val missing by AutoAiInbox.lastListMissing.collectAsState()
    val asking by AutoAiInbox.asking.collectAsState()
    val problem by AutoAiInbox.lastProblem.collectAsState()
    val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(listed))
    Text(
        when {
            problem != null -> "Cannot reach the server: $problem"
            asking > 0 && now - asking < NO_ANSWER_MS -> "Asking AutoAI what is open…"
            asking > 0 -> "No answer yet – is AutoAI running on the PC? It answers once it is."
            listed > 0 && missing != 0 -> "Compared with AutoAI at $at, but " +
                (if (missing > 0) "$missing of its open items" else "some of its open items") +
                " did not fit in its list – the least urgent and oldest. They may be missing here or out of date."
            listed > 0 -> "Up to date with AutoAI at $at · new things arrive by themselves"
            else -> "Not compared with AutoAI yet – tap reload."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyInbox() {
    val enabled = AutoAiInbox.enabled
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Nothing waiting", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) "When AutoAI needs you – a decision, an approval, something only you can do – it shows up here. " +
                    "Urgent things ring the alarm."
                else "Turn on “Receive AutoAI's inbox” in Settings → AutoAI to see it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val KIND_LABEL = mapOf(
    "draft" to "Draft – approve to run it exactly like this",
    "proposal" to "Proposal",
    "question" to "Question",
    "todo" to "For you to do",
    "info" to "Note",
)

/**
 * One item, with a text field for every kind: the answer to a question, for anything else a
 * note that goes along with the button ("go ahead, but not before 9"). Empty is fine, except
 * as an answer. What is typed is kept as a draft ([AutoAiInbox.draft]) until it is sent, and a
 * failed send puts it back.
 */
@Composable
private fun InboxCard(item: InboxItem) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // not rememberSaveable: the list keeps that by key, so an item put back after a failed send
    // came back with the field as it was before - empty - instead of the note that did not go out
    var note by remember(item.id) { mutableStateOf(AutoAiInbox.draft(item.id)) }
    fun act(action: String) = AutoAiInbox.resolve(item, action, note) { result ->
        if (result != "delivered") context.toast("Not sent – still in the inbox ($result)")
    }
    val urgent = item.urgency == "urgent"
    val question = item.kind == "question"
    Card(
        onClick = { expanded = !expanded },
        colors = if (urgent) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "#${item.id} · ${KIND_LABEL[item.kind] ?: item.kind} · ${item.from} · " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.created)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.body, style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.full) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (item.body.isBlank()) "The text did not fit in AutoAI's list – it is on the PC."
                    else "Shortened to fit AutoAI's list – the whole text is on the PC.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                note,
                {
                    note = it.take(AutoAiInbox.NOTE_MAX)
                    AutoAiInbox.setDraft(item.id, note)
                },
                label = { Text(if (question) "Your answer (or why you skip it)" else "Note for ${item.from} (optional)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.kind) {
                    "draft" -> {
                        Button(onClick = { act("approve") }) { Text("Approve") }
                        OutlinedButton(onClick = { act("reject") }) { Text("Reject") }
                    }
                    "proposal" -> {
                        Button(onClick = { act("approve") }) { Text("Go ahead") }
                        OutlinedButton(onClick = { act("reject") }) { Text("No") }
                    }
                    "question" -> {
                        Button(onClick = { act("answer") }, enabled = note.isNotBlank()) { Text("Send answer") }
                        OutlinedButton(onClick = { act("reject") }) { Text("Skip") }
                    }
                    "todo" -> Button(onClick = { act("done") }) { Text("Done") }
                    else -> OutlinedButton(onClick = { act("done") }) { Text(if (note.isBlank()) "OK" else "Send") }
                }
            }
        }
    }
}
