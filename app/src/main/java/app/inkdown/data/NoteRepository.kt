package app.inkdown.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.inkdown.markdown.NoteIndex
import app.inkdown.markdown.NoteIndexer
import app.inkdown.markdown.LinkRewriter
import app.inkdown.markdown.WikiLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Folder paths: slash-separated, no leading or trailing slash, "" for the top level. */
object FolderPaths {
    fun normalize(path: String): String =
        path.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    fun child(parent: String, name: String): String = normalize(if (parent.isEmpty()) name else "$parent/$name")

    /** The path and every folder above it. */
    fun withAncestors(path: String): List<String> {
        val parts = normalize(path).split('/').filter { it.isNotEmpty() }
        return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
    }

    fun isWithin(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")

    /** A folder name: no slashes, trimmed. */
    fun cleanName(name: String): String = name.replace('/', ' ').replace('\\', ' ').trim()
}

/** Escapes LIKE wildcards (with `\` as the escape character). */
internal fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

class NoteRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.notes()

    companion object {
        /** While a note is edited, its previous version is kept at most this often. */
        const val REVISION_INTERVAL_MS = 10 * 60 * 1000L
        const val MAX_REVISIONS_PER_NOTE = 60
        const val REVISION_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    // ---- Reading ------------------------------------------------------------------------------

    fun summaries(): Flow<List<NoteSummary>> = dao.observeSummaries()

    fun trash(): Flow<List<NoteSummary>> = dao.observeTrash()

    fun tags(): Flow<List<TagCount>> = dao.observeTags()

    /** Notes tagged [tag] or a nested tag under it (`#a` includes `#a/b`). */
    fun tagged(tag: String): Flow<List<NoteSummary>> {
        val key = tag.lowercase()
        return dao.observeTagged(key, escapeLike(key) + "/%")
    }

    fun titles(): Flow<List<String>> = dao.observeTitles()

    fun observeNote(id: Long): Flow<NoteEntity?> = dao.observeNote(id)

    suspend fun note(id: Long): NoteEntity? = dao.note(id)

    suspend fun notes(ids: Collection<Long>): List<NoteEntity> = dao.notes(ids)

    suspend fun allNotes(): List<NoteEntity> = dao.allNotes()

    suspend fun tagsOf(noteId: Long): List<String> = dao.tagsOf(noteId)

    /**
     * Notes containing every word of [query] (case-insensitive substrings of title or text).
     * An empty query matches nothing.
     */
    fun search(query: String): Flow<List<NoteSummary>> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return flowOf(emptyList())
        val where = terms.joinToString(" AND ") { "searchText LIKE ? ESCAPE '\\'" }
        val sql = "SELECT id, title, folder, createdAt, updatedAt, pinned, trashedAt, snippet FROM notes " +
            "WHERE trashedAt IS NULL AND $where"
        return dao.search(SimpleSQLiteQuery(sql, terms.map { "%${escapeLike(it)}%" }.toTypedArray()))
    }

