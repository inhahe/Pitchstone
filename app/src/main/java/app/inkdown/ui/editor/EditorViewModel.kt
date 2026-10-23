package app.inkdown.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkdown.AppContainer
import app.inkdown.ListMessage
import app.inkdown.data.AppSettings
import app.inkdown.data.FolderPaths
import app.inkdown.data.NoteRepository
import app.inkdown.data.NoteSummary
import app.inkdown.markdown.PlainText
import app.inkdown.markdown.WikiLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

sealed interface EditorEvent {
    data class OpenUrl(val url: String) : EditorEvent
    data class OpenNote(val id: Long) : EditorEvent
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

class EditorViewModel(
    val noteId: Long,
    private val container: AppContainer,
) : ViewModel() {
    private val repo = container.repository

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Titles of all notes, for wikilink suggestions and the link picker. */
    val noteTitles: StateFlow<List<String>> = repo.titles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var port: EditorPort? = null
    private var pageReady = false
    private var loadedIntoPage = false

    /** The newest content known (from the database, or reported by the page). */
    private var content: Content? = null
    /** What the database holds as far as this editor knows. */
    private var saved: Content? = null
    /** The content being written right now. */
    private var inFlight: Content? = null
    private val saveLock = Mutex()

    /** The title when editing began, and the name links were last updated to. */
    private var linkedTitle: String? = null
    /** Whether the note was empty when opened (a new note): left empty, it is deleted. */
    private var startedEmpty = false
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
            linkedTitle = note.title
            startedEmpty = note.title.isBlank() && note.body.isBlank()
            val mode = if (settings.value.openInReadingMode && !startedEmpty) EditorMode.READ else EditorMode.EDIT
            _state.update {
                it.copy(loaded = true, title = note.title, folder = note.folder, pinned = note.pinned, mode = mode)
            }
            loadIntoPage()
            watchForOutsideChanges()
        }
        viewModelScope.launch {
            combine(settings, noteTitles) { s, t -> s to t }.collect { (s, titles) ->
                if (loadedIntoPage) {
                    port?.call("setSettings", settingsJson(s).toString())
                    port?.call("setNoteTitles", titlesJson(titles).toString())
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

    /** Titles as they are written, and as wikilinks can spell them, for "missing note" marks. */
    private fun titlesJson(titles: List<String>) = buildJsonArray {
        val seen = HashSet<String>()
        for (t in titles) {
            for (name in listOf(t, WikiLinks.linkName(t))) if (name.isNotEmpty() && seen.add(name)) add(JsonPrimitive(name))
        }
    }

    private fun loadIntoPage() {
        val p = port ?: return
        val c = content ?: return
        if (!pageReady || loadedIntoPage) return
        loadedIntoPage = true
        val fresh = startedEmpty && c.title.isEmpty() && c.markdown.isEmpty()
        val payload = buildJsonObject {
            put("markdown", c.markdown)
            put("title", c.title)
            put("mode", _state.value.mode.js)
            put("settings", settingsJson(settings.value))
            put("noteTitles", titlesJson(noteTitles.value))
            put("focus", if (fresh) "title" else "none")
        }
        p.call("load", payload.toString())
        // A new note starts with the keyboard open on its title.
        if (fresh && _state.value.mode == EditorMode.EDIT) p.showKeyboard()
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
        _state.update { it.copy(title = c.title) }
        // Saves run in the app's scope: leaving the screen must not cancel one.
        container.appScope.launch { saveNow() }
    }

    /** Writes the newest content if the database does not have it yet. One save at a time. */
    private suspend fun saveNow() = saveLock.withLock {
        val c = content ?: return@withLock
        if (c == saved) return@withLock
        inFlight = c
        try {
            val result = repo.save(noteId, c.title, c.markdown)
            if (result != NoteRepository.SaveResult.MISSING) saved = c
            _state.update { it.copy(saveError = null) }
        } catch (e: Exception) {
            _state.update { it.copy(saveError = e.message ?: "Could not save") }
        } finally {
            inFlight = null
        }
    }

    /**
     * Collects the page's unsaved change, saves everything, then runs [then] on the main
     * thread. Used before anything that leaves the note.
     */
    fun flushThen(then: suspend () -> Unit) {
        val p = port
        val finish = {
            container.appScope.launch {
                saveNow()
                withContext(Dispatchers.Main) { then() }
            }
        }
        if (p == null || !loadedIntoPage) {
            finish()
            return
        }
        p.flush { result ->
            if (result != null && result["dirty"]?.jsonPrimitive?.booleanOrNull == true) {
                val markdown = result["markdown"]?.jsonPrimitive?.contentOrNull
                if (markdown != null) {
                    content = Content(result["title"]?.jsonPrimitive?.contentOrNull ?: "", markdown)
                    _state.update { it.copy(title = content!!.title) }
                }
            }
            finish()
        }
    }

    /** The app goes to the background: save, and update links if the note was renamed. */
    fun onStop() = flushThen { updateLinksAfterRename() }

    /**
     * Leaving the note: save, delete it if it was created empty and is still empty, and update
     * links to it if it was renamed. Then [onClosed].
     */
    fun close(onClosed: () -> Unit) = flushThen {
        val c = content
        if (c != null && c.title.isBlank() && c.markdown.isBlank()) {
            if (startedEmpty) {
                repo.deleteForever(listOf(noteId))
            } else {
                // A note emptied by hand goes to the trash, recoverable, rather than away.
                repo.moveToTrash(listOf(noteId))
                container.listMessages.trySend(
                    ListMessage("Empty note moved to trash", "Undo") { repo.restore(listOf(noteId)) },
                )
            }
        } else {
            updateLinksAfterRename()
        }
        onClosed()
    }

    private suspend fun updateLinksAfterRename() {
        val c = content ?: return
        val old = linkedTitle ?: return
        if (c.title == old || !settings.value.updateLinksOnRename) return
        val changed = repo.propagateRename(noteId, old, c.title)
        linkedTitle = c.title
        if (changed > 0) container.listMessages.send(ListMessage("Updated links in $changed ${if (changed == 1) "note" else "notes"}"))
    }

    private fun openWikilink(target: String) = flushThen {
        val folder = _state.value.folder
        val ref = repo.resolveLink(target, folder)
        val id = ref?.id ?: run {
            val name = target.substringBefore('#').trim().substringAfterLast('/')
            if (name.isEmpty()) return@flushThen
            repo.create(title = name, folder = folder)
        }
        if (id != noteId) _events.send(EditorEvent.OpenNote(id))
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
            if (h.isNotEmpty() && !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(h) && !h.startsWith("/") && !h.startsWith("#") && h.contains('.')) "https://$h" else h
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
        val clean = WikiLinks.linkName(target)
        if (clean.isEmpty()) return
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
