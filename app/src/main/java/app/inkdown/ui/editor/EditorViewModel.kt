package app.inkdown.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkdown.AppContainer
import app.inkdown.ListMessage
import app.inkdown.data.AppSettings
import app.inkdown.data.FolderPaths
import app.inkdown.data.NoteRepository
import app.inkdown.data.NoteSummary
import app.inkdown.data.NoteTitle
import app.inkdown.markdown.PlainText
import app.inkdown.markdown.WikiLinks
import app.inkdown.ui.common.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class EditorMode(val js: String) {
    EDIT("edit"),
    READ("read"),
    SOURCE("source");

    companion object {
        fun from(js: String?) = entries.firstOrNull { it.js == js } ?: EDIT
    }
}

/** Formatting at the cursor, as reported by the editor page (bridge.ts ToolbarState). */
data class ToolbarState(
    val strong: Boolean = false,
    val em: Boolean = false,
    val strike: Boolean = false,
    val highlight: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
    val block: String = "paragraph",
    val headingLevel: Int = 0,
    val list: String? = null,
    val task: Boolean = false,
    val quote: Boolean = false,
    val table: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canIndent: Boolean = false,
    val canOutdent: Boolean = false,
)

data class LinkDialogState(val text: String, val href: String, val title: String?, val existing: Boolean)

data class WikilinkDialogState(val target: String, val alias: String?, val editing: Boolean)

/** A note's content as the editor holds it. */
data class Content(val title: String, val markdown: String)

data class EditorUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val title: String = "",
    val folder: String = "",
    val pinned: Boolean = false,
    val mode: EditorMode = EditorMode.EDIT,
    val toolbar: ToolbarState = ToolbarState(),
    val findOpen: Boolean = false,
    val findQuery: String = "",
    val findCount: Int = 0,
    val findCurrent: Int = 0,
    val linkDialog: LinkDialogState? = null,
    val wikilinkDialog: WikilinkDialogState? = null,
    /** Set when the latest save failed; cleared by the next successful one. */
    val saveError: String? = null,
    /** Why the note could not be closed (its latest changes are not saved). */
    val leaveBlocked: String? = null,
)

sealed interface EditorEvent {
    data class OpenUrl(val url: String) : EditorEvent
    /** Open note [id], at a `#heading` or `#^block` [anchor] if given. */
    data class OpenNote(val id: Long, val anchor: String? = null) : EditorEvent
    data class ShowTag(val tag: String) : EditorEvent
    data class Message(val text: String) : EditorEvent
    /** Leave the editor (the note was closed, deleted or does not exist). */
    data object Close : EditorEvent
}

/** The editor page, as seen from here (implemented by the screen around [EditorWebView]). */
interface EditorPort {
    fun call(function: String, vararg args: String)
    fun flush(callback: (JsonObject?) -> Unit)
    /** Opens the soft keyboard on the page's focused field (the page itself cannot). */
    fun showKeyboard()
}

/**
 * One note's editing session.
 *
 * @param anchor a `#heading` or `#^block` to scroll to once the note is shown
 */
