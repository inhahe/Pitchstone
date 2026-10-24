package app.pitchstone.sync

import android.util.Log
import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.NoteRepository
import app.pitchstone.export.DocumentDir
import app.pitchstone.export.find
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The second step of a sync, after the notes: makes the attachments notes link to (pictures,
 * PDFs, audio, video, office files, canvases) the same in both places. One only in the folder
 * is stored in the app; one only in the app is written to the folder, where the vault's
 * Obsidian settings put attachments; one changed in the folder since the last sync replaces
 * the app's copy. Two different files of one name are both left, reported, and remembered as
 * a clash, so that a later change of either never replaces the other; the clash ends when they
 * are the same. Attachments are never deleted by a sync.
 */
internal class AttachmentSync(
    private val dao: SyncDao,
    private val repo: NoteRepository,
    private val store: AttachmentStore,
) {
    private companion object {
        const val TAG = "Pitchstone"
        const val MIME = "application/octet-stream"

        /** Marks an entry whose two copies differ. */
        const val CLASH = "clash:"
        const val CLASH_PROBLEM = "a different file of this name is in the app; neither was changed"
    }

    /** Syncs the attachments of [vault]'s notes; adds what failed to [problems]. Returns how many were copied. */
    suspend fun sync(vault: Vault, problems: MutableMap<String, String>): Int {
        val entries = dao.all().filter { it.attachment != null }.associateBy { it.path.lowercase() }
        // Attachment name -> the folder of a note in sync linking to it.
        val linked = LinkedHashMap<String, String>()
        for (note in repo.allNotes()) {
            if (vault.excluded.containsFolder(SyncPlanner.folderFor(note.folder))) continue
            for (name in AttachmentStore.mentionedNames(note.body)) if (AttachmentStore.isAttachment(name)) linked.putIfAbsent(name, note.folder)
        }
        val folderSetting by lazy { attachmentFolderSetting(vault.root) }
        var copied = 0
        for ((name, noteFolder) in linked) {
            // Of copies in several folders, the one synced before, else the first by path (not
            // by listing order, which may change between syncs).
            val copies = vault.attachments[name]
            val inVault = copies?.firstOrNull { it.path.lowercase() in entries } ?: copies?.minByOrNull { it.path.lowercase() }
            try {
                // Stored under another name (the app had another file of this name), the row from
                // the last sync says which; that name taken back if it is free now, and if no note
                // here names the other.
                var stored = store.file(name)
                if (stored == null && inVault != null) {
                    stored = entries[inVault.path.lowercase()]?.attachment?.let(store::file)
                    if (stored != null && stored.name.lowercase() !in linked && store.rename(stored.name, inVault.entry.name)) {
                        stored = store.file(inVault.entry.name)
                    }
                }
                when {
                    inVault != null && stored != null -> {
                        if (compare(inVault, stored, entries[inVault.path.lowercase()], problems)) copied++
                        if (!stored.name.equals(inVault.entry.name, ignoreCase = true)) {
                            problems.putIfAbsent(inVault.path, "kept in the app as ${stored.name}, the app having another file of this name")
                        }
                    }
                    inVault != null -> {
                        bringIn(inVault, problems)
                        copied++
                    }
                    stored != null -> {
                        // In the folder already, in a folder left out (it was synced there before).
                        val leftOutCopy = entries.values.any {
                            it.attachment != null && vault.excluded.contains(it.path) && it.path.substringAfterLast('/').equals(name, ignoreCase = true)
                        }
                        if (leftOutCopy) continue
                        val folder = attachmentFolder(folderSetting, noteFolder)
                        // A folder left out of sync gets nothing written into it.
                        if (vault.excluded.contains(joinPath(folder, stored.name))) continue
                        vault.touch(folder)
                        writeOut(vault.dir(folder), folder, stored)
                        copied++
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Syncing the attachment $name failed", e)
                problems.putIfAbsent(inVault?.path ?: name, describe(e))
            }
        }
        return copied
    }

    /**
     * The same name in both places, [entry] the row from the last sync: records them, replacing
     * the app's copy if the folder's changed since. Returns whether it was replaced.
     */
    private suspend fun compare(inVault: Located, stored: java.io.File, entry: SyncEntryEntity?, problems: MutableMap<String, String>): Boolean {
        val e = inVault.entry
        val clash = entry?.hash?.startsWith(CLASH) == true
        val untouched = entry != null && entry.modified == e.lastModified && entry.size == e.size
        if (untouched && clash) {
            problems.putIfAbsent(inVault.path, CLASH_PROBLEM)
            return false
        }
        if (untouched && stored.length() == e.size) return false
        if (e.size > AttachmentStore.MAX_BYTES) error("larger than an attachment can be")
        // Hashed as read, not held in memory: attachments may be large.
        val hash = SyncHash.of(inVault.dir.read(e))
        var replaced = false
        val recorded = when {
            hash == SyncHash.of(stored.inputStream()) -> hash
            // Changed in the folder since both had it: the app's copy follows.
            entry != null && !clash && hash != entry.hash -> {
                store.replace(stored.name, inVault.dir.read(e))
                replaced = true
                hash
            }
            else -> {
                problems.putIfAbsent(inVault.path, CLASH_PROBLEM)
                CLASH + hash
            }
        }
        dao.put(SyncEntryEntity(inVault.path, attachment = stored.name, hash = recorded, modified = e.lastModified, size = e.size))
        return replaced
    }

    /** Stores [inVault], which only the folder has. */
    private suspend fun bringIn(inVault: Located, problems: MutableMap<String, String>) {
        val e = inVault.entry
        if (e.size > AttachmentStore.MAX_BYTES) error("larger than an attachment can be")
        val storedAs = store.addOrReuse(inVault.dir.read(e), e.name)
        if (!storedAs.equals(e.name, ignoreCase = true)) {
            problems.putIfAbsent(inVault.path, "stored as $storedAs, another file in the app having this name")
        }
        val hash = SyncHash.of((store.file(storedAs) ?: error("$storedAs vanished")).inputStream())
        dao.put(SyncEntryEntity(inVault.path, attachment = storedAs, hash = hash, modified = e.lastModified, size = e.size))
    }

    /** Writes [stored], which only the app has, into [dir] (the folder at [folder]). */
    private suspend fun writeOut(dir: DocumentDir, folder: String, stored: java.io.File) {
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
                joinPath(folder, created.name), attachment = stored.name, hash = SyncHash.of(stored.inputStream()),
                modified = written.lastModified, size = written.size,
            ),
        )
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
     * Where a new attachment goes, by Obsidian's [setting]: the vault's top ("/", or none), a
     * folder ("attachments"), or beside the note linking to it, in [noteFolder] ("./", "./pics").
     */
    private fun attachmentFolder(setting: String?, noteFolder: String): String {
        fun clean(p: String) = p.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
        val s = setting?.trim().orEmpty()
        return when {
            s.isEmpty() || s == "/" -> ""
            s == "." || s.startsWith("./") -> clean(SyncPlanner.folderFor(noteFolder) + "/" + s.removePrefix("."))
            else -> clean(s)
        }
    }
}
