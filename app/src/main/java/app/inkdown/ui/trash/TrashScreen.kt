package app.inkdown.ui.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkdown.container
import app.inkdown.data.NoteRepository
import app.inkdown.data.NoteSummary
import app.inkdown.ui.common.ConfirmDialog
import app.inkdown.ui.common.Dates
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val repo = LocalContext.current.container.repository
    val notes by repo.trash().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<NoteSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (notes.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash") }
                },
            )
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("The trash is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                item {
                    Text(
                        "Notes here are deleted for good ${NoteRepository.TRASH_RETENTION_MS / 86_400_000} days after they were trashed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(notes, key = { it.id }) { note ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (note.title.isBlank()) {
                                Text("Untitled", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.titleMedium)
                            } else {
                                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (note.snippet.isNotBlank()) {
                                Text(note.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "Trashed ${Dates.relative(note.trashedAt ?: 0)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { scope.launch { repo.restore(listOf(note.id)) } }) {
                            Icon(Icons.Outlined.RestoreFromTrash, "Restore")
                        }
                        IconButton(onClick = { confirmDelete = note }) {
                            Icon(Icons.Outlined.DeleteForever, "Delete forever", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty the trash?",
            text = "${notes.size} ${if (notes.size == 1) "note is" else "notes are"} deleted for good, with ${if (notes.size == 1) "its" else "their"} version history.",
            confirmLabel = "Delete forever",
            destructive = true,
            onDismiss = { confirmEmpty = false },
        ) {
            confirmEmpty = false
            scope.launch { repo.emptyTrash() }
        }
    }
    confirmDelete?.let { note ->
        ConfirmDialog(
            title = "Delete forever?",
            text = "“${note.title.ifBlank { "Untitled" }}” and its version history are deleted for good.",
            confirmLabel = "Delete forever",
            destructive = true,
            onDismiss = { confirmDelete = null },
        ) {
            confirmDelete = null
            scope.launch { repo.deleteForever(listOf(note.id)) }
        }
    }
}
