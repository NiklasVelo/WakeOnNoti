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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.actions.AutoAiInbox
import io.github.niklasvelo.wakeonnoti.actions.InboxItem
import java.text.DateFormat
import java.util.Date

/** What AutoAI needs from you. Each answer goes straight back to it. */
@Composable
fun InboxScreen(onBack: () -> Unit) {
    val items by AutoAiInbox.items.collectAsState()
    var answering by remember { mutableStateOf<InboxItem?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoAI inbox") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty()) item { EmptyInbox() }
            items(items, key = { it.id }) { item -> InboxCard(item, onAnswer = { answering = item }) }
        }
    }
    answering?.let { item -> AnswerDialog(item, onDismiss = { answering = null }) }
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

@Composable
private fun InboxCard(item: InboxItem, onAnswer: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    fun act(action: String) = AutoAiInbox.resolve(item, action) { result ->
        if (result != "delivered") context.toast("Not sent – still in the inbox ($result)")
    }
    val urgent = item.urgency == "urgent"
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
                        Button(onClick = onAnswer) { Text("Answer…") }
                        OutlinedButton(onClick = { act("reject") }) { Text("Skip") }
                    }
                    "todo" -> Button(onClick = { act("done") }) { Text("Done") }
                    else -> OutlinedButton(onClick = { act("done") }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun AnswerDialog(item: InboxItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                AutoAiInbox.resolve(item, "answer", text.trim()) { result ->
                    if (result != "delivered") context.toast("Not sent – still in the inbox ($result)")
                }
                onDismiss()
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
