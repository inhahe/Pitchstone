package app.inkdown.ui.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.inkdown.container
import app.inkdown.export.ExportNote
import app.inkdown.export.FileNames
import app.inkdown.ui.common.ConfirmDialog
import app.inkdown.ui.common.FolderPickerDialog
import app.inkdown.ui.export.ExportDialog
import app.inkdown.ui.export.ExportSource
import app.inkdown.ui.share.ShareSheet
import app.inkdown.ui.theme.editorTheme
import app.inkdown.ui.theme.isDark
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    noteId: Long,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onShowTag: (String) -> Unit,
    onHistory: (Long) -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val vm: EditorViewModel = viewModel(key = "editor-$noteId") { EditorViewModel(noteId, container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val titles by vm.noteTitles.collectAsStateWithLifecycle()
    val folders by container.repository.folders().collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf<Content?>(null) }
    var export by remember { mutableStateOf<ExportSource?>(null) }
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showBacklinks by remember { mutableStateOf(false) }

    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    fun openUrl(url: String) {
        val uri = Uri.parse(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url")
        if (url.startsWith("#") || url.isBlank()) {
            message("That link points inside a note")
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            message("No app can open $url")
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is EditorEvent.OpenUrl -> openUrl(event.url)
                is EditorEvent.OpenNote -> onOpenNote(event.id)
                is EditorEvent.ShowTag -> onShowTag(event.tag)
                is EditorEvent.Message -> message(event.text)
                EditorEvent.Close -> onBack()
            }
        }
    }

    BackHandler {
        if (state.findOpen) vm.closeFind() else vm.close(onBack)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }

    val dark = isDark(settings.theme)
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(colors, dark) { vm.setTheme(colors.editorTheme(dark)) }

    Scaffold(
        topBar = {
            if (state.findOpen) {
                FindBar(
                    query = state.findQuery,
                    count = state.findCount,
                    current = state.findCurrent,
                    onQuery = vm::find,
                    onStep = vm::findStep,
                    onClose = vm::closeFind,
                )
            } else {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { vm.close(onBack) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (state.saveError != null) {
                            IconButton(onClick = { message("Not saved: ${state.saveError}") }) {
                                Icon(Icons.Outlined.ErrorOutline, "Not saved", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (state.mode == EditorMode.EDIT) {
                            IconButton(onClick = { vm.setMode(EditorMode.READ) }) { Icon(Icons.AutoMirrored.Outlined.MenuBook, "Reading view") }
                        } else {
                            IconButton(onClick = { vm.setMode(EditorMode.EDIT) }) { Icon(Icons.Outlined.Edit, "Edit") }
                        }
                        IconButton(onClick = vm::openFind) { Icon(Icons.Outlined.Search, "Find in note") }
                        IconButton(onClick = { vm.withContent { share = it } }) { Icon(Icons.Outlined.Share, "Share") }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (state.mode == EditorMode.SOURCE) "Formatted view" else "Markdown source") },
                                leadingIcon = { Icon(Icons.Outlined.Code, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.setMode(if (state.mode == EditorMode.SOURCE) EditorMode.EDIT else EditorMode.SOURCE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export…") },
                                leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.withContent { c ->
                                        val note = container.repository.note(noteId)
                                        export = ExportSource(
                                            description = "This note: “${c.title.ifBlank { "Untitled" }}”",
                                            count = 1,
                                            defaultFileName = FileNames.safe(c.title),
                                        ) {
                                            listOf(
                                                ExportNote(
                                                    c.title, c.markdown, note?.folder ?: "", note?.createdAt ?: 0, note?.updatedAt ?: 0,
                                                    container.repository.tagsOf(noteId),
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.pinned) "Unpin" else "Pin to top") },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.setPinned(!state.pinned)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Move to folder…") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                                onClick = {
                                    menuOpen = false
                                    moving = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Links to this note") },
                                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.flushThen { showBacklinks = true }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Version history") },
                                leadingIcon = { Icon(Icons.Outlined.History, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.flushThen { onHistory(noteId) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Note info") },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.flushThen { showInfo = true }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Move to trash", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            EditorWebViewHost(vm, ::openUrl, Modifier.weight(1f).fillMaxWidth())
            if (state.mode == EditorMode.EDIT && settings.showToolbar) {
                MarkdownToolbar(
                    state = state.toolbar,
                    onCommand = { name: String, args: JsonObject? -> vm.command(name, args) },
                    onWikilink = vm::requestWikilink,
                )
            }
        }
    }

    state.linkDialog?.let { d ->
        LinkDialog(d, onDismiss = vm::dismissLinkDialog, onApply = vm::applyLink, onRemove = vm::removeLink)
    }
    state.wikilinkDialog?.let { d ->
        WikilinkDialog(d, titles, onDismiss = vm::dismissWikilinkDialog, onPick = vm::insertWikilink)
    }
    share?.let { c ->
        ShareSheet(c.title, c.markdown, onDismiss = { share = null }, onError = { message(it) })
    }
    export?.let { source -> ExportDialog(source) { export = null } }
    if (moving) {
        FolderPickerDialog("Move to folder", folders, state.folder, onDismiss = { moving = false }) { folder ->
            moving = false
            vm.move(folder)
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Move to trash?",
            text = "The note stays in the trash for 30 days, where you can restore it.",
            confirmLabel = "Move to trash",
            destructive = true,
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            vm.moveToTrash()
        }
    }
    if (showInfo) NoteInfoDialog(vm) { showInfo = false }
    if (showBacklinks) {
        BacklinksDialog(vm, onDismiss = { showBacklinks = false }) { id ->
            showBacklinks = false
            onOpenNote(id)
        }
    }
}

/** The editor's WebView. It is rebuilt (and reloaded) if its renderer process dies. */
@Composable
private fun EditorWebViewHost(vm: EditorViewModel, openUrl: (String) -> Unit, modifier: Modifier) {
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                lateinit var web: EditorWebView
                web = EditorWebView(
                    ctx,
                    onMessage = vm::onMessage,
                    onExternalLink = { uri -> openUrl(uri.toString()) },
                    onRendererGone = { generation++ },
                )
                val port = object : EditorPort {
                    override fun call(function: String, vararg args: String) = web.call(function, *args)
                    override fun flush(callback: (JsonObject?) -> Unit) = web.flush(callback)
                    override fun showKeyboard() = web.showKeyboard()
                }
                web.tag = port
                vm.attach(port)
                web
            },
            onRelease = { web ->
                (web.tag as? EditorPort)?.let(vm::detach)
                web.destroy()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
    query: String,
    count: Int,
    current: Int,
    onQuery: (String) -> Unit,
    onStep: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close find") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Find in note") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onStep(true) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                suffix = { if (query.isNotEmpty()) Text(if (count == 0) "0" else "$current/$count") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            IconButton(onClick = { onStep(false) }, enabled = count > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = { onStep(true) }, enabled = count > 0) { Icon(Icons.Outlined.KeyboardArrowDown, "Next match") }
        },
    )
}
