package app.inkdown.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkdown.AppContainer
import app.inkdown.ListMessage
import app.inkdown.ListRequest
import app.inkdown.data.AppSettings
import app.inkdown.data.FolderPaths
import app.inkdown.data.NoteSummary
import app.inkdown.data.SortOrder
import app.inkdown.data.TagCount
import app.inkdown.export.ExportNote
import java.text.Collator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which notes the list shows. */
sealed interface Scope {
    data object All : Scope
    /** Notes directly in a folder ("" is the top level), with its subfolders listed first. */
    data class Folder(val path: String) : Scope
    data class Tag(val tag: String) : Scope
}

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repository

    val scope = MutableStateFlow<Scope>(Scope.All)
    val searching = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val folders: StateFlow<List<String>> = repo.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tags: StateFlow<List<TagCount>> = repo.tags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val trashCount: StateFlow<Int> = repo.trash().map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val all: StateFlow<List<NoteSummary>> = repo.summaries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Notes per folder, counting those in subfolders too. */
    val folderCounts: StateFlow<Map<String, Int>> = all.map { notes ->
        val counts = HashMap<String, Int>()
        for (n in notes) for (f in FolderPaths.withAncestors(n.folder)) counts[f] = (counts[f] ?: 0) + 1
        counts
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val totalCount: StateFlow<Int> = all.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val notes: StateFlow<List<NoteSummary>> = combine(scope, searching, query) { s, searching, q -> Triple(s, searching, q) }
        .flatMapLatest { (s, searching, q) ->
            when {
                searching -> repo.search(q)
                s is Scope.Folder -> all.map { list -> list.filter { it.folder == s.path } }
                s is Scope.Tag -> repo.tagged(s.tag)
                else -> all
            }
        }
        .combine(settings) { list, s -> sorted(list, s.sortOrder) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val subfolders: StateFlow<List<String>> = combine(scope, searching, folders) { s, searching, f ->
        if (!searching && s is Scope.Folder) f.filter { it.isNotEmpty() && FolderPaths.parent(it) == s.path } else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            container.listRequests.collect { request ->
                when (request) {
                    is ListRequest.ShowTag -> {
                        searching.value = false
                        scope.value = Scope.Tag(request.tag.lowercase())
                        container.listRequests.value = null
                    }
                    null -> {}
                }
            }
        }
    }

    private val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    private fun sorted(list: List<NoteSummary>, order: SortOrder): List<NoteSummary> {
        val byOrder: Comparator<NoteSummary> = when (order) {
            SortOrder.MODIFIED_NEWEST -> compareByDescending { it.updatedAt }
            SortOrder.MODIFIED_OLDEST -> compareBy { it.updatedAt }
            SortOrder.CREATED_NEWEST -> compareByDescending { it.createdAt }
            SortOrder.CREATED_OLDEST -> compareBy { it.createdAt }
            SortOrder.TITLE_AZ -> Comparator { a, b -> titleCompare(a, b) }
            SortOrder.TITLE_ZA -> Comparator { a, b -> titleCompare(b, a) }
        }
        return list.sortedWith(compareByDescending<NoteSummary> { it.pinned }.then(byOrder).thenByDescending { it.id })
    }

    /** Untitled notes sort after titled ones. */
    private fun titleCompare(a: NoteSummary, b: NoteSummary): Int = when {
        a.title.isBlank() && b.title.isBlank() -> 0
        a.title.isBlank() -> 1
        b.title.isBlank() -> -1
        else -> collator.compare(a.title, b.title)
    }

    fun setScope(s: Scope) {
        scope.value = s
        searching.value = false
        query.value = ""
        selection.value = emptySet()
    }

    fun startSearch() {
        searching.value = true
        selection.value = emptySet()
    }

    fun stopSearch() {
        searching.value = false
        query.value = ""
    }

    fun setSort(order: SortOrder) = viewModelScope.launch { container.settings.update { it.copy(sortOrder = order) } }

    /** Creates a note where the list is (its folder), and returns its id. */
    suspend fun createNote(): Long {
        val folder = (scope.value as? Scope.Folder)?.path ?: ""
        return repo.create(folder = folder)
    }

    fun toggleSelection(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        selection.value = notes.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun pinSelection() = viewModelScope.launch {
        val ids = selection.value
        val pin = notes.value.filter { it.id in ids }.any { !it.pinned }
        repo.setPinned(ids, pin)
        clearSelection()
    }

    fun moveSelection(folder: String) = viewModelScope.launch {
        val ids = selection.value
        repo.move(ids, folder)
        clearSelection()
        val n = ids.size
        container.listMessages.send(ListMessage("Moved $n ${if (n == 1) "note" else "notes"} to ${if (folder.isEmpty()) "the top level" else FolderPaths.name(folder)}"))
    }

    fun trashSelection() = viewModelScope.launch {
        val ids = selection.value
        repo.moveToTrash(ids)
        clearSelection()
        val n = ids.size
        container.listMessages.send(ListMessage("${if (n == 1) "Note" else "$n notes"} moved to trash", "Undo") { repo.restore(ids) })
    }

    fun createFolder(path: String) = viewModelScope.launch { repo.createFolder(path) }

    fun renameFolder(path: String, newName: String) = viewModelScope.launch {
        val target = FolderPaths.child(FolderPaths.parent(path), newName)
        repo.renameFolder(path, target)
        val s = scope.value
        if (s is Scope.Folder && FolderPaths.isWithin(s.path, path)) scope.value = Scope.Folder(target + s.path.substring(path.length))
    }

    fun deleteFolder(path: String) = viewModelScope.launch {
        val trashed = repo.deleteFolder(path)
        val s = scope.value
        if (s is Scope.Folder && FolderPaths.isWithin(s.path, path)) scope.value = Scope.Folder(FolderPaths.parent(path))
        container.listMessages.send(
            ListMessage(if (trashed == 0) "Folder deleted" else "Folder deleted; $trashed ${if (trashed == 1) "note" else "notes"} moved to trash"),
        )
    }

    suspend fun countNotesIn(path: String): Int = repo.countNotesIn(path)

    /** The notes [ids], or all notes in the current scope if null, ready for export. */
    suspend fun exportNotes(ids: Collection<Long>?): List<ExportNote> {
        val notes = if (ids != null) repo.notes(ids) else repo.allNotes()
        val wanted = if (ids != null) notes else when (val s = scope.value) {
            is Scope.Folder -> notes.filter { FolderPaths.isWithin(it.folder, s.path) || s.path.isEmpty() }
            is Scope.Tag -> {
                val tagged = this.notes.value.map { it.id }.toSet()
                notes.filter { it.id in tagged }
            }
            Scope.All -> notes
        }
        return wanted.sortedWith(compareBy({ it.folder.lowercase() }, { it.title.lowercase() })).map {
            ExportNote(it.title, it.body, it.folder, it.createdAt, it.updatedAt, repo.tagsOf(it.id))
        }
    }

    suspend fun allNotesForExport(): List<ExportNote> = repo.allNotes()
        .sortedWith(compareBy({ it.folder.lowercase() }, { it.title.lowercase() }))
        .map { ExportNote(it.title, it.body, it.folder, it.createdAt, it.updatedAt, repo.tagsOf(it.id)) }
}