    /** Every folder: created ones, those holding notes, and the folders above them. */
    fun folders(): Flow<List<String>> = combine(dao.observeFolders(), dao.observeSummaries()) { folders, notes ->
        (folders.map { it.path } + notes.map { it.folder })
            .flatMap { FolderPaths.withAncestors(it) }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /**
     * The note a wikilink [target] leads to: matched by name (as [WikiLinks.key]), restricted
     * to a folder when the target names one (`folder/note`). Among several, the one in
     * [fromFolder] wins, then one at the top level, then the most recently edited.
     */
    suspend fun resolveLink(target: String, fromFolder: String): NoteRef? {
        val name = target.substringBefore('#').trim().removeSuffix(".md")
        if (name.isEmpty()) return null
        val folder = FolderPaths.normalize(name.substringBeforeLast('/', ""))
        val key = WikiLinks.key(name.substringAfterLast('/'))
        val candidates = dao.allRefs().filter { ref ->
            WikiLinks.key(ref.title) == key &&
                (folder.isEmpty() || ref.folder.equals(folder, ignoreCase = true) || ref.folder.endsWith("/$folder", ignoreCase = true))
        }
        return candidates.minWithOrNull(
            compareBy<NoteRef>({ if (it.folder == fromFolder) 0 else if (it.folder.isEmpty()) 1 else 2 }, { -it.updatedAt }),
        )
    }

    /** Notes that link to the note [noteId]. */
    suspend fun backlinks(noteId: Long): List<NoteSummary> {
        val note = dao.note(noteId) ?: return emptyList()
        val key = WikiLinks.key(note.title)
        if (key.isEmpty()) return emptyList()
        return dao.backlinks(listOf(key), noteId)
    }

    fun revisions(noteId: Long): Flow<List<RevisionSummary>> = dao.observeRevisions(noteId)

    suspend fun revision(id: Long): RevisionEntity? = dao.revision(id)

    // ---- Writing ------------------------------------------------------------------------------

    private suspend fun indexOf(title: String, body: String): NoteIndex =
        withContext(Dispatchers.Default) { NoteIndexer.index(title, body) }

    private suspend fun writeIndex(noteId: Long, index: NoteIndex) {
        dao.replaceIndex(
            noteId,
            index.tags.map { (key, display) -> NoteTagEntity(noteId, key, display) },
            index.links.map { NoteLinkEntity(noteId, it) },
        )
    }

    suspend fun create(title: String = "", body: String = "", folder: String = ""): Long {
        val index = indexOf(title, body)
        val now = clock()
        val path = FolderPaths.normalize(folder)
        return db.withTransaction {
            if (path.isNotEmpty()) dao.insertFolder(FolderEntity(path, now))
            val id = dao.insert(
                NoteEntity(
                    title = title, body = body, folder = path, createdAt = now, updatedAt = now,
                    searchText = index.searchText, snippet = index.snippet,
                ),
            )
            writeIndex(id, index)
            id
        }
    }

    /** Adds a note from outside (import), keeping its dates when known. */
    suspend fun import(title: String, body: String, folder: String, createdAt: Long?, updatedAt: Long?): Long {
        val index = indexOf(title, body)
        val now = clock()
        val path = FolderPaths.normalize(folder)
        return db.withTransaction {
            if (path.isNotEmpty()) dao.insertFolder(FolderEntity(path, now))
            val id = dao.insert(
                NoteEntity(
                    title = title, body = body, folder = path,
                    createdAt = createdAt ?: updatedAt ?: now, updatedAt = updatedAt ?: now,
                    searchText = index.searchText, snippet = index.snippet,
                ),
            )
            writeIndex(id, index)
            id
        }
    }

    enum class SaveResult { SAVED, UNCHANGED, MISSING }

    /**
     * Saves a note's title and body. The version being replaced is kept as a revision when the
     * last revision is older than [REVISION_INTERVAL_MS], so edits can be undone later.
     */
    suspend fun save(id: Long, title: String, body: String, forceRevision: Boolean = false): SaveResult {
        val index = indexOf(title, body)
        return db.withTransaction {
            val current = dao.note(id) ?: return@withTransaction SaveResult.MISSING
            if (current.title == title && current.body == body) return@withTransaction SaveResult.UNCHANGED
            val now = clock()
            val hadContent = current.title.isNotEmpty() || current.body.isNotEmpty()
            val last = dao.lastRevisionTime(id)
            if (hadContent && (forceRevision || last == null || now - last >= REVISION_INTERVAL_MS)) {
                dao.insertRevision(RevisionEntity(noteId = id, title = current.title, body = current.body, savedAt = now))
                dao.pruneRevisions(id, now - REVISION_RETENTION_MS, keep = 10)
                dao.capRevisions(id, MAX_REVISIONS_PER_NOTE)
            }
            dao.updateContent(id, title, body, now, index.searchText, index.snippet)
            writeIndex(id, index)
            SaveResult.SAVED
        }
    }

    /** Replaces a note's content with an earlier version; the current one becomes a revision. */
    suspend fun restoreRevision(noteId: Long, revisionId: Long): Boolean {
        val rev = dao.revision(revisionId) ?: return false
        if (rev.noteId != noteId) return false
        return save(noteId, rev.title, rev.body, forceRevision = true) != SaveResult.MISSING
    }

    /**
     * After a note is renamed, points the links to its old name at its new name, in every
     * note. Does nothing while another note still has the old name (the links may mean that
     * one). Returns how many notes changed.
     */
    suspend fun propagateRename(noteId: Long, oldTitle: String, newTitle: String): Int {
        val oldKey = WikiLinks.key(oldTitle)
        if (oldKey.isEmpty() || oldKey == WikiLinks.key(newTitle) || WikiLinks.key(newTitle).isEmpty()) return 0
        if (dao.allRefs().any { it.id != noteId && WikiLinks.key(it.title) == oldKey }) return 0
        var changed = 0
        for (summary in dao.backlinks(listOf(oldKey), exclude = -1)) {
            val note = dao.note(summary.id) ?: continue
            val body = LinkRewriter.rename(note.body, oldTitle, newTitle) ?: continue
            if (save(note.id, note.title, body) == SaveResult.SAVED) changed++
        }
        return changed
    }

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) = dao.setPinned(ids, pinned)

