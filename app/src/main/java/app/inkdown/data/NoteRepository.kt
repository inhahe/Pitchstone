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

    /** Every note's title and folder, for linking. */
    fun linkTargets(): Flow<List<NoteTitle>> = dao.observeLinkTargets()

    fun observeNote(id: Long): Flow<NoteEntity?> = dao.observeNote(id)

    suspend fun note(id: Long): NoteEntity? = dao.note(id)

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
     * The note a wikilink [target] leads to, matched by [WikiLinks.key]: first a note whose
     * whole title is the target's name (titles may contain `/`), then, for `folder/note`, a
     * note named by the last part in that folder or one ending in that path (see
     * [WikiLinks.lookupKeys]). Among several, the one in [fromFolder] wins, then one at the top
     * level, then the most recently edited. A `.md` suffix is ignored. The editor marks links
     * to missing notes by the same rules (editor/src/editor/links.ts).
     */
    suspend fun resolveLink(target: String, fromFolder: String): NoteRef? {
        var name = target.substringBefore('#').trim()
        if (name.endsWith(".md", ignoreCase = true)) name = name.dropLast(3)
        if (name.isEmpty()) return null
        val refs = dao.allRefs()
        val prefer = compareBy<NoteRef>({ if (it.folder == fromFolder) 0 else if (it.folder.isEmpty()) 1 else 2 }, { -it.updatedAt })
        val whole = WikiLinks.key(name)
        refs.filter { WikiLinks.key(it.title) == whole }.minWithOrNull(prefer)?.let { return it }
        val path = WikiLinks.pathKey(name) ?: return null
        return refs.filter { path in WikiLinks.lookupKeys(it.title, it.folder) }.minWithOrNull(prefer)
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

    /**
     * Deletes the earlier version [revisionId] of note [noteId]. Returns the version deleted, so
     * that [undeleteRevision] can put it back, or null if the note has no such version.
     */
    suspend fun deleteRevision(noteId: Long, revisionId: Long): RevisionEntity? = db.withTransaction {
        val revision = dao.revision(revisionId)?.takeIf { it.noteId == noteId } ?: return@withTransaction null
        dao.deleteRevision(noteId, revisionId)
        revision
    }

    /** Puts back a version removed by [deleteRevision] (Undo), unless its note is gone. */
    suspend fun undeleteRevision(revision: RevisionEntity) {
        db.withTransaction {
            if (dao.note(revision.noteId) != null && dao.revision(revision.id) == null) dao.insertRevision(revision)
        }
    }

    /** Deletes every earlier version of note [noteId]; returns how many there were. */
    suspend fun deleteAllRevisions(noteId: Long): Int = dao.deleteRevisions(noteId)

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

    enum class SaveResult {
        SAVED,
        UNCHANGED,
        MISSING,
        /** The note's body was not the expected one (someone else saved it first). */
        CONFLICT,
    }

    /**
     * Saves a note's title and body. The version being replaced is kept as a revision when the
     * last revision is older than [REVISION_INTERVAL_MS], and always when the save empties the
     * note or removes more than half of it, so a deletion can be undone from the history.
     *
     * @param expectedBody if given, save only if the stored body is still this (compare and
     *   set, for changes computed from a body read earlier), else return [SaveResult.CONFLICT]
     */
    suspend fun save(id: Long, title: String, body: String, forceRevision: Boolean = false, expectedBody: String? = null): SaveResult {
        val index = indexOf(title, body)
        return db.withTransaction {
            val current = dao.note(id) ?: return@withTransaction SaveResult.MISSING
            if (expectedBody != null && current.body != expectedBody) return@withTransaction SaveResult.CONFLICT
            if (current.title == title && current.body == body) return@withTransaction SaveResult.UNCHANGED
            val now = clock()
            val hadContent = current.title.isNotEmpty() || current.body.isNotEmpty()
            val emptied = title.isBlank() && body.isBlank()
            val shrunk = body.length < current.body.length / 2
            val last = dao.lastRevisionTime(id)
            if (hadContent && (forceRevision || emptied || shrunk || last == null || now - last >= REVISION_INTERVAL_MS)) {
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
     * Whether a note was never written: its row is as created (never updated) and it has no
     * history. Only such a note may be discarded without going to the trash.
     */
    suspend fun isPristine(noteId: Long): Boolean {
        val note = dao.note(noteId) ?: return false
        return note.updatedAt == note.createdAt && dao.revisionCount(noteId) == 0
    }

    /**
     * Completes a rename of note [noteId]: points [[links]] to its old name (its
     * [NoteEntity.linkedTitle]) at its title, in every note, then records the title as linked.
     * Links are left alone when [updateLinks] is off, and while another note still has the old
     * name (they may mean that one). Each linking note is rewritten from the body it was read
     * with, so a save racing with this one is never overwritten. Returns how many notes changed.
     */
    suspend fun applyRename(noteId: Long, updateLinks: Boolean): Int {
        val note = dao.note(noteId) ?: return 0
        if (note.linkedTitle == note.title) return 0
        val oldTitle = note.linkedTitle
        val oldKey = WikiLinks.key(oldTitle)
        val newKey = WikiLinks.key(note.title)
        var changed = 0
        val othersHaveOldName = dao.allRefs().any { it.id != noteId && WikiLinks.key(it.title) == oldKey }
        if (updateLinks && oldKey.isNotEmpty() && newKey.isNotEmpty() && oldKey != newKey && !othersHaveOldName) {
            for (summary in dao.backlinks(listOf(oldKey), exclude = -1)) {
                for (attempt in 1..5) {
                    val linking = dao.note(summary.id) ?: break
                    val body = LinkRewriter.rename(linking.body, oldTitle, note.title) ?: break
                    when (save(linking.id, linking.title, body, expectedBody = linking.body)) {
                        SaveResult.SAVED -> {
                            changed++
                            break
                        }
                        SaveResult.CONFLICT -> continue
                        else -> break
                    }
                }
            }
        }
        dao.setLinkedTitle(noteId, note.title)
        return changed
    }

    /** Completes every rename still pending (after the app was stopped mid-way, say). */
    suspend fun applyPendingRenames(updateLinks: Boolean): Int =
        dao.pendingRenames().sumOf { applyRename(it.id, updateLinks) }

    // SQLite before 3.32 (Android 8 to 11) allows at most 999 variables in a statement.
    private suspend fun <T> inChunks(ids: Collection<Long>, action: suspend (List<Long>) -> T): List<T> =
        ids.distinct().chunked(500).map { action(it) }

    suspend fun notes(ids: Collection<Long>): List<NoteEntity> = inChunks(ids) { dao.notes(it) }.flatten()

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) {
        db.withTransaction { inChunks(ids) { dao.setPinned(it, pinned) } }
    }

    suspend fun move(ids: Collection<Long>, folder: String) {
        val path = FolderPaths.normalize(folder)
        db.withTransaction {
            if (path.isNotEmpty()) dao.insertFolder(FolderEntity(path, clock()))
            inChunks(ids) { dao.moveNotes(it, path) }
        }
    }

    suspend fun moveToTrash(ids: Collection<Long>) {
        val now = clock()
        db.withTransaction { inChunks(ids) { dao.setTrashed(it, now) } }
    }

    suspend fun restore(ids: Collection<Long>) {
        db.withTransaction { inChunks(ids) { dao.setTrashed(it, null) } }
    }

    suspend fun deleteForever(ids: Collection<Long>) {
        db.withTransaction { inChunks(ids) { dao.delete(it) } }
    }

    suspend fun emptyTrash() = deleteForever(dao.trashedIds())

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
        db.withTransaction {
            dao.renameNoteFolders(old, new)
            val paths = dao.folders().map { it.path }.filter { FolderPaths.isWithin(it, old) }
            dao.deleteFolderTree(old)
            val now = clock()
            for (p in paths) dao.insertFolder(FolderEntity(new + p.substring(old.length), now))
            dao.insertFolder(FolderEntity(new, now))
        }
    }

    /** Deletes a folder: its notes (and those of its subfolders) go to the trash. */
    suspend fun deleteFolder(path: String): Int {
        val p = FolderPaths.normalize(path)
        if (p.isEmpty()) return 0
        return db.withTransaction {
            val ids = dao.notesInFolderTree(p)
            moveToTrash(ids)
            dao.deleteFolderTree(p)
            ids.size
        }
    }

    suspend fun countNotesIn(path: String): Int = dao.notesInFolderTree(FolderPaths.normalize(path)).size
}
