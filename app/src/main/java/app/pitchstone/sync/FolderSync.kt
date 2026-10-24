package app.pitchstone.sync

import android.util.Log
import app.pitchstone.data.AppDatabase
import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.NoteRepository
import app.pitchstone.export.DocEntry
import app.pitchstone.export.DocumentDir
import app.pitchstone.export.Importer
import app.pitchstone.export.find
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What one sync did, for the status line and the messages after it. */
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
    /** Pictures copied either way. */
    val pictures: Int = 0,
    /**
     * Notes whose files were deleted from the folder but which were left alone, because so
     * many at once may be a mistake (a folder emptied, a card not mounted): see [GoneFiles].
     */
    val heldBack: Int = 0,
    /** Titles of the notes changed on both sides: the text not kept is in their version history. */
    val conflicts: List<String> = emptyList(),
    /** "path: why" for each file that could not be synced this time (it is tried again next time). */
    val problems: List<String> = emptyList(),
) {
    val changes: Int get() = written + updated + imported + renamed + trashedNotes + trashedFiles + pictures
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
 * pictures notes show are files there too. [SyncPlanner] decides what to do; this lists the
 * folder, reads what must be read, and does it. One step failing is reported and the rest go
 * on; a failed step is tried again at the next sync, since its pair is not updated.
 */
class FolderSync(
    db: AppDatabase,
    private val repo: NoteRepository,
    private val attachments: AttachmentStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.sync()

    companion object {
        /** More notes than this losing their files in one sync are held back ([GoneFiles.ASK]). */
        const val TRASH_LIMIT = 5

        private const val TAG = "Pitchstone"
        private const val MIME = "application/octet-stream"

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

        private fun join(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

        private fun why(e: Exception) = e.message ?: e.javaClass.simpleName
    }

    /** A file in the folder, where it is. */
    private class Located(val path: String, val dir: DocumentDir, val entry: DocEntry)

    /** The folder as listed: markdown files by lower-cased path, pictures by lower-cased name. */
    private class Vault(val root: DocumentDir) {
        val notes = HashMap<String, Located>()
        val pictures = HashMap<String, MutableList<Located>>()
        private val dirs = HashMap<String, DocumentDir>()

        fun found(path: String, dir: DocumentDir) {
            dirs[path.lowercase()] = dir
        }

        /** The folder at [path] ("" for the top), created if missing. */
        fun dir(path: String): DocumentDir = dirs[path.lowercase()] ?: run {
            val parent = if (path.contains('/')) dir(path.substringBeforeLast('/')) else root
            parent.subdirectory(path.substringAfterLast('/')).also { dirs[path.lowercase()] = it }
        }

        val files: List<VaultFile> get() = notes.values.map { VaultFile(it.path, it.entry.lastModified, it.entry.size) }
    }

    private fun scan(root: DocumentDir): Vault {
        val vault = Vault(root)
        vault.found("", root)
        fun walk(dir: DocumentDir, path: String) {
            for (entry in dir.list()) {
                // Hidden: .obsidian, .trash, .git, .stfolder (Syncthing) and the like.
                if (entry.name.startsWith(".")) continue
                val child = join(path, entry.name)
                when {
                    entry.isDirectory -> {
                        val sub = dir.openDirectory(entry)
                        vault.found(child, sub)
                        walk(sub, child)
                    }
                    entry.name.endsWith(".md", ignoreCase = true) -> vault.notes[child.lowercase()] = Located(child, dir, entry)
                    AttachmentStore.isImage(entry.name) -> vault.pictures.getOrPut(entry.name.lowercase()) { ArrayList() } += Located(child, dir, entry)
                }
            }
        }
        walk(root, "")
        return vault
    }

    private class Snapshot(val notes: List<SyncNote>, val pairs: List<SyncPair>)

    private suspend fun snapshot(): Snapshot {
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
        return Snapshot(notes, entries.map { SyncPair(it.path, it.noteId!!, it.hash, it.modified, it.size) })
    }

    /** The files' texts, each read once (BOM dropped, line ends made LF, as import does). */
    private class Texts(private val vault: Vault) {
        private val cache = HashMap<String, String>()

        /** Throws if the file cannot be read, or is too large for a note. */
        fun text(path: String): String = cache.getOrPut(path.lowercase()) {
            val f = vault.notes[path.lowercase()] ?: error("it is gone")
            Importer.readText(f.dir.read(f.entry))
        }
    }

    /** The planner's view of a file's text: its hash, or one matching nothing if it cannot be read. */
    private fun hasher(texts: Texts, problems: MutableMap<String, String>?): (String) -> String = { path ->
        try {
            SyncHash.of(texts.text(path))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            problems?.putIfAbsent(path, why(e))
            "unreadable:$path"
        }
    }

    /** What a first sync with [root] would do (as if nothing were paired yet), without doing it. */
    suspend fun preview(root: DocumentDir): SyncPreview = withContext(Dispatchers.IO) {
        val vault = scan(root)
        val notes = snapshot().notes.filter { !it.trashed }
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
     * Brings [root] and the app's notes in step, then the pictures notes show.
     *
     * @param updateLinks a note renamed by renaming its file gets the [[links]] to it updated
     * @param busy notes open in an editor: the sync does not change them (their files it may
     *   write), and catches up with them at a later sync
     * @param gone what to do when more than [TRASH_LIMIT] notes lost their files at once
     */
    suspend fun sync(
        root: DocumentDir,
        updateLinks: Boolean,
        busy: Set<Long> = emptySet(),
        gone: GoneFiles = GoneFiles.ASK,
    ): SyncReport = withContext(Dispatchers.IO) {
        val vault = scan(root)
        val texts = Texts(vault)
        val s = snapshot()
        val planned = s.notes.associateBy { it.id }
        val problems = LinkedHashMap<String, String>()
        val hash = hasher(texts, problems)
        fun allowed(a: SyncAction) = a.changesNote()?.let { it in busy } != true

        var plan = SyncPlanner.plan(vault.files, s.notes, s.pairs, hash).filter(::allowed)
        val trashing = plan.filterIsInstance<SyncAction.TrashNote>()
        var heldBack = 0
        // An empty listing with notes paired is more likely a folder that cannot be read right now.
        if (trashing.size > TRASH_LIMIT || (trashing.isNotEmpty() && vault.notes.isEmpty())) {
            when (gone) {
                GoneFiles.ASK -> {
                    heldBack = trashing.size
                    plan = plan.filter { it !is SyncAction.TrashNote }
                }
                GoneFiles.TRASH_NOTES -> {}
                GoneFiles.WRITE_AGAIN -> {
                    // Unpaired, those notes are written as new files.
                    val forget = trashing.mapTo(HashSet()) { it.path.lowercase() }
                    plan = trashing.map { SyncAction.Forget(it.path) } +
                        SyncPlanner.plan(vault.files, s.notes, s.pairs.filter { it.path.lowercase() !in forget }, hash).filter(::allowed)
                }
            }
        }

        var r = SyncReport(at = clock(), heldBack = heldBack)
        val conflicts = ArrayList<String>()
        val adopted = ArrayList<Long>()
        val trashDir by lazy { vault.root.subdirectory(".trash") }

        fun located(path: String) = vault.notes[path.lowercase()] ?: error("it is gone")

        /** Records that note [noteId] and the file at [path] (as it is now) both hold the text with [textHash]. */
        suspend fun remember(path: String, noteId: Long, textHash: String) {
            val f = located(path)
            dao.put(SyncEntryEntity(path = f.path, noteId = noteId, hash = textHash, modified = f.entry.lastModified, size = f.entry.size))
        }

        /** Writes [text] to the file at [path]: a new file if [create], else over the one listed (unless it changed since). */
        fun write(path: String, text: String, create: Boolean) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val existing = vault.notes[path.lowercase()]
            if (!create && existing != null) {
                val now = existing.dir.refresh(existing.entry)
                if (now == null || now.lastModified != existing.entry.lastModified || now.size != existing.entry.size) {
                    error("it changed while syncing; it is synced next time")
                }
                existing.dir.overwrite(existing.entry, MIME).use { it.write(bytes) }
                // A provider that cannot empty a file makes a new one, with another handle.
                val fresh = existing.dir.refresh(existing.entry) ?: existing.dir.find(existing.entry.name) ?: error("it vanished")
                vault.notes[path.lowercase()] = Located(existing.path, existing.dir, fresh)
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
            val actual = join(folder, created.name)
            vault.notes[actual.lowercase()] = Located(actual, dir, dir.refresh(created.entry) ?: created.entry)
        }

        /** Moves the file at [from] to [to] (not taken); returns where it is now. */
        fun move(from: String, to: String): String {
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
            if (!entry.name.equals(name, ignoreCase = true)) entry = dir.rename(entry, name)
            vault.notes.remove(from.lowercase())
            val actual = join(folder, entry.name)
            vault.notes[actual.lowercase()] = Located(actual, dir, entry)
            return actual
        }

        /** Moves the file at [path] to the folder's `.trash`, as Obsidian does (numbered if its name is taken there). */
        fun trash(path: String) {
            val f = located(path)
            var entry = f.entry
            if (trashDir.find(entry.name) != null) entry = f.dir.rename(entry, freeName(entry.name, f.dir, trashDir))
            f.dir.moveTo(entry, trashDir)
            vault.notes.remove(path.lowercase())
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
                            write(action.path, note.body, create = false)
                            repo.keepVersion(note.id, note.title, text)
                            remember(action.path, note.id, SyncHash.of(note.body))
                        }
                        conflicts += note.title.ifBlank { "Untitled" }
                    }
                    is SyncAction.Adopt -> {
                        val note = repo.note(action.noteId) ?: continue
                        if (!unchanged(note.body, note.id)) continue
                        val name = action.to.substringAfterLast('/')
                        repo.retitle(note.id, name.dropLast(3), action.to.substringBeforeLast('/', ""))
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
                            title = f.entry.name.dropLast(3), body = text, folder = action.path.substringBeforeLast('/', ""),
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
                problems.putIfAbsent(action.path(), why(e))
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

        val pictures = try {
            syncPictures(vault, problems)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Syncing pictures failed", e)
            problems.putIfAbsent("Pictures", why(e))
            0
        }
        r.copy(pictures = pictures, conflicts = conflicts, problems = problems.map { (path, why) -> "$path: $why" })
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

    /**
     * Makes the pictures notes show the same in both places: one only in the folder is stored
     * in the app, one only in the app is written to the folder (where the vault's Obsidian
     * settings put attachments), and one changed in the folder since the last sync replaces the
     * app's copy. Two different pictures of one name meeting for the first time are both left
     * as they are, and reported. Pictures are never deleted by a sync. Returns how many were copied.
     */
    private suspend fun syncPictures(vault: Vault, problems: MutableMap<String, String>): Int {
        val entries = dao.all().filter { it.attachment != null }.associateBy { it.path.lowercase() }
        // Picture name -> the folder of a note showing it.
        val shown = LinkedHashMap<String, String>()
        for (note in repo.allNotes()) {
            for (name in AttachmentStore.mentionedNames(note.body)) if (AttachmentStore.isImage(name)) shown.putIfAbsent(name, note.folder)
        }
        val folderSetting by lazy { attachmentFolderSetting(vault.root) }
        var copied = 0
        for ((name, noteFolder) in shown) {
            val inVault = vault.pictures[name]?.firstOrNull()
            try {
                val stored = attachments.file(name)
                when {
                    inVault != null && stored != null -> {
                        val e = inVault.entry
                        val entry = entries[inVault.path.lowercase()]
                        if (entry != null && entry.modified == e.lastModified && entry.size == e.size) continue
                        if (e.size > AttachmentStore.MAX_BYTES) error("larger than a picture can be")
                        val bytes = inVault.dir.read(e).use { it.readBytes() }
                        val hash = SyncHash.of(bytes)
                        if (hash != SyncHash.of(stored.readBytes())) {
                            if (entry != null && hash != entry.hash) {
                                attachments.replace(stored.name, bytes.inputStream())
                                copied++
                            } else if (entry == null) {
                                problems.putIfAbsent(inVault.path, "a different picture of this name is in the app; neither was changed")
                            }
                        }
                        dao.put(SyncEntryEntity(inVault.path, attachment = stored.name, hash = hash, modified = e.lastModified, size = e.size))
                    }
                    inVault != null -> {
                        val e = inVault.entry
                        if (e.size > AttachmentStore.MAX_BYTES) error("larger than a picture can be")
                        val bytes = inVault.dir.read(e).use { it.readBytes() }
                        val storedAs = attachments.addOrReuse(bytes.inputStream(), e.name)
                        if (!storedAs.equals(e.name, ignoreCase = true)) {
                            problems.putIfAbsent(inVault.path, "stored as $storedAs, another picture in the app having this name")
                        }
                        dao.put(SyncEntryEntity(inVault.path, attachment = storedAs, hash = SyncHash.of(bytes), modified = e.lastModified, size = e.size))
                        copied++
                    }
                    stored != null -> {
                        val folder = attachmentFolder(folderSetting, noteFolder)
                        val dir = vault.dir(folder)
                        val created = dir.createFile(stored.name, MIME)
                        if (!created.name.equals(stored.name, ignoreCase = true)) {
                            created.stream.close()
                            runCatching { dir.delete(created.entry) }
                            error("the name is taken in ${folder.ifEmpty { "the folder" }}")
                        }
                        created.stream.use { out -> stored.inputStream().use { it.copyTo(out) } }
                        val written = dir.refresh(created.entry) ?: created.entry
                        dao.put(
                            SyncEntryEntity(
                                join(folder, created.name), attachment = stored.name, hash = SyncHash.of(stored.readBytes()),
                                modified = written.lastModified, size = written.size,
                            ),
                        )
                        copied++
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Syncing the picture $name failed", e)
                problems.putIfAbsent(inVault?.path ?: name, why(e))
            }
        }
        return copied
    }

    /** Obsidian's "Default location for new attachments" in the vault [root] (`.obsidian/app.json`), or null. */
    private fun attachmentFolderSetting(root: DocumentDir): String? = try {
        val obsidian = root.find(".obsidian")?.takeIf { it.isDirectory }
        val dir = obsidian?.let { root.openDirectory(it) }
        val app = dir?.find("app.json")
        if (dir == null || app == null) null
        else Json.parseToJsonElement(dir.read(app).use { it.readBytes().decodeToString() }).jsonObject["attachmentFolderPath"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /**
     * Where a new picture goes, by Obsidian's [setting]: the vault's top ("/", or none), a
     * folder ("attachments"), or beside the note showing it, in [noteFolder] ("./", "./pics").
     */
    private fun attachmentFolder(setting: String?, noteFolder: String): String {
        fun clean(p: String) = p.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
        val s = setting?.trim().orEmpty()
        return when {
            s.isEmpty() || s == "/" -> ""
            s == "." || s.startsWith("./") -> clean(SyncPlanner.folderFor(SyncNote(0, "", noteFolder, "", 0, false)) + "/" + s.removePrefix("."))
            else -> clean(s)
        }
    }
}