class EditorViewModel(
    val noteId: Long,
    private val anchor: String?,
    private val container: AppContainer,
) : ViewModel() {
    private val repo = container.repository

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Every note's title and folder: for link suggestions, the link picker and missing-link marks. */
    private val linkTargets: StateFlow<List<NoteTitle>> = repo.linkTargets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The titles of all notes, each once. */
    val noteTitles: StateFlow<List<String>> = linkTargets
        .map { targets -> targets.map { it.title }.filter { it.isNotBlank() }.distinct() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var port: EditorPort? = null
    private var pageReady = false
    private var loadedIntoPage = false

    // Read on the main thread, written there and by saves in the app scope.
    /** The newest content known (from the database, or reported by the page). */
    @Volatile private var content: Content? = null
    /** What the database holds as far as this editor knows. */
    @Volatile private var saved: Content? = null
    /** The content being written right now. */
    @Volatile private var inFlight: Content? = null
    private val saveLock = Mutex()

    /** Whether the note was empty when opened: a new note gets the keyboard on its title. */
    private var openedEmpty = false
    /** Whether the note held any text during this session. One that never did may be discarded. */
    @Volatile private var everHadContent = false
    /** Set when the page could not produce markdown for its edits: they are not saved. */
    @Volatile private var pageError: String? = null
    private val finished = AtomicBoolean(false)
    private var themeJson: JsonObject? = null

    init {
        viewModelScope.launch {
            val note = repo.note(noteId)
            if (note == null) {
                _state.update { it.copy(missing = true) }
                _events.send(EditorEvent.Close)
                return@launch
            }
            val c = Content(note.title, note.body)
            content = c
            saved = c
            openedEmpty = note.title.isBlank() && note.body.isBlank()
            everHadContent = !openedEmpty
            val mode = if (settings.value.openInReadingMode && !openedEmpty) EditorMode.READ else EditorMode.EDIT
            _state.update {
                it.copy(loaded = true, title = note.title, folder = note.folder, pinned = note.pinned, mode = mode)
            }
            loadIntoPage()
            watchForOutsideChanges()
        }
        viewModelScope.launch {
            combine(settings, linkTargets) { s, t -> s to t }.collect { (s, targets) ->
                if (loadedIntoPage) {
                    port?.call("setSettings", settingsJson(s).toString())
                    port?.call("setNotes", notesJson(targets).toString())
                }
            }
        }
    }

    /** A change saved by someone else (a restored version, links updated after a rename). */
    private fun watchForOutsideChanges() {
        viewModelScope.launch {
            repo.observeNote(noteId).collect { note ->
                if (note == null) return@collect
                _state.update { it.copy(folder = note.folder, pinned = note.pinned) }
                val c = Content(note.title, note.body)
                if (c == saved || c == inFlight || c == content) return@collect
                saved = c
                content = c
                _state.update { it.copy(title = c.title) }
                if (loadedIntoPage) {
                    loadedIntoPage = false
                    loadIntoPage()
                }
            }
        }
    }

    // ---- The page ---------------------------------------------------------------------------

    fun attach(port: EditorPort) {
        this.port = port
        pageReady = false
        loadedIntoPage = false
    }

    fun detach(port: EditorPort) {
        if (this.port === port) {
            this.port = null
            pageReady = false
            loadedIntoPage = false
        }
    }

    fun setTheme(theme: Map<String, Any>) {
        themeJson = buildJsonObject {
            for ((k, v) in theme) {
                when (v) {
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        if (loadedIntoPage) port?.call("setSettings", settingsJson(settings.value).toString())
    }

    private fun settingsJson(s: AppSettings) = buildJsonObject {
        put("markdownShortcuts", s.markdownShortcuts)
        put("fontSize", s.fontSize)
        put("spellcheck", s.spellcheck)
        put("readableLineLength", s.readableLineLength)
        themeJson?.let { put("theme", it) }
    }

    /** The other notes for the page (NoteNames in editor.ts): titles, and keys to find them by. */
    private fun notesJson(targets: List<NoteTitle>) = buildJsonObject {
        put("titles", buildJsonArray {
            targets.map { it.title }.filter { it.isNotBlank() }.distinct().forEach { add(JsonPrimitive(it)) }
        })
        put("keys", buildJsonArray {
            targets.flatMap { WikiLinks.lookupKeys(it.title, it.folder) }.distinct().forEach { add(JsonPrimitive(it)) }
        })
    }

    private fun loadIntoPage() {
        val p = port ?: return
        val c = content ?: return
        if (!pageReady || loadedIntoPage) return
        loadedIntoPage = true
        val fresh = openedEmpty && c.title.isEmpty() && c.markdown.isEmpty()
        val payload = buildJsonObject {
            put("markdown", c.markdown)
            put("title", c.title)
            put("mode", _state.value.mode.js)
            put("settings", settingsJson(settings.value))
            put("notes", notesJson(linkTargets.value))
            put("focus", if (fresh) "title" else "none")
        }
        p.call("load", payload.toString())
        // A new note starts with the keyboard open on its title.
        if (fresh && _state.value.mode == EditorMode.EDIT) p.showKeyboard()
        if (anchor != null) p.call("scrollToAnchor", anchor)
    }

    /** A message from the page (see editor/src/editor/bridge.ts). */
    fun onMessage(msg: JsonObject) {
        fun str(key: String) = msg[key]?.jsonPrimitive?.contentOrNull
        when (str("type")) {
            "ready" -> {
                pageReady = true
                loadedIntoPage = false
                loadIntoPage()
            }
            "changed" -> {
                val markdown = str("markdown") ?: return
                onContent(Content(str("title") ?: "", markdown))
            }
            "state" -> msg["state"]?.jsonObject?.let { s -> _state.update { it.copy(toolbar = parseToolbar(s)) } }
            "modeChanged" -> _state.update { it.copy(mode = EditorMode.from(str("mode"))) }
            "openLink" -> str("href")?.let { href -> flushThen { _events.send(EditorEvent.OpenUrl(href)) } }
            "openNote" -> str("target")?.let { openWikilink(it) }
            "openTag" -> str("tag")?.let { tag -> flushThen { _events.send(EditorEvent.ShowTag(tag)) } }
            "editLink" -> _state.update {
                it.copy(
                    linkDialog = LinkDialogState(
                        text = str("text") ?: "",
                        href = str("href") ?: "",
                        title = str("title"),
                        existing = msg["existing"]?.jsonPrimitive?.booleanOrNull ?: false,
                    ),
                )
            }
            "editWikilink" -> _state.update {
                it.copy(wikilinkDialog = WikilinkDialogState(str("target") ?: "", str("alias"), editing = true))
            }
            "findResult" -> _state.update {
                it.copy(
                    findCount = msg["count"]?.jsonPrimitive?.intOrNull ?: 0,
                    findCurrent = msg["current"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            "warning" -> {
                val text = when (str("code")) {
                    "parseFailed" -> "This note opened in source mode: the editor could not read it safely."
                    "sourceParseFailed" -> "The markdown could not be read; staying in source mode."
                    "serializeFailed" -> "Your latest changes are not saved: the editor could not convert them to markdown."
                    else -> str("message") ?: "Something went wrong in the editor."
                }
                viewModelScope.launch { _events.send(EditorEvent.Message(text)) }
            }
        }
    }

    private fun parseToolbar(s: JsonObject): ToolbarState {
        fun b(k: String) = s[k]?.jsonPrimitive?.booleanOrNull ?: false
        return ToolbarState(
            strong = b("strong"), em = b("em"), strike = b("strike"), highlight = b("highlight"), code = b("code"),
            link = b("link"),
            block = s["block"]?.jsonPrimitive?.contentOrNull ?: "paragraph",
            headingLevel = s["headingLevel"]?.jsonPrimitive?.intOrNull ?: 0,
            list = s["list"]?.jsonPrimitive?.contentOrNull,
            task = b("task"), quote = b("quote"), table = b("table"),
            canUndo = b("canUndo"), canRedo = b("canRedo"), canIndent = b("canIndent"), canOutdent = b("canOutdent"),
        )
    }

    // ---- Saving -----------------------------------------------------------------------------

    private fun onContent(c: Content) {
        content = c
        if (c.title.isNotBlank() || c.markdown.isNotBlank()) everHadContent = true
        _state.update { it.copy(title = c.title) }
        // Saves run in the app's scope: leaving the screen must not cancel one.
        container.appScope.launch { saveNow() }
    }

    /**
     * Writes the newest content if the database does not have it yet, one save at a time.
     * Returns whether the database now holds everything this editor knows.
     */
    private suspend fun saveNow(): Boolean = saveLock.withLock {
        val c = content ?: return@withLock true
        if (c == saved) return@withLock true
        inFlight = c
        try {
            val result = repo.save(noteId, c.title, c.markdown)
            if (result != NoteRepository.SaveResult.MISSING) saved = c
            _state.update { it.copy(saveError = null) }
            true
        } catch (e: Exception) {
            _state.update { it.copy(saveError = e.message ?: "Could not save") }
            false
        } finally {
            inFlight = null
        }
    }

    /**
     * Collects the page's unsaved change, saves everything, then runs [then] on the main
     * thread with whether everything is saved. Used before anything that leaves the note.
     */
    private fun flushThen(then: suspend (saved: Boolean) -> Unit) {
        val p = port
        val finish = {
            container.appScope.launch {
                val ok = saveNow() && pageError == null
                withContext(Dispatchers.Main) { then(ok) }
            }
        }
        if (p == null || !loadedIntoPage) {
            finish()
            return
        }
        p.flush { result ->
            val error = result?.get("error")?.jsonPrimitive?.contentOrNull
            if (error != null) pageError = error
            if (result != null && result["dirty"]?.jsonPrimitive?.booleanOrNull == true) {
                val markdown = result["markdown"]?.jsonPrimitive?.contentOrNull
                if (markdown != null && error == null) {
                    pageError = null
                    val c = Content(result["title"]?.jsonPrimitive?.contentOrNull ?: "", markdown)
                    content = c
                    if (c.title.isNotBlank() || c.markdown.isNotBlank()) everHadContent = true
                    _state.update { it.copy(title = c.title) }
                }
            }
            finish()
        }
    }

    /** Saves what there is, then runs [action] (which does not leave the note for good). */
    fun flushThenRun(action: suspend () -> Unit) = flushThen { action() }

    /** The app goes to the background: save. */
    fun onStop() = flushThen {}

    /**
     * Leaving the note: save, then finish the session (see [finishSession]) and [onClosed].
     * If the note cannot be saved, it stays open and [EditorUiState.leaveBlocked] says why,
     * so nothing typed is thrown away without asking.
     */
    fun close(onClosed: () -> Unit) = flushThen { ok ->
        if (!ok) {
            val why = pageError?.let { "The editor could not convert your latest changes to markdown ($it)." }
                ?: "Your latest changes could not be saved: ${_state.value.saveError ?: "unknown error"}."
            _state.update { it.copy(leaveBlocked = why) }
            return@flushThen
        }
        finishSession()
        onClosed()
    }

    /** Leaves even though the latest changes are not saved (the user chose to). */
    fun leaveAnyway(onClosed: () -> Unit) {
        _state.update { it.copy(leaveBlocked = null) }
        container.appScope.launch {
            finishSession()
            withContext(Dispatchers.Main) { onClosed() }
        }
    }

    fun dismissLeaveBlocked() = _state.update { it.copy(leaveBlocked = null) }

    /**
     * The end of an editing session, once (when the note is closed, or its screen is removed
     * some other way). An empty note that never held anything is discarded; one that was
     * emptied goes to the trash (its text is in the version history). A renamed note gets the
     * links to it updated.
     */
    private suspend fun finishSession() {
        if (!finished.compareAndSet(false, true)) return
        val c = content ?: return
        if (c.title.isBlank() && c.markdown.isBlank()) {
            if (!everHadContent && repo.isPristine(noteId)) {
                repo.deleteForever(listOf(noteId))
            } else if (repo.note(noteId)?.trashedAt == null) {
                repo.moveToTrash(listOf(noteId))
                container.listMessages.trySend(ListMessage("Empty note moved to trash", "Undo") { repo.restore(listOf(noteId)) })
            }
            return
        }
        val changed = repo.applyRename(noteId, settings.value.updateLinksOnRename)
        if (changed > 0) container.listMessages.trySend(ListMessage("Updated links in $changed ${if (changed == 1) "note" else "notes"}"))
    }

    override fun onCleared() {
        // Removed without close() (a tag tapped further up the stack, say): save and finish.
        container.appScope.launch {
            saveNow()
            finishSession()
        }
    }

    /**
     * Follows a [[link]]: to the note it names, created if missing (`folder/note` in that
     * folder, as Obsidian does), and to its `#heading` or `#^block` if it has one.
     */
    private fun openWikilink(target: String) {
        val anchor = target.substringAfter('#', "").trim().ifEmpty { null }
        val name = target.substringBefore('#').trim()
        if (name.isEmpty()) {
            // [[#Heading]]: a place in this note.
            if (anchor != null) port?.call("scrollToAnchor", anchor)
            return
        }
        flushThen {
            val id = repo.resolveLink(target, _state.value.folder)?.id ?: run {
                // A link to a file (an image, a PDF) is not a note: do not make one up.
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty() && ext != "md" && ext.length <= 5 && !name.endsWith(" ")) {
                    _events.send(EditorEvent.Message("“$name” is a file, not a note; attachments are not supported"))
                    return@flushThen
                }
                val path = if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
                val folder = if ('/' in path) FolderPaths.normalize(path.substringBeforeLast('/')) else _state.value.folder
                val title = path.substringAfterLast('/').trim()
                if (title.isEmpty()) return@flushThen
                repo.create(title = title, folder = folder)
            }
            if (id == noteId) {
                if (anchor != null) port?.call("scrollToAnchor", anchor)
            } else {
                _events.send(EditorEvent.OpenNote(id, anchor))
            }
        }
    }

    // ---- Commands from the screen ----------------------------------------------------------

    fun command(name: String, args: JsonObject? = null) {
        val p = port ?: return
        if (args == null) p.call("command", name) else p.call("command", name, args.toString())
    }

    fun setMode(mode: EditorMode) {
        _state.update { it.copy(mode = mode) }
        port?.call("setMode", mode.js)
    }

    fun openFind() = _state.update { it.copy(findOpen = true) }

    fun closeFind() {
        _state.update { it.copy(findOpen = false, findQuery = "", findCount = 0, findCurrent = 0) }
        port?.call("find", "")
    }

    fun find(query: String) {
        _state.update { it.copy(findQuery = query) }
        port?.call("find", query)
    }

    fun findStep(forward: Boolean) = port?.call(if (forward) "findNext" else "findPrevious")

    fun dismissLinkDialog() = _state.update { it.copy(linkDialog = null) }

    fun applyLink(text: String, href: String) {
        _state.update { it.copy(linkDialog = null) }
        val url = href.trim().let { h ->
            // A bare domain gets https://, so the link opens.
            if (h.isNotEmpty() && !Urls.hasScheme(h) && !h.startsWith("/") && !h.startsWith("#") && h.contains('.')) "https://$h" else h
        }
        command("setLink", buildJsonObject { put("text", text); put("href", url) })
    }

    fun removeLink() {
        _state.update { it.copy(linkDialog = null) }
        command("removeLink")
    }

    fun requestWikilink() = _state.update { it.copy(wikilinkDialog = WikilinkDialogState("", null, editing = false)) }

    fun dismissWikilinkDialog() = _state.update { it.copy(wikilinkDialog = null) }

    fun insertWikilink(target: String, alias: String?) {
        _state.update { it.copy(wikilinkDialog = null) }
        val clean = WikiLinks.cleanTarget(target)
        if (clean.substringBefore('#').isBlank() && !clean.startsWith("#")) return
        command("insertWikilink", buildJsonObject {
            put("target", clean)
            if (!alias.isNullOrBlank()) put("alias", alias)
        })
    }

    // ---- Note actions -----------------------------------------------------------------------

    /** The current content, after saving it (for sharing and exporting). */
    fun withContent(action: suspend (Content) -> Unit) = flushThen { content?.let { action(it) } }

    fun setPinned(pinned: Boolean) = viewModelScope.launch { repo.setPinned(listOf(noteId), pinned) }

    fun move(folder: String) = viewModelScope.launch {
        repo.move(listOf(noteId), folder)
        _events.send(EditorEvent.Message(if (folder.isEmpty()) "Moved to the top level" else "Moved to ${FolderPaths.name(folder)}"))
    }

    fun moveToTrash() = flushThen {
        repo.moveToTrash(listOf(noteId))
        container.listMessages.trySend(ListMessage("Note moved to trash", "Undo") { repo.restore(listOf(noteId)) })
        _events.send(EditorEvent.Close)
    }

    suspend fun backlinks(): List<NoteSummary> = repo.backlinks(noteId)

    suspend fun info(): NoteInfo {
        val note = repo.note(noteId)
        val c = content ?: Content("", "")
        val text = withContext(Dispatchers.Default) { PlainText.render(c.markdown) }
        return NoteInfo(
            created = note?.createdAt ?: 0,
            modified = note?.updatedAt ?: 0,
            words = Regex("\\S+").findAll(text).count(),
            characters = text.length,
            folder = note?.folder ?: "",
            tags = repo.tagsOf(noteId),
            backlinks = repo.backlinks(noteId).size,
        )
    }
}

data class NoteInfo(
    val created: Long,
    val modified: Long,
    val words: Int,
    val characters: Int,
    val folder: String,
    val tags: List<String>,
    val backlinks: Int,
)
