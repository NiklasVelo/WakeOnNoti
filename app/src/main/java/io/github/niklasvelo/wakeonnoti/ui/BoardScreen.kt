@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.niklasvelo.wakeonnoti.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.niklasvelo.wakeonnoti.actions.AutoAiBoard
import io.github.niklasvelo.wakeonnoti.actions.BoardState
import io.github.niklasvelo.wakeonnoti.actions.BoardTask

private val STATE_LABEL = mapOf(
    "todo" to "To do", "doing" to "Doing", "waiting" to "Waiting",
    "needs_you" to "Needs you", "done" to "Done", "dropped" to "Dropped",
)

fun boardStateLabel(state: String) = STATE_LABEL[state] ?: state

private val PRIORITY_LABEL = listOf("1 · first", "2", "3", "4 · some day")

/** Today just the time; any other day with weekday and date. */
private fun whenText(c: Context, ms: Long): String = DateUtils.formatDateTime(
    c, ms,
    DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(ms)) 0 else
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
)

// ---------------------------------------------------------------- the list --

/** AutoAI's tasks: what needs you first, then what is open, then what closed lately. */
@Composable
fun BoardScreen(onBack: () -> Unit, onOpen: (Int) -> Unit, onNew: () -> Unit) {
    val context = LocalContext.current
    val board by AutoAiBoard.board.collectAsState()
    var showClosed by remember { mutableStateOf(false) }
    // opening the screen is when the board is fetched; changes arrive by themselves after that
    LaunchedEffect(Unit) { if (AutoAiBoard.available) AutoAiBoard.sync() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${board.from}'s board") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { AutoAiBoard.sync() }, enabled = AutoAiBoard.available) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            if (AutoAiBoard.available) {
                ExtendedFloatingActionButton(
                    onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New ticket") },
                )
            }
        },
    ) { padding ->
        val needsYou = board.tasks.filter { it.state == "needs_you" }
        val open = board.tasks.filterNot { it.state == "needs_you" }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!AutoAiBoard.available) {
                item {
                    Card {
                        Text(
                            "The board needs an AutoAI setup: turn on “Receive AutoAI's inbox” in Settings → AutoAI.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Text(
                    if (board.syncedAt == 0L) "Getting the board from ${board.from}…"
                    else "Updated ${whenText(context, board.syncedAt)} · changes arrive by themselves",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (needsYou.isNotEmpty()) {
                item { Text("Needs you", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
                items(needsYou, key = { "n${it.id}" }) { TaskCard(it) { onOpen(it.id) } }
            }
            item { Text("Open", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            if (open.isEmpty()) {
                item { Text("Nothing else open.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(open, key = { "o${it.id}" }) { TaskCard(it) { onOpen(it.id) } }
            if (board.closed.isNotEmpty()) {
                item {
                    TextButton(onClick = { showClosed = !showClosed }) {
                        Text(if (showClosed) "Hide closed" else "Show closed (${board.closed.size}, last 7 days)")
                    }
                }
                if (showClosed) items(board.closed, key = { "c${it.id}" }) { TaskCard(it) { onOpen(it.id) } }
            }
        }
    }
}

@Composable
private fun TaskCard(t: BoardTask, onOpen: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = if (t.state == "needs_you") CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                listOf("#${t.id}", boardStateLabel(t.state), "P${t.priority}", t.owner).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (t.waitingOn.isNotBlank()) Text("Waiting on ${t.waitingOn}", style = MaterialTheme.typography.bodySmall)
            t.nextCheck?.let { Text("Follow-up ${whenText(context, it)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ---------------------------------------------------------------- one task --

/** One task in full: every field, its log, a quick state change and a note. */
@Composable
fun BoardTaskScreen(id: Int, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val board by AutoAiBoard.board.collectAsState()
    val task = board.task(id)
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // a list entry has a shortened detail and no log
    LaunchedEffect(id) { if (task?.full != true) AutoAiBoard.syncTask(id) }

    fun done(err: String?) {
        busy = false
        error = err
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("#$id") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (task != null) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (task == null) {
                Text("Getting #$id from ${board.from}…", Modifier.padding(top = 16.dp))
                return@Column
            }
            Text(task.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            FieldRow("Priority", PRIORITY_LABEL.getOrElse(task.priority - 1) { task.priority.toString() })
            FieldRow("Owner", task.owner.ifBlank { "–" })
            FieldRow("Waiting on", task.waitingOn.ifBlank { "nothing" })
            FieldRow("Follow-up", task.nextCheck?.let { whenText(context, it) } ?: "none")
            FieldRow("Source", task.source.ifBlank { "–" })
            FieldRow("Created", whenText(context, task.created))
            FieldRow("Updated", whenText(context, task.updated))

            SectionTitle("State")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                board.states.forEach { s ->
                    FilterChip(
                        selected = task.state == s,
                        enabled = !busy,
                        onClick = {
                            if (s != task.state) {
                                busy = true
                                AutoAiBoard.update(id, mapOf("state" to s)) { _, err -> done(err) }
                            }
                        },
                        label = { Text(boardStateLabel(s)) },
                    )
                }
            }

            SectionTitle("Detail")
            Text(task.detail.ifBlank { "No detail." }, style = MaterialTheme.typography.bodyMedium)
            if (!task.full) {
                Text("Shortened – loading the full text…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitle("Add a note")
            OutlinedTextField(
                note, { note = it }, label = { Text("Note – goes to the log") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = note.isNotBlank() && !busy, onClick = {
                    busy = true
                    AutoAiBoard.note(id, note.trim()) { _, err ->
                        done(err)
                        if (err == null) note = ""
                    }
                }) { Text("Send note") }
                if (busy) {
                    Spacer(Modifier.width(12.dp))
                    Text("Sending…", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            SectionTitle("Log")
            when {
                !task.full -> Text("Loading the log…", style = MaterialTheme.typography.bodySmall)
                task.log.isEmpty() -> Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
                // the wire sends oldest first; the latest is what one looks for
                else -> task.log.asReversed().forEach { line ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(whenText(context, line.ts), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// --------------------------------------------------------- add or change --

/** A new ticket ([id] null) or a change to one. Sends only what changed. */
@Composable
fun BoardEditScreen(id: Int?, onClose: () -> Unit) {
    val context = LocalContext.current
    val board by AutoAiBoard.board.collectAsState()
    val current = id?.let { board.task(it) }
    val isNew = id == null
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    var detail by remember { mutableStateOf(current?.detail.orEmpty()) }
    var detailTouched by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(current?.state ?: "todo") }
    var priority by remember { mutableIntStateOf(current?.priority ?: 2) }
    var owner by remember { mutableStateOf(current?.owner.orEmpty()) }
    var waitingOn by remember { mutableStateOf(current?.waitingOn.orEmpty()) }
    // follow-up: typed text replaces it, the Clear chip removes it, neither leaves it alone
    var followUp by remember { mutableStateOf("") }
    var clearFollowUp by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // a list entry's detail is cut to 280 characters: editing that would cut the real one
    val detailReady = isNew || current?.full == true
    LaunchedEffect(id) { if (id != null && current?.full != true) AutoAiBoard.syncTask(id) }
    LaunchedEffect(current?.full, current?.updated) {
        if (current?.full == true && !detailTouched) detail = current.detail
    }

    fun changes(): Map<String, Any> = buildMap {
        if (isNew) {
            put("title", title.trim())
            if (detail.isNotBlank()) put("detail", detail.trim())
            put("state", state)
            put("priority", priority)
            if (owner.isNotBlank()) put("owner", owner.trim())
            if (waitingOn.isNotBlank()) put("waiting_on", waitingOn.trim())
        } else if (current != null) {
            if (title.trim() != current.title) put("title", title.trim())
            if (detailTouched && detail.trim() != current.detail) put("detail", detail.trim())
            if (state != current.state) put("state", state)
            if (priority != current.priority) put("priority", priority)
            if (owner.trim() != current.owner) put("owner", owner.trim())
            if (waitingOn.trim() != current.waitingOn) put("waiting_on", waitingOn.trim())
        }
        when {
            clearFollowUp -> if (!isNew) put("follow_up", "")
            followUp.isNotBlank() -> put("follow_up", followUp.trim())
        }
    }
    val dirty = changes().let { if (isNew) title.isNotBlank() || detail.isNotBlank() else it.isNotEmpty() } || note.isNotBlank()

    fun save() {
        if (title.isBlank()) {
            error = "A ticket needs a title."
            return
        }
        val fields = changes()
        val noteText = note.trim()
        val finish = { _: Int?, err: String? ->
            saving = false
            if (err == null) onClose() else error = err
        }
        error = null
        when {
            isNew -> {
                saving = true
                AutoAiBoard.add(fields + if (noteText.isNotEmpty()) mapOf("note" to noteText) else emptyMap(), finish)
            }
            fields.isEmpty() && noteText.isEmpty() -> {
                context.toast("Nothing changed")
                onClose()
            }
            fields.isEmpty() -> {
                saving = true
                AutoAiBoard.note(id!!, noteText, finish)
            }
            else -> {
                saving = true
                AutoAiBoard.update(id!!, fields + if (noteText.isNotEmpty()) mapOf("note" to noteText) else emptyMap(), finish)
            }
        }
    }

    BackHandler { if (dirty && !saving) confirmDiscard = true else onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New ticket" else "Edit #$id") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty && !saving) confirmDiscard = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = { IconButton(onClick = { save() }, enabled = !saving) { Icon(Icons.Filled.Check, "Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (!isNew && current == null) {
                Text("Getting #$id from ${board.from}…", Modifier.padding(top = 16.dp))
                return@Column
            }
            if (isNew) {
                Text(
                    "${board.from} gets this as your instruction and puts it in order on its board.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            OutlinedTextField(
                title, { title = it.take(200) }, label = { Text("Title") }, singleLine = true,
                isError = error != null && title.isBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                detail, { detail = it.take(4000); detailTouched = true },
                label = { Text("Detail (optional)") }, enabled = detailReady, minLines = 3,
                supportingText = if (detailReady) null else ({ Text("Loading the full text…") }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionTitle("State")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                board.states.forEach { s ->
                    FilterChip(selected = state == s, onClick = { state = s }, label = { Text(boardStateLabel(s)) })
                }
            }
            SectionTitle("Priority")
            Segmented(PRIORITY_LABEL, (priority - 1).coerceIn(0, 3)) { priority = it + 1 }

            OutlinedTextField(
                owner, { owner = it }, label = { Text("Owner") }, singleLine = true,
                supportingText = { Text("Who does it. Empty on a new ticket: ${board.from}.") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                waitingOn, { waitingOn = it }, label = { Text("Waiting on (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionTitle("Follow-up")
            Text(
                when {
                    clearFollowUp -> "Will be removed."
                    current?.nextCheck != null -> "Now: ${whenText(context, current.nextCheck)}. Type to change it."
                    else -> "None yet. When should ${board.from} look at it again?"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                followUp, { followUp = it; clearFollowUp = false }, singleLine = true,
                placeholder = { Text("in 2h · 14:00 · morgen 09:00 · 2026-11-05 09:00") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { followUp = "in 2h"; clearFollowUp = false }, label = { Text("in 2h") })
                AssistChip(onClick = { followUp = "tomorrow 09:00"; clearFollowUp = false }, label = { Text("tomorrow 09:00") })
                if (current?.nextCheck != null) {
                    AssistChip(onClick = { followUp = ""; clearFollowUp = true }, label = { Text("No follow-up") })
                }
            }

            OutlinedTextField(
                note, { note = it }, label = { Text("Note (optional) – goes to the log") }, minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Button(
                onClick = { save() }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(if (saving) "Sending…" else if (isNew) "Create ticket" else "Save changes") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (isNew) "Discard this ticket?" else "Discard your changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** Home: the way in, with what needs you counted. Only with an AutoAI setup. */
@Composable
fun BoardCard(board: BoardState, onOpen: () -> Unit) {
    val needsYou = board.tasks.count { it.state == "needs_you" }
    Card(
        onClick = onOpen,
        colors = if (needsYou > 0) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${board.from}'s board", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        board.syncedAt == 0L -> "Its tasks – and your tickets for it"
                        needsYou > 0 -> "${board.tasks.size} open · $needsYou need${if (needsYou == 1) "s" else ""} you"
                        else -> "${board.tasks.size} open"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
