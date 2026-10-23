package app.pitchstone.ui.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitchstone.container
import app.pitchstone.export.ImportFile
import app.pitchstone.export.ImportResult
import app.pitchstone.export.Importer
import app.pitchstone.export.SafDir
import app.pitchstone.ui.common.FolderPickerDialog
import app.pitchstone.ui.common.RadioGroup
import app.pitchstone.ui.export.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Phase {
    data object Options : Phase
    data class Running(val done: Int, val total: Int) : Phase
    data class Done(val result: ImportResult, val unsupported: Int) : Phase
    data class Failed(val message: String) : Phase
}

@Composable
fun ImportDialog(currentFolder: String, folders: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    var phase by remember { mutableStateOf<Phase>(Phase.Options) }
    var destination by remember { mutableStateOf(currentFolder) }
    var keepFolders by remember { mutableStateOf(true) }
    var txtAsMarkdown by remember { mutableStateOf(false) }
    var pickingDestination by remember { mutableStateOf(false) }

    fun run(collect: suspend () -> Pair<List<ImportFile>, Int>) {
        phase = Phase.Running(0, 0)
        container.appScope.launch {
            val outcome = runCatching {
                val (files, unsupported) = withContext(Dispatchers.IO) { collect() }
                phase = Phase.Running(0, files.size)
                val result = withContext(Dispatchers.IO) {
                    Importer.import(container.repository, files, destination, keepFolders, txtAsMarkdown) { done ->
                        phase = Phase.Running(done, files.size)
                    }
                }
                result to unsupported
            }
            phase = outcome.fold(
                onSuccess = { (r, unsupported) -> Phase.Done(r, unsupported) },
                onFailure = { Phase.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run { Importer.collect(SafDir.root(context.contentResolver, uri)) to 0 }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run { filesFrom(context.contentResolver, uris) }
    }

    if (pickingDestination) {
        FolderPickerDialog("Import into", folders, destination, onDismiss = { pickingDestination = false }) {
            destination = it
            pickingDestination = false
        }
        return
    }

    when (val p = phase) {
        is Phase.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done / p.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(if (p.total == 0) "Looking for notes…" else "${p.done} of ${p.total} files")
                }
            },
            confirmButton = {},
        )
        is Phase.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import finished") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val r = p.result
                    Text("Imported ${r.imported} ${if (r.imported == 1) "note" else "notes"}.")
                    if (r.alreadyPresent > 0) Text("${r.alreadyPresent} were already here unchanged and were not imported again.")
                    if (p.unsupported > 0) Text("${p.unsupported} chosen ${if (p.unsupported == 1) "file is" else "files are"} not .md or .txt and ${if (p.unsupported == 1) "was" else "were"} skipped.")
                    if (r.skippedLarge.isNotEmpty()) {
                        Text("Too large to import (over 8 MB):", fontWeight = FontWeight.Medium)
                        r.skippedLarge.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (r.failed.isNotEmpty()) {
                        Text("Could not read ${r.failed.size}:", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                        r.failed.take(20).forEach { (name, why) -> Text("• $name: $why", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        is Phase.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import failed") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        Phase.Options -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import notes") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Markdown (.md) and text (.txt) files become notes, named after the file. " +
                            "Importing the same files again does not duplicate them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Into", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (destination.isEmpty()) "Top level" else destination,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(onClick = { pickingDestination = true }) { Text("Change") }
                    }
                    SwitchRow("Keep subfolders", "When importing a folder, recreate its subfolders", keepFolders) { keepFolders = it }
                    Text(".txt files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    RadioGroup(
                        listOf(false, true),
                        txtAsMarkdown,
                        { if (it) "Read as markdown" else "Keep as plain text (symbols stay literal)" },
                        { txtAsMarkdown = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Import a folder…") }
                    OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import files…") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** Importable files among picked [uris], and how many were not markdown or text. */
private fun filesFrom(resolver: ContentResolver, uris: List<Uri>): Pair<List<ImportFile>, Int> {
    val files = ArrayList<ImportFile>()
    var unsupported = 0
    for (uri in uris) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported"
        var size = 0L
        var modified: Long? = null
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { modified = c.getLong(it).takeIf { t -> t > 0 } }
            }
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in Importer.EXTENSIONS) {
            unsupported++
            continue
        }
        files += ImportFile(
            title = name.substringBeforeLast('.'),
            extension = ext,
            folder = "",
            lastModified = modified,
            size = size,
            open = { resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(name) },
        )
    }
    return files to unsupported
}