    suspend fun move(ids: Collection<Long>, folder: String) {
        val path = FolderPaths.normalize(folder)
        db.withTransaction {
            if (path.isNotEmpty()) dao.insertFolder(FolderEntity(path, clock()))
            dao.moveNotes(ids, path)
        }
    }

    suspend fun moveToTrash(ids: Collection<Long>) = dao.setTrashed(ids, clock())

    suspend fun restore(ids: Collection<Long>) = dao.setTrashed(ids, null)

    suspend fun deleteForever(ids: Collection<Long>) = dao.delete(ids)

    suspend fun emptyTrash() = dao.delete(dao.trashedIds())

    /** Deletes notes that have been in the trash longer than [TRASH_RETENTION_MS]. */
    suspend fun purgeOldTrash(): Int = dao.purgeTrash(clock() - TRASH_RETENTION_MS)

    suspend fun createFolder(path: String) {
        val p = FolderPaths.normalize(path)
        if (p.isNotEmpty()) dao.insertFolder(FolderEntity(p, clock()))
    }

    /** Renames or moves a folder with everything in it. */
    suspend fun renameFolder(from: String, to: String) {
        val old = FolderPaths.normalize(from)
        val new = FolderPaths.normalize(to)
        if (old.isEmpty() || new.isEmpty() || old == new || FolderPaths.isWithin(new, old)) return
        val prefix = escapeLike(old) + "/%"
        db.withTransaction {
            dao.renameNoteFolders(old, new, old.length, prefix)
            val paths = dao.folders().map { it.path }.filter { FolderPaths.isWithin(it, old) }
            dao.deleteFolderTree(old, prefix)
            val now = clock()
            for (p in paths) dao.insertFolder(FolderEntity(new + p.substring(old.length), now))
            dao.insertFolder(FolderEntity(new, now))
        }
    }

    /** Deletes a folder: its notes (and those of its subfolders) go to the trash. */
    suspend fun deleteFolder(path: String): Int {
        val p = FolderPaths.normalize(path)
        if (p.isEmpty()) return 0
        val prefix = escapeLike(p) + "/%"
        return db.withTransaction {
            val ids = dao.notesInFolderTree(p, prefix)
            if (ids.isNotEmpty()) dao.setTrashed(ids, clock())
            dao.deleteFolderTree(p, prefix)
            ids.size
        }
    }

    suspend fun countNotesIn(path: String): Int {
        val p = FolderPaths.normalize(path)
        return dao.notesInFolderTree(p, escapeLike(p) + "/%").size
    }
}
