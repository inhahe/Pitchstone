package app.pitchstone.sync

import android.util.Log
import app.pitchstone.data.AppDatabase
import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.FolderPaths
import app.pitchstone.data.NoteRepository
import app.pitchstone.export.DocumentDir
import app.pitchstone.export.FileNames
import app.pitchstone.export.Importer
import app.pitchstone.export.find
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonNames

/** What one sync did, for the status line and the messages after it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SyncReport(
    val at: Long,
    /** Notes written to the folder: new ones and changed ones. */
    val written: Int = 0,
    /** Notes changed from their files (or taken back out of the trash by them). */
    val updated: Int = 0,
    /** Notes made from new files. */
    val imported: Int = 0,
    /** Notes renamed or moved (either way). */
    val renamed: Int = 0,
    /** Notes moved to the app's trash because their files were deleted. */
    val trashedNotes: Int = 0,
    /** Files moved to the folder's `.trash` because their notes left the app. */
    val trashedFiles: Int = 0,
    /** Attachments (pictures, PDFs...) copied either way. Saved as "pictures" before they were all synced. */
    @JsonNames("pictures") val attachments: Int = 0,
    /**
     * Notes whose files were deleted from the folder but which were left alone, because so
     * many at once may be a mistake (a folder emptied, a card not mounted): see [GoneFiles].
     */
    val heldBack: Int = 0,
    /** Titles of the notes changed on both sides: the text not kept is in their version history. */
    val conflicts: List<String> = emptyList(),
    /** "path: why" for each file that could not be synced this time (it is tried again next time). */
    val problems: List<String> = emptyList(),
    /** The folders the sync wrote into, lower-cased ([Vault.touched]): for [FolderWatcher], not kept. */
    @Transient val wroteIn: Set<String> = emptySet(),
) {
    val changes: Int get() = written + updated + imported + renamed + trashedNotes + trashedFiles + attachments
}

/** What a first sync would do, for the user to agree to. */
data class SyncPreview(
    /** Notes only in the app: written to the folder. */
    val toFolder: Int,
    /** Files only in the folder: they become notes. */
    val fromFolder: Int,
    /** Notes with a file of their name in the folder. */
    val inBoth: Int,
    /** Of [inBoth], those whose texts differ: the newer is kept, the other goes to the note's history. */
    val differing: Int,
)

/** What to do about notes whose files were deleted from the folder, when there are many ([FolderSync.TRASH_LIMIT]). */
enum class GoneFiles {
    /** Leave them, and report them as [SyncReport.heldBack]. */
    ASK,
    /** Move them to the app's trash, as for a few. */
    TRASH_NOTES,
    /** Write their files again. */
    WRITE_AGAIN,
}

/**
 * Keeps a folder (an Obsidian vault, a Syncthing folder) and the app's notes in step: each
 * note is a markdown file there, `Folder/Title.md`, holding exactly the note's text, and the
 * attachments notes link to are files there too ([AttachmentSync]). [SyncPlanner] decides
 * what to do; this lists the folder ([Vault]), reads what must be read, and does it. One step
 * failing is reported and the rest go on; a failed step is tried again at the next sync,
 * since its pair is not updated.
 *
 * Folders left out of sync (`excluded`) are not looked into: their notes, on either side, stay
 * as they are. A note moved into one, in the app or in the folder, is moved on the other side
 * too, and from then on left alone.
 */
