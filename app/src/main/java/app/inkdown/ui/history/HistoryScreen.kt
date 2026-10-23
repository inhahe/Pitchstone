package app.inkdown.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkdown.container
import app.inkdown.data.RevisionEntity
import app.inkdown.data.RevisionSummary
import app.inkdown.ui.common.Dates
import kotlinx.coroutines.launch

/**
 * Earlier versions of a note. Any of them can be restored (the current one is kept as a version
 * too) or deleted: one at a time with Undo, or all at once after a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(noteId: Long, onBack: () -> Unit) {
    val repo = LocalContext.current.container.repository
    val revisions by repo.revisions(noteId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var preview by remember { mutableStateOf<RevisionSummary?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    fun delete(r: RevisionSummary) = scope.launch {
        val deleted = repo.deleteRevision(noteId, r.id) ?: return@launch
        val result = snackbar.showSnackbar("Version deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) repo.undeleteRevision(deleted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Version history") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (revisions.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) { Icon(Icons.Outlined.DeleteSweep, "Delete all versions") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (revisions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No earlier versions. While you edit, the previous version is kept every 10 minutes, " +
                        "and before you empty a note or delete most of it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(revisions, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { preview = r }.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(Dates.full(r.savedAt), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "“${r.title.ifBlank { "Untitled" }}” · ${r.length} characters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { delete(r) }) { Icon(Icons.Outlined.Delete, "Delete this version") }
                    }
                }
            }
        }
    }

    preview?.let { r ->
        val full by produceState<RevisionEntity?>(null, r.id) { value = repo.revision(r.id) }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(Dates.full(r.savedAt)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(full?.title?.ifBlank { "Untitled" } ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(
                        full?.body ?: "",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    scope.launch {
                        repo.restoreRevision(noteId, r.id)
                        onBack()
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        preview = null
                        delete(r)
                    }) { Text("Delete") }
                    TextButton(onClick = { preview = null }) { Text("Close") }
                }
            },
        )
    }

    if (confirmDeleteAll) {
        val count = revisions.size
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all versions?") },
            text = {
                Text(
                    "The ${if (count == 1) "earlier version" else "$count earlier versions"} of this note will be " +
                        "deleted for good. The note itself stays as it is.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch { repo.deleteAllRevisions(noteId) }
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}
