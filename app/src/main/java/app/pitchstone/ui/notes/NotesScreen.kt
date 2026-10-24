package app.pitchstone.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pitchstone.container
import app.pitchstone.data.FolderPaths
import app.pitchstone.data.NoteSummary
import app.pitchstone.data.SortOrder
import app.pitchstone.ui.common.ConfirmDialog
import app.pitchstone.ui.common.Dates
import app.pitchstone.ui.common.FolderNameDialog
import app.pitchstone.ui.common.FolderPickerDialog
import app.pitchstone.ui.export.ExportDialog
import app.pitchstone.ui.export.ExportSource
import app.pitchstone.ui.export.defaultExportName
import app.pitchstone.ui.importer.ImportDialog
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A dialog the list screen can show. */
private sealed interface ListDialog {
    data class NewFolder(val parent: String) : ListDialog
    data class RenameFolder(val path: String) : ListDialog
    data class DeleteFolder(val path: String, val notes: Int) : ListDialog
    data object MoveSelection : ListDialog
    data class Export(val source: ExportSource) : ListDialog
    data object Import : ListDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onOpenNote: (Long) -> Unit, onSettings: () -> Unit, onTrash: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm: NotesViewModel = viewModel { NotesViewModel(container) }
    val scope by vm.scope.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val subfolders by vm.subfolders.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val folderCounts by vm.folderCounts.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val coroutines = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<ListDialog?>(null) }

    LaunchedEffect(Unit) {
        container.listMessages.receiveAsFlow().collect { msg ->
            // A message with Undo stays long enough to reach for it.
            val duration = if (msg.undoLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            val result = snackbar.showSnackbar(msg.text, actionLabel = msg.undoLabel, duration = duration)
            if (result == SnackbarResult.ActionPerformed) msg.undo?.invoke()
        }
    }

    BackHandler(enabled = drawer.isOpen) { coroutines.launch { drawer.close() } }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = searching && selection.isEmpty()) { vm.stopSearch() }
    // Back climbs out of a nested folder to its parent, and from anywhere else to all notes.
    BackHandler(enabled = !searching && selection.isEmpty() && scope != Scope.All && !drawer.isOpen) {
        val s = scope
        vm.setScope(if (s is Scope.Folder && s.path.contains('/')) Scope.Folder(FolderPaths.parent(s.path)) else Scope.All)
    }

    fun newNote() = coroutines.launch { onOpenNote(vm.createNote()) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AppDrawer(
                scope = scope,
                total = total,
                folders = folders,
                folderCounts = folderCounts,
                tags = tags,
                trashCount = trashCount,
                onScope = {
                    vm.setScope(it)
                    coroutines.launch { drawer.close() }
                },
                onNewFolder = { parent -> dialog = ListDialog.NewFolder(parent) },
                onRenameFolder = { dialog = ListDialog.RenameFolder(it) },
                onDeleteFolder = { path -> coroutines.launch { dialog = ListDialog.DeleteFolder(path, vm.countNotesIn(path)) } },
                onTrash = {
                    coroutines.launch { drawer.close() }
                    onTrash()
                },
                onImport = {
                    coroutines.launch { drawer.close() }
                    dialog = ListDialog.Import
                },
                onExportAll = {
                    coroutines.launch { drawer.close() }
                    dialog = ListDialog.Export(ExportSource("All $total notes", total, defaultExportName()) { vm.allNotesForExport() })
                },
                onSettings = {
                    coroutines.launch { drawer.close() }
                    onSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                when {
                    selection.isNotEmpty() -> SelectionBar(
                        count = selection.size,
                        allPinned = notes.filter { it.id in selection }.all { it.pinned },
                        onClose = vm::clearSelection,
                        onSelectAll = vm::selectAll,
                        onPin = vm::pinSelection,
                        onMove = { dialog = ListDialog.MoveSelection },
                        onExport = {
                            val ids = selection
                            dialog = ListDialog.Export(
                                ExportSource(
                                    "${ids.size} selected ${if (ids.size == 1) "note" else "notes"}",
                                    ids.size,
                                    if (ids.size == 1) notes.firstOrNull { it.id in ids }?.title?.ifBlank { "Untitled" } ?: "Untitled" else defaultExportName(),
                                ) { vm.exportNotes(ids) },
                            )
                        },
                        onDelete = vm::trashSelection,
                    )
                    searching -> SearchBar(query, onQuery = { vm.query.value = it }, onClose = vm::stopSearch)
                    else -> ListTopBar(
                        title = scopeTitle(scope),
                        sort = settings.sortOrder,
                        onMenu = { coroutines.launch { drawer.open() } },
                        onSearch = vm::startSearch,
                        onSort = vm::setSort,
                        onExport = {
                            val count = notes.size
                            dialog = ListDialog.Export(
                                ExportSource("${scopeTitle(scope)} ($count ${if (count == 1) "note" else "notes"})", count, defaultExportName(scopeTitle(scope))) {
                                    vm.exportNotes(notes.map { it.id })
                                },
                            )
                        },
                        onNewFolder = (scope as? Scope.Folder)?.let { s -> { dialog = ListDialog.NewFolder(s.path) } },
                    )
                }
            },
            floatingActionButton = {
                if (selection.isEmpty()) {
                    FloatingActionButton(onClick = { newNote() }) { Icon(Icons.Filled.Add, "New note") }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (notes.isEmpty() && subfolders.isEmpty()) {
                EmptyState(scope, searching, query, Modifier.padding(padding))
            } else {
                LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp)) {
                    items(subfolders, key = { "folder:$it" }) { path ->
                        FolderRow(path, folderCounts[path] ?: 0) { vm.setScope(Scope.Folder(path)) }
                    }
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            showFolder = scope !is Scope.Folder || searching,
                            selected = note.id in selection,
                            highlight = if (searching) query else "",
                            onClick = { if (selection.isEmpty()) onOpenNote(note.id) else vm.toggleSelection(note.id) },
                            onLongClick = { vm.toggleSelection(note.id) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is ListDialog.NewFolder -> FolderNameDialog(
            title = if (d.parent.isEmpty()) "New folder" else "New folder in ${FolderPaths.name(d.parent)}",
            parent = d.parent,
            existing = folders,
            confirmLabel = "Create",
            onDismiss = { dialog = null },
        ) {
            vm.createFolder(it)
            dialog = null
        }
        is ListDialog.RenameFolder -> FolderNameDialog(
            title = "Rename folder",
            parent = FolderPaths.parent(d.path),
            existing = folders,
            confirmLabel = "Rename",
            initial = FolderPaths.name(d.path),
            renaming = d.path,
            onDismiss = { dialog = null },
        ) {
            vm.renameFolder(d.path, it)
            dialog = null
        }
        is ListDialog.DeleteFolder -> ConfirmDialog(
            title = "Delete “${FolderPaths.name(d.path)}”?",
            text = if (d.notes == 0) "The folder is empty." else "Its ${d.notes} ${if (d.notes == 1) "note goes" else "notes go"} to the trash, where ${if (d.notes == 1) "it stays" else "they stay"} for 30 days.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { dialog = null },
        ) {
            vm.deleteFolder(d.path)
            dialog = null
        }
        ListDialog.MoveSelection -> FolderPickerDialog("Move to folder", folders, null, onDismiss = { dialog = null }) {
            vm.moveSelection(it)
            dialog = null
        }
        is ListDialog.Export -> ExportDialog(d.source) {
            dialog = null
            vm.clearSelection()
        }
        ListDialog.Import -> ImportDialog(
            currentFolder = (scope as? Scope.Folder)?.path ?: "",
            folders = folders,
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

private fun scopeTitle(scope: Scope): String = when (scope) {
    Scope.All -> "All notes"
    is Scope.Folder -> if (scope.path.isEmpty()) "Top level" else FolderPaths.name(scope.path)
    is Scope.Tag -> "#${scope.tag}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListTopBar(
    title: String,
    sort: SortOrder,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onSort: (SortOrder) -> Unit,
    onExport: () -> Unit,
    onNewFolder: (() -> Unit)?,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Folders and tags") } },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search") }
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    for (order in SortOrder.entries) {
                        DropdownMenuItem(
                            text = { Text(order.label, color = if (order == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                sortOpen = false
                                onSort(order)
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Export these notes…") },
                        leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    if (onNewFolder != null) {
                        DropdownMenuItem(
                            text = { Text("New folder here…") },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            onClick = {
                                menuOpen = false
                                onNewFolder()
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search all notes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    allPinned: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Cancel selection") } },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "Select all") }
            IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, if (allPinned) "Unpin" else "Pin") }
            IconButton(onClick = onMove) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to folder") }
            IconButton(onClick = onExport) { Icon(Icons.Outlined.FileDownload, "Export") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Move to trash") }
        },
    )
}

@Composable
private fun FolderRow(path: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(FolderPaths.name(path), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: NoteSummary,
    showFolder: Boolean,
    selected: Boolean,
    highlight: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.title.isBlank()) {
                Text("Untitled", fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            } else {
                Text(
                    highlighted(note.title, highlight, colors.primary),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (note.pinned) Icon(Icons.Outlined.PushPin, "Pinned", tint = colors.primary, modifier = Modifier.size(18.dp))
        }
        if (note.snippet.isNotBlank()) {
            Text(
                highlighted(note.snippet, highlight, colors.primary),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Dates.relative(note.updatedAt), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            if (showFolder && note.folder.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(FolderPaths.name(note.folder), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/**
 * [s] lower-cased one character at a time, keeping one whose lower case is longer (`İ`), so
 * that positions in the result are positions in [s].
 */
internal fun foldCase(s: String): String = buildString(s.length) {
    for (c in s) {
        val lower = c.lowercase()
        if (lower.length == 1) append(lower) else append(c)
    }
}

/** [text] with every occurrence of the words of [query] in bold and [color]. */
private fun highlighted(text: String, query: String, color: Color): AnnotatedString {
    val words = foldCase(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return AnnotatedString(text)
    val lower = foldCase(text)
    val marks = BooleanArray(text.length)
    for (w in words) {
        var i = lower.indexOf(w)
        while (i >= 0 && w.isNotEmpty()) {
            for (k in i until minOf(text.length, i + w.length)) marks[k] = true
            i = lower.indexOf(w, i + w.length)
        }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val on = marks[i]
            var j = i
            while (j < text.length && marks[j] == on) j++
            if (on) pushStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold))
            append(text.substring(i, j))
            if (on) pop()
            i = j
        }
    }
}

@Composable
private fun EmptyState(scope: Scope, searching: Boolean, query: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (searching) Icons.Outlined.Search else Icons.Outlined.Edit,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    searching && query.isBlank() -> "Type to search titles and text"
                    searching -> "No notes contain “$query”"
                    scope is Scope.Tag -> "No notes tagged #${scope.tag}"
                    scope is Scope.Folder -> "This folder is empty"
                    else -> "No notes yet"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!searching && scope !is Scope.Tag) {
                Text("Tap + to write one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