class FolderSync(
    db: AppDatabase,
    private val repo: NoteRepository,
    attachments: AttachmentStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.sync()
    private val attachmentSync = AttachmentSync(dao, repo, attachments)

    companion object {
        /** More notes than this losing their files in one sync are held back ([GoneFiles.ASK]). */
        const val TRASH_LIMIT = 5

        private const val TAG = "Pitchstone"
        private const val MIME = "application/octet-stream"

        /** "Title (2).md", "Title (3).md"... whichever is free in [dir], as a clash numbers a note's file. */
        private fun numbered(name: String, dir: DocumentDir): String =
            FileNames.unique(name.substringBeforeLast('.'), name.substringAfterLast('.', ""), dir.list().mapTo(HashSet()) { it.name.lowercase() })

        /** "name 1.ext", "name 2.ext"... whichever is free in both [a] and [b]. */
        private fun freeName(name: String, a: DocumentDir, b: DocumentDir): String {
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var n = 1
            while (true) {
                val candidate = "$base $n" + if (ext.isEmpty()) "" else ".$ext"
                if (a.find(candidate) == null && b.find(candidate) == null) return candidate
                n++
            }
        }
    }

    /** The folders below [root] (not hidden), for choosing which to leave out of sync. */
    suspend fun folders(root: DocumentDir): List<String> = withContext(Dispatchers.IO) { Vault.folders(root) }

    /**
     * The app's notes and the pairs remembered, as the planner sees them, without what is in the
     * folders left out: pairs there (forgotten by the sync), and notes there without a pair
     * elsewhere (one with a pair elsewhere was just moved in: its file follows it).
     */
    private class Snapshot(val notes: List<SyncNote>, val pairs: List<SyncPair>, val forgotten: List<SyncPair>, val all: List<SyncNote>)

    private suspend fun snapshot(excluded: Excluded): Snapshot {
        val entries = dao.all().filter { it.noteId != null }
        val live = repo.allNotes()
        val liveIds = live.mapTo(HashSet()) { it.id }
        val trashed = repo.notes(entries.mapNotNull { it.noteId }.filter { it !in liveIds })
        val notes = (live + trashed).map {
            SyncNote(
                id = it.id, title = it.title, folder = it.folder, hash = SyncHash.of(it.body), updatedAt = it.updatedAt,
                trashed = it.trashedAt != null, blank = it.title.isBlank() && it.body.isBlank(),
            )
        }
        val (forgotten, pairs) = entries.map { SyncPair(it.path, it.noteId!!, it.hash, it.modified, it.size) }.partition { excluded.contains(it.path) }
        val paired = pairs.mapTo(HashSet()) { it.noteId }
        val inSync = notes.filter { it.id in paired || !excluded.containsFolder(SyncPlanner.folderFor(it)) }
        return Snapshot(inSync, pairs, forgotten, notes)
    }

    /** The files' texts, each read once (BOM dropped, line ends made LF, as import does). */
    private class Texts(private val vault: Vault) {
        private val cache = HashMap<String, String>()

        /** Throws if the file cannot be read, or is too large for a note. */
        fun text(path: String): String = cache.getOrPut(path.lowercase()) {
            val f = vault.note(path) ?: error("it is gone")
            Importer.readText(f.dir.read(f.entry))
        }
    }

    /** The planner's view of a file's text: its hash, or one matching nothing if it cannot be read. */
    private fun hasher(texts: Texts, problems: MutableMap<String, String>?): (String) -> String = { path ->
        try {
            SyncHash.of(texts.text(path))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            problems?.putIfAbsent(path, describe(e))
            "unreadable:$path"
        }
    }

    /**
     * What a first sync with [root] would do (as if nothing were paired yet), without doing it,
     * leaving out the folders [excluded].
     */
    suspend fun preview(root: DocumentDir, excluded: Collection<String> = emptyList()): SyncPreview = withContext(Dispatchers.IO) {
        val vault = Vault.scan(root, excluded)
        val notes = snapshot(vault.excluded).all.filter { !it.trashed && !vault.excluded.containsFolder(SyncPlanner.folderFor(it)) }
        val plan = SyncPlanner.plan(vault.files, notes, emptyList(), hasher(Texts(vault), null))
        SyncPreview(
            toFolder = plan.count { it is SyncAction.WriteFile && it.create },
            fromFolder = plan.count { it is SyncAction.Import },
            inBoth = plan.count { it is SyncAction.Record || it is SyncAction.Conflict },
            differing = plan.count { it is SyncAction.Conflict },
        )
    }

    /** The note an action would change in the app, if any: those are put off while the note is open. */
    private fun SyncAction.changesNote(): Long? = when (this) {
        is SyncAction.UpdateNote -> noteId
        is SyncAction.Conflict -> noteId
        is SyncAction.Adopt -> noteId
        is SyncAction.TrashNote -> noteId
        is SyncAction.RestoreNote -> noteId
        else -> null
    }

    /**
     * Brings [root] and the app's notes in step, then the attachments notes link to.
     *
     * @param updateLinks a note renamed by renaming its file gets the [[links]] to it updated
     * @param excluded folders of [root] left out of sync ("Templates", "Archive/2020")
     * @param busy whether a note is open in an editor, asked as each step runs (one may be
     *   opened during a long sync): the sync does not change such a note (its file it may
     *   write), and catches up with it at a later sync
     * @param gone what to do when more than [TRASH_LIMIT] notes lost their files at once
     */
    suspend fun sync(
        root: DocumentDir,
        updateLinks: Boolean,
        excluded: Collection<String> = emptyList(),
        busy: (noteId: Long) -> Boolean = { false },
        gone: GoneFiles = GoneFiles.ASK,
    ): SyncReport = withContext(Dispatchers.IO) {
        val vault = Vault.scan(root, excluded)
        val texts = Texts(vault)
        val s = snapshot(vault.excluded)
        val planned = s.all.associateBy { it.id }
        val problems = LinkedHashMap<String, String>()
        val hash = hasher(texts, problems)
        fun allowed(a: SyncAction) = a.changesNote()?.let(busy) != true

        // Nothing listed while notes are paired: more likely a folder that cannot be read right
        // now (a card not there, a folder moved) than one emptied. Nothing is done, not even
        // forgetting pairs, until the user says which.
        val pairedLive = s.pairs.count { planned[it.noteId]?.trashed == false }
        // No markdown file anywhere, the folders left out included (where the last note may have
        // moved); hidden files do not count, a vault emptied keeping its .obsidian being as suspect.
        val noNotes by lazy { vault.notes.isEmpty() && vault.outside().isEmpty() }
        if (pairedLive > 0 && noNotes && gone == GoneFiles.ASK) {
            return@withContext SyncReport(at = clock(), heldBack = pairedLive)
        }

        fun plan(pairs: List<SyncPair>) = SyncPlanner.plan(vault.files, s.notes, pairs, hash, outside = vault::outside).filter(::allowed)
        // Pairs in folders now left out are forgotten: both sides stay as they are.
        val forget = s.forgotten.map { SyncAction.Forget(it.path) }
        var plan = forget + plan(s.pairs)
        val trashing = plan.filterIsInstance<SyncAction.TrashNote>()
        var heldBack = 0
        // (With an empty listing, only a choice the user made gets here.)
        if (trashing.size > TRASH_LIMIT || (trashing.isNotEmpty() && noNotes)) {
            when (gone) {
                GoneFiles.ASK -> {
                    heldBack = trashing.size
                    plan = plan.filter { it !is SyncAction.TrashNote }
                }
                GoneFiles.TRASH_NOTES -> {}
                GoneFiles.WRITE_AGAIN -> {
                    // Unpaired, those notes are written as new files.
                    val again = trashing.mapTo(HashSet()) { it.path.lowercase() }
                    plan = forget + trashing.map { SyncAction.Forget(it.path) } + plan(s.pairs.filter { it.path.lowercase() !in again })
                }
            }
        }

        var r = SyncReport(at = clock(), heldBack = heldBack)
        val conflicts = ArrayList<String>()
        val adopted = ArrayList<Long>()
        val trashDir by lazy { vault.root.subdirectory(".trash") }

        // The app's folders by the folder each is in the synced folder ("Q&A: misc" is "Q&A misc"),
        // so that a file moved or made there goes to the app's folder of that spelling.
        val appFolders = HashMap<String, String>()
        for (n in s.all) {
            if (!n.trashed) for (f in FolderPaths.withAncestors(n.folder)) appFolders.putIfAbsent(SyncPlanner.folderFor(f).lowercase(), f)
        }
        fun appFolder(path: String): String {
            val folder = path.substringBeforeLast('/', "")
            return appFolders[folder.lowercase()] ?: folder
        }

        fun located(path: String) = vault.note(path) ?: error("it is gone")

        /** Records that note [noteId] and the file at [path] (as it is now) both hold the text with [textHash]. */
        suspend fun remember(path: String, noteId: Long, textHash: String) {
            val f = located(path)
            dao.put(SyncEntryEntity(path = f.path, noteId = noteId, hash = textHash, modified = f.entry.lastModified, size = f.entry.size))
        }

        /** Writes [text] to the file at [path]: a new file if [create], else over the one listed (unless it changed since). */
        fun write(path: String, text: String, create: Boolean) {
            vault.touch(path.substringBeforeLast('/', ""))
            val bytes = text.toByteArray(Charsets.UTF_8)
            val existing = vault.note(path)
            if (!create && existing != null) {
                val now = existing.dir.refresh(existing.entry)
                if (now == null || now.lastModified != existing.entry.lastModified || now.size != existing.entry.size) {
                    error("it changed while syncing; it is synced next time")
                }
                existing.dir.overwrite(existing.entry, MIME).use { it.write(bytes) }
                // A provider that cannot empty a file makes a new one, with another handle.
                val fresh = existing.dir.refresh(existing.entry) ?: existing.dir.find(existing.entry.name) ?: error("it vanished")
                vault.put(Located(existing.path, existing.dir, fresh))
                return
            }
            val folder = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val dir = vault.dir(folder)
            val created = dir.createFile(name, MIME)
            if (!created.name.equals(name, ignoreCase = true)) {
                created.stream.close()
                runCatching { dir.delete(created.entry) }
                error("the name was taken meanwhile")
            }
            created.stream.use { it.write(bytes) }
            vault.put(Located(joinPath(folder, created.name), dir, dir.refresh(created.entry) ?: created.entry))
        }

        /** Moves the file at [from] to [to] (not taken); returns where it is now. */
        fun move(from: String, to: String): String {
            vault.touch(from.substringBeforeLast('/', ""))
            vault.touch(to.substringBeforeLast('/', ""))
            val f = located(from)
            val folder = to.substringBeforeLast('/', "")
            val name = to.substringAfterLast('/')
            var dir = f.dir
            var entry = f.entry
            if (!from.substringBeforeLast('/', "").equals(folder, ignoreCase = true)) {
                val target = vault.dir(folder)
                // Through a free name if the old one is taken there.
                if (target.find(entry.name) != null) entry = dir.rename(entry, freeName(entry.name, dir, target))
                entry = dir.moveTo(entry, target)
                dir = target
            }
            // Not for case alone: providers on case-blind storage would number the name instead.
            if (!entry.name.equals(name, ignoreCase = true)) {
                // A folder left out of sync was not listed: a file there may have the name.
                val free = if (vault.excluded.contains(to) && dir.find(name) != null) numbered(name, dir) else name
                if (!entry.name.equals(free, ignoreCase = true)) entry = dir.rename(entry, free)
            }
            vault.remove(from)
            val actual = joinPath(folder, entry.name)
            vault.put(Located(actual, dir, entry))
            return actual
        }

        /** Moves the file at [path] to the folder's `.trash`, as Obsidian does (numbered if its name is taken there). */
        fun trash(path: String) {
            vault.touch(path.substringBeforeLast('/', ""))
            val f = located(path)
            var entry = f.entry
            if (trashDir.find(entry.name) != null) entry = f.dir.rename(entry, freeName(entry.name, f.dir, trashDir))
            f.dir.moveTo(entry, trashDir)
            vault.remove(path)
        }

        val pairs = s.pairs.associateBy { it.path.lowercase() }

        /**
         * Moves note [noteId]'s file from [from] to [to] and the pair with it, so that a step
         * failing after the move leaves the pair where the file is. Returns where it is now.
         */
        suspend fun moveFile(noteId: Long, from: String, to: String): String {
            val path = move(from, to)
            dao.remove(from)
            pairs[from.lowercase()]?.let { remember(path, noteId, it.hash) }
            return path
        }

        /** Whether note [id] still has the text the plan was made from (else it was edited meanwhile: the next sync decides). */
        fun unchanged(body: String, id: Long) = SyncHash.of(body) == planned[id]?.hash

        for (action in plan) {
            // Opened since the plan was made: left for a later sync.
            if (!allowed(action)) continue
            try {
                when (action) {
                    is SyncAction.Record -> {
                        val note = repo.note(action.noteId) ?: continue
                        if (!unchanged(note.body, note.id)) continue
                        remember(action.path, note.id, SyncHash.of(note.body))
                    }
                    is SyncAction.WriteFile -> {
                        val note = repo.note(action.noteId) ?: continue
                        val path = action.movedFrom?.let { moveFile(note.id, it, action.path) } ?: action.path
                        write(path, note.body, action.create)
                        remember(path, note.id, SyncHash.of(note.body))
                        r = r.copy(written = r.written + 1)
                    }
                    is SyncAction.MoveFile -> {
                        val note = repo.note(action.noteId) ?: continue
                        moveFile(note.id, action.from, action.to)
                        r = r.copy(renamed = r.renamed + 1)
                    }
                    is SyncAction.UpdateNote -> {
                        val note = repo.note(action.noteId) ?: continue
                        if (!unchanged(note.body, note.id)) continue
                        val text = texts.text(action.path)
                        if (repo.save(note.id, note.title, text, expectedBody = note.body) == NoteRepository.SaveResult.CONFLICT) continue
                        val path = action.movedTo?.let { moveFile(note.id, action.path, it) } ?: action.path
                        remember(path, note.id, SyncHash.of(text))
                        r = r.copy(updated = r.updated + 1)
                    }
                    is SyncAction.Conflict -> {
                        val note = repo.note(action.noteId) ?: continue
                        if (!unchanged(note.body, note.id)) continue
                        val text = texts.text(action.path)
                        if (action.keepFile) {
                            // The app's text becomes a version.
                            val saved = repo.save(note.id, note.title, text, forceRevision = true, expectedBody = note.body)
                            if (saved == NoteRepository.SaveResult.CONFLICT) continue
                            remember(action.path, note.id, SyncHash.of(text))
                        } else {
                            // The version first: a file overwritten before it would be lost.
                            repo.keepVersion(note.id, note.title, text)
                            write(action.path, note.body, create = false)
                            remember(action.path, note.id, SyncHash.of(note.body))
                        }
                        conflicts += note.title.ifBlank { "Untitled" }
                    }
                    is SyncAction.Adopt -> {
                        val note = repo.note(action.noteId) ?: continue
                        if (!unchanged(note.body, note.id)) continue
                        val name = action.to.substringAfterLast('/')
                        // The note keeps a title the name still suits (a character files cannot
                        // hold, a clash's number), and a folder the file is still in.
                        val title = if (SyncPlanner.fitsName(name, FileNames.safe(note.title))) note.title else name.dropLast(3)
                        val folder = action.to.substringBeforeLast('/', "")
                        val keepFolder = SyncPlanner.fitsFolder(folder, SyncPlanner.folderFor(note.folder))
                        repo.retitle(note.id, title, if (keepFolder) note.folder else appFolder(action.to))
                        if (action.writeNote) write(action.to, note.body, create = false)
                        dao.remove(action.from)
                        remember(action.to, note.id, if (action.writeNote) SyncHash.of(note.body) else SyncHash.of(texts.text(action.to)))
                        adopted += note.id
                        r = r.copy(renamed = r.renamed + 1)
                    }
                    is SyncAction.Import -> {
                        val f = located(action.path)
                        val text = texts.text(action.path)
                        val id = repo.import(
                            title = f.entry.name.dropLast(3), body = text, folder = appFolder(action.path),
                            createdAt = f.entry.lastModified, updatedAt = f.entry.lastModified,
                        )
                        remember(action.path, id, SyncHash.of(text))
                        r = r.copy(imported = r.imported + 1)
                    }
                    is SyncAction.TrashNote -> {
                        val note = repo.note(action.noteId) ?: continue
                        if (!unchanged(note.body, note.id)) continue
                        repo.moveToTrash(listOf(note.id))
                        dao.remove(action.path)
                        r = r.copy(trashedNotes = r.trashedNotes + 1)
                    }
                    is SyncAction.TrashFile -> {
                        trash(action.path)
                        dao.remove(action.path)
                        r = r.copy(trashedFiles = r.trashedFiles + 1)
                    }
                    is SyncAction.RestoreNote -> {
                        val note = repo.note(action.noteId) ?: continue
                        val text = texts.text(action.path)
                        repo.restore(listOf(note.id))
                        repo.save(note.id, note.title, text)
                        remember(action.path, note.id, SyncHash.of(text))
                        r = r.copy(updated = r.updated + 1)
                    }
                    is SyncAction.Forget -> dao.remove(action.path)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Sync step $action failed", e)
                problems.putIfAbsent(action.path(), describe(e))
            }
        }
        for (id in adopted) {
            try {
                repo.applyRename(id, updateLinks)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Left pending: the next start completes it.
                Log.w(TAG, "Updating links to note $id failed", e)
            }
        }

        val copied = try {
            attachmentSync.sync(vault, problems)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Syncing attachments failed", e)
            problems.putIfAbsent("Attachments", describe(e))
            0
        }
        r.copy(attachments = copied, conflicts = conflicts, problems = problems.map { (path, why) -> "$path: $why" }, wroteIn = vault.touched)
    }

    private fun SyncAction.path(): String = when (this) {
        is SyncAction.Record -> path
        is SyncAction.WriteFile -> path
        is SyncAction.MoveFile -> to
        is SyncAction.UpdateNote -> path
        is SyncAction.Conflict -> path
        is SyncAction.Adopt -> to
        is SyncAction.Import -> path
        is SyncAction.TrashNote -> path
        is SyncAction.TrashFile -> path
        is SyncAction.RestoreNote -> path
        is SyncAction.Forget -> path
    }
}
