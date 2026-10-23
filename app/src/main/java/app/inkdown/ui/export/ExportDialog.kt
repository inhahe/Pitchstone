package app.inkdown.ui.export

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.inkdown.container
import app.inkdown.data.ConflictPolicy
import app.inkdown.data.ExportFormat
import app.inkdown.data.ExportLayout
import app.inkdown.export.ExportNote
import app.inkdown.export.ExportPlanner
import app.inkdown.export.ExportRequest
import app.inkdown.export.ExportResult
import app.inkdown.export.ExportWriter
import app.inkdown.export.SafDir
import app.inkdown.ui.common.RadioGroup
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to export: a description for the dialog, how many notes, and how to load them. */
class ExportSource(
    val description: String,
    val count: Int,
    /** A name for the file when everything goes into one (or the single note's title). */
    val defaultFileName: String,
    val load: suspend () -> List<ExportNote>,
)

private sealed interface Phase {
    data object Options : Phase
    data class Running(val done: Int, val total: Int) : Phase
    data class Done(val result: ExportResult, val folder: String) : Phase
    data class Failed(val message: String) : Phase
}

@Composable
fun ExportDialog(source: ExportSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings by container.settings.settings.collectAsState(initial = null)
    val s = settings ?: return
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Options) }
    var fileName by remember { mutableStateOf(source.defaultFileName) }
    var folderLabel by remember { mutableStateOf<String?>(null) }

    // The remembered destination, if the app may still write there.
    val savedTree = s.exportTreeUri?.let(Uri::parse)?.takeIf { uri ->
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }
    LaunchedEffect(savedTree) {
        folderLabel = savedTree?.let { uri ->
            withContext(Dispatchers.IO) { runCatching { SafDir.root(context.contentResolver, uri).name }.getOrNull() }
        }
    }

    fun update(transform: (app.inkdown.data.AppSettings) -> app.inkdown.data.AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    val single = source.count == 1
    val combined = !single && s.exportLayout == ExportLayout.SINGLE_FILE

    fun runExport(tree: Uri) {
        val request = ExportRequest(
            format = s.exportFormat,
            layout = if (single) ExportLayout.FILE_PER_NOTE else s.exportLayout,
            keepFolders = s.exportKeepFolders,
            titleHeading = s.exportTitleHeading,
            metadata = s.exportMetadata,
            textKeepsMarkdown = s.exportTextKeepsMarkdown,
            singleFileName = if (single || combined) fileName else "",
        )
        val policy = s.exportConflict
        phase = Phase.Running(0, source.count)
        container.appScope.launch {
            val outcome = runCatching {
                val notes = source.load()
                val files = withContext(Dispatchers.Default) { ExportPlanner.plan(notes, request) }
                withContext(Dispatchers.IO) {
                    val root = SafDir.root(context.contentResolver, tree)
                    val result = ExportWriter.write(root, files, policy) { done ->
                        phase = Phase.Running(done, files.size)
                    }
                    result to root.name
                }
            }
            phase = outcome.fold(
                onSuccess = { (result, name) -> Phase.Done(result, name) },
                onFailure = { Phase.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            update { it.copy(exportTreeUri = uri.toString()) }
            runExport(uri)
        }
    }

    when (val p = phase) {
        is Phase.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done / p.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${p.done} of ${p.total} files")
                }
            },
            confirmButton = {},
        )
        is Phase.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (p.result.ok) "Export finished" else "Export finished with problems") },
            text = { ExportSummary(p.result, p.folder) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        is Phase.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Export failed") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        Phase.Options -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Export") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text(source.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SectionLabel("Format")
                    RadioGroup(ExportFormat.entries, s.exportFormat, { it.label }, { f -> update { it.copy(exportFormat = f) } })
                    if (s.exportFormat == ExportFormat.TEXT) {
                        SwitchRow("Keep markdown symbols", "Write the note source instead of plain text", s.exportTextKeepsMarkdown) { v ->
                            update { it.copy(exportTextKeepsMarkdown = v) }
                        }
                    }
                    if (!single) {
                        SectionLabel("Files")
                        RadioGroup(ExportLayout.entries, s.exportLayout, { it.label }, { l -> update { it.copy(exportLayout = l) } })
                        if (!combined) {
                            SwitchRow("Keep folders", "Recreate the note folders as subfolders", s.exportKeepFolders) { v ->
                                update { it.copy(exportKeepFolders = v) }
                            }
                        }
                    }
                    if (!combined) {
                        SwitchRow("Title as heading", "Start each file with the note title", s.exportTitleHeading) { v ->
                            update { it.copy(exportTitleHeading = v) }
                        }
                    }
                    if (s.exportFormat == ExportFormat.MARKDOWN) {
                        SwitchRow("Dates and tags", "Add created and modified dates and tags as front matter", s.exportMetadata) { v ->
                            update { it.copy(exportMetadata = v) }
                        }
                    }
                    if (single || combined) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("File name") },
                            suffix = { Text(".${s.exportFormat.extension}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SectionLabel("If a file already exists")
                    RadioGroup(ConflictPolicy.entries, s.exportConflict, { it.label }, { c -> update { it.copy(exportConflict = c) } })
                    SectionLabel("Destination")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            folderLabel ?: "Choose a folder when you export",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (savedTree != null) {
                            OutlinedButton(onClick = { pickFolder.launch(savedTree) }) { Text("Change") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (savedTree != null) runExport(savedTree) else pickFolder.launch(null)
                }) { Text(if (savedTree != null) "Export" else "Choose folder") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExportSummary(result: ExportResult, folder: String) {
    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val n = result.written.size
        Text("Wrote $n ${if (n == 1) "file" else "files"} to “$folder”.")
        if (result.skipped.isNotEmpty()) {
            Text("Skipped ${result.skipped.size} that already existed:", fontWeight = FontWeight.Medium)
            result.skipped.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (result.failed.isNotEmpty()) {
            Text("Could not write ${result.failed.size}:", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            result.failed.take(20).forEach { (name, why) -> Text("• $name: $why", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A default name for an export of several notes: "Notes 2026-10-23". */
fun defaultExportName(prefix: String = "Notes"): String = "$prefix ${LocalDate.now()}"
