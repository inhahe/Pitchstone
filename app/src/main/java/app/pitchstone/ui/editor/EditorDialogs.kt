package app.pitchstone.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitchstone.data.NoteSummary
import app.pitchstone.markdown.WikiLinks
import app.pitchstone.ui.common.Dates

@Composable
fun LinkDialog(state: LinkDialogState, onDismiss: () -> Unit, onApply: (String, String) -> Unit, onRemove: () -> Unit) {
    var text by remember { mutableStateOf(state.text) }
    var href by remember { mutableStateOf(state.href) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.existing) "Edit link" else "Add link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = href,
                    onValueChange = { href = it },
                    label = { Text("Address") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    placeholder = { Text("Shown instead of the address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text, href) }, enabled = href.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (state.existing) TextButton(onClick = onRemove) { Text("Remove link") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Picks the note a [[wikilink]] points at, with an optional display text. */
@Composable
fun WikilinkDialog(
    state: WikilinkDialogState,
    titles: List<String>,
    onDismiss: () -> Unit,
    onPick: (String, String?) -> Unit,
) {
    var query by remember { mutableStateOf(state.target) }
    var alias by remember { mutableStateOf(state.alias ?: "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val choices = remember(query, titles) { LinkChoices.of(query, titles) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.editing) "Change link" else "Link to a note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(choices) { c ->
                        PickRow(
                            if (c.kind == LinkChoice.Kind.NEW_NOTE) Icons.Outlined.Add else Icons.Outlined.Description,
                            c.label,
                            highlight = c.kind != LinkChoice.Kind.NOTE,
                        ) { onPick(c.target, alias.ifBlank { null } ?: c.alias) }
                    }
                }
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Display text (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NoteInfoDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val info by produceState<NoteInfo?>(null) { value = vm.info() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note info") },
        text = {
            val i = info
            if (i == null) {
                Text("…")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Created", Dates.full(i.created))
                    InfoRow("Modified", Dates.full(i.modified))
                    InfoRow("Words", i.words.toString())
                    InfoRow("Characters", i.characters.toString())
                    InfoRow("Folder", i.folder.ifEmpty { "Top level" })
                    InfoRow("Tags", if (i.tags.isEmpty()) "None" else i.tags.joinToString(" ") { "#$it" })
                    InfoRow("Linked from", "${i.backlinks} ${if (i.backlinks == 1) "note" else "notes"}")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value)
    }
}

@Composable
fun BacklinksDialog(vm: EditorViewModel, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val notes by produceState<List<NoteSummary>?>(null) { value = vm.backlinks() }
    val state by vm.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Links to this note") },
        text = {
            val list = notes
            when {
                list == null -> Text("…")
                list.isEmpty() -> Text("No other note links here yet. Link to it with [[${state.title.ifBlank { "its title" }}]].")
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list) { n ->
                        PickRow(Icons.Outlined.Description, n.title.ifBlank { "Untitled" }) { onOpen(n.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
