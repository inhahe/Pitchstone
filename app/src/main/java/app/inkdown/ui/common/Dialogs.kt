package app.inkdown.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.inkdown.data.FolderPaths

/** A dialog asking for one line of text. [validate] returns an error message, or null if fine. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "OK",
    validate: (String) -> String? = { if (it.isBlank()) "Enter a name" else null },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var touched by remember { mutableStateOf(false) }
    val error = validate(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    touched = true
                },
                label = { Text(label) },
                singleLine = true,
                isError = touched && error != null,
                supportingText = { if (touched && error != null) Text(error) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (error == null) onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = error == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks for the name of a folder below [parent] and returns its path. A name that matches one of
 * the [existing] paths, ignoring case, is refused: exported, the two would share one directory.
 * [renaming] is the path of the folder being renamed, which does not count as taken.
 */
@Composable
fun FolderNameDialog(
    title: String,
    parent: String,
    existing: List<String>,
    confirmLabel: String,
    initial: String = "",
    renaming: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (path: String) -> Unit,
) {
    TextInputDialog(
        title = title,
        label = "Folder name",
        initial = initial,
        confirmLabel = confirmLabel,
        validate = { name ->
            val clean = FolderPaths.cleanName(name)
            val target = FolderPaths.child(parent, clean)
            when {
                clean.isEmpty() -> "Enter a name"
                existing.any { it != renaming && it.equals(target, ignoreCase = true) } -> "That folder exists"
                else -> null
            }
        },
        onDismiss = onDismiss,
        onConfirm = { onConfirm(FolderPaths.child(parent, FolderPaths.cleanName(it))) },
    )
}

/**
 * Picks a folder among [folders] (paths), the top level, or a new one. [current] is marked.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var parentForNew by remember { mutableStateOf("") }
    if (creating) {
        FolderNameDialog(
            title = if (parentForNew.isEmpty()) "New folder" else "New folder in ${FolderPaths.name(parentForNew)}",
            parent = parentForNew,
            existing = folders,
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = onPick,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    FolderRow("Top level", 0, current == "", Icons.Outlined.Home) { onPick("") }
                }
                items(folders) { path ->
                    FolderRow(FolderPaths.name(path), path.count { it == '/' } + 1, current == path, Icons.Outlined.Folder) { onPick(path) }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            parentForNew = current ?: ""
                            creating = true
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("New folder…", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderRow(name: String, depth: Int, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 16).dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** A labelled group of radio buttons. */
@Composable
fun <T> RadioGroup(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        for (option in options) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(option) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = { onSelect(option) }, enabled = enabled)
                Text(label(option), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}
