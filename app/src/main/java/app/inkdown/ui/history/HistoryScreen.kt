package app.inkdown.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
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

/** Earlier versions of a note; any of them can be restored (the current one is kept too). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(noteId: Long, onBack: () -> Unit) {
    val repo = LocalContext.current.container.repository
    val revisions by repo.revisions(noteId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<RevisionSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Version history") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (revisions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No earlier versions yet. While you edit, the previous version is kept every 10 minutes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(revisions, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().clickable { preview = r }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(Dates.full(r.savedAt), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "“${r.title.ifBlank { "Untitled" }}” · ${r.length} characters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                }) { Text("Restore this version") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
}
