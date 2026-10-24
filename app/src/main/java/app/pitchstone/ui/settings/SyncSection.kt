package app.pitchstone.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pitchstone.container
import app.pitchstone.sync.GoneFiles
import app.pitchstone.sync.SyncPreview
import app.pitchstone.sync.SyncReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Sync with a folder: choosing the folder (after a preview), the last sync, and stopping. */
@Composable
fun SyncSection(folderUri: String?) {
    val context = LocalContext.current
    val container = context.container
    val sync = container.sync
    val status by sync.status.collectAsState()
    val folderName by produceState("the folder", folderUri) {
        value = folderUri?.let { withContext(Dispatchers.IO) { sync.folderName(it) } } ?: "the folder"
    }

    /** The folder being chosen, while its preview shows. */
    var choosing by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<SyncPreview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        choosing = uri
        preview = null
        previewError = null
        container.appScope.launch {
            // Only for the folder still being chosen (not one cancelled meanwhile).
            try {
                val p = sync.preview(uri)
                if (choosing == uri) preview = p
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (choosing == uri) previewError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    Text(
        "Keep the notes in step with a folder on this phone: an Obsidian vault, or a folder Syncthing or another app syncs. " +
            "Each note is a markdown file there, Folder/Title.md, and the pictures notes show are files there too. " +
            "Changes on either side reach the other when Pitchstone opens or closes, and every 15 minutes or so.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    if (folderUri == null) {
        Button(onClick = { pick.launch(null) }) { Text("Choose folder") }
    } else {
        Text("Syncing with $folderName", style = MaterialTheme.typography.bodyLarge)
        val last = status.last
        when {
            status.running -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Syncing…", style = MaterialTheme.typography.bodyMedium)
            }
            last != null -> {
                val change = status.lastChange
                val text = when {
                    change == null -> "Last synced ${ago(last.at)}: everything was in step."
                    change.at == last.at -> "Last synced ${ago(last.at)}: ${summary(change)}."
                    else -> "Last synced ${ago(last.at)}. Last change ${ago(change.at)}: ${summary(change)}."
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        status.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }
        val conflicts = status.lastChange?.conflicts.orEmpty()
        if (conflicts.isNotEmpty()) {
            Text(
                "Changed both here and in the folder: ${conflicts.joinToString(", ") { "“$it”" }}. " +
                    "The newer text was kept; the other is in the note's version history.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (last != null && last.heldBack > 0 && !status.running) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "The files of ${last.heldBack} notes were deleted from $folderName. So many at once may be a mistake " +
                            "(a folder emptied, a memory card not there), so the notes were left as they are.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { container.appScope.launch { sync.syncNow(GoneFiles.WRITE_AGAIN) } }) { Text("Write the files again") }
                        TextButton(onClick = { container.appScope.launch { sync.syncNow(GoneFiles.TRASH_NOTES) } }) { Text("Move notes to trash") }
                    }
                }
            }
        }
        if (last != null && last.problems.isNotEmpty()) {
            val n = last.problems.size
            TextButton(onClick = { showProblems = !showProblems }, modifier = Modifier.padding(start = 0.dp)) {
                Text(if (showProblems) "Hide what could not be synced" else "$n ${if (n == 1) "file" else "files"} could not be synced")
            }
            if (showProblems) {
                for (p in last.problems.take(50)) Text(p, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                if (n > 50) Text("…and ${n - 50} more", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { container.appScope.launch { sync.syncNow() } }, enabled = !status.running) { Text("Sync now") }
            OutlinedButton(onClick = { pick.launch(null) }, enabled = !status.running) { Text("Change folder") }
        }
        TextButton(onClick = { confirmStop = true }) { Text("Stop syncing") }
    }

    val uri = choosing
    if (uri != null) {
        fun cancel() {
            choosing = null
            if (uri.toString() != folderUri) container.appScope.launch { sync.releasePermission(uri.toString()) }
        }
        val p = preview
        AlertDialog(
            onDismissRequest = ::cancel,
            title = { Text("Sync with this folder?") },
            text = {
                when {
                    previewError != null -> Text("The folder could not be read: $previewError")
                    p == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Looking at the folder…")
                    }
                    else -> Text(previewText(p))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = p != null && previewError == null,
                    onClick = {
                        choosing = null
                        container.appScope.launch { sync.start(uri) }
                    },
                ) { Text("Start syncing") }
            },
            dismissButton = { TextButton(onClick = ::cancel) { Text("Cancel") } },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop syncing?") },
            text = { Text("The notes here and the files in $folderName stay as they are; they just stop being kept in step.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    container.appScope.launch { sync.stop() }
                }) { Text("Stop syncing") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

/** "just now", "5 minutes ago", "yesterday"... */
private fun ago(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }
}

private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

/** What a first sync will do, in words. */
private fun previewText(p: SyncPreview): String = buildString {
    append("• ${count(p.toFolder, "note", "notes")} will be written to the folder.\n")
    append("• ${count(p.fromFolder, "file", "files")} will become notes.\n")
    append("• ${count(p.inBoth, "note is", "notes are")} in both")
    if (p.differing > 0) {
        append(", ${p.differing} with different text: the newer text is kept, and the other goes to the note's version history")
    }
    append(".\n\nNothing is deleted. From then on, a change on either side reaches the other, and a note deleted on one side ")
    append("goes to the trash on the other (the folder's is .trash, as in Obsidian).")
}

/** What a sync did, in a few words. */
internal fun summary(r: SyncReport): String {
    val parts = buildList {
        if (r.imported > 0) add("${count(r.imported, "note", "notes")} new from the folder")
        if (r.written > 0) add("${count(r.written, "note", "notes")} written to it")
        if (r.updated > 0) add("${count(r.updated, "note", "notes")} updated from it")
        if (r.renamed > 0) add("${count(r.renamed, "note", "notes")} renamed or moved")
        if (r.trashedNotes > 0) add("${count(r.trashedNotes, "note", "notes")} moved to the trash")
        if (r.trashedFiles > 0) add("${count(r.trashedFiles, "file", "files")} moved to the folder's .trash")
        if (r.pictures > 0) add("${count(r.pictures, "picture", "pictures")} copied")
        if (r.conflicts.isNotEmpty()) add("${count(r.conflicts.size, "note", "notes")} changed on both sides")
    }
    return if (parts.isEmpty()) "everything was in step" else parts.joinToString(", ")
}
