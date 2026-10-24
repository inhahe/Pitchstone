package app.pitchstone.sync

import app.pitchstone.export.FileNames

/** A markdown file in the synced folder: [path] is relative to it, with `/` ("Work/Plan.md"). */
data class VaultFile(val path: String, val modified: Long?, val size: Long)

/**
 * A note as sync sees it. [hash] is that of its body ([SyncHash]), which is the file's whole
 * content; the title is the file's name. [blank] notes (no title, no text) are not synced yet.
 */
data class SyncNote(
    val id: Long,
    val title: String,
    val folder: String,
    val hash: String,
    val updatedAt: Long,
    val trashed: Boolean,
    val blank: Boolean = false,
)

/** What a pair of note and file was at the last sync (a [SyncEntryEntity] of a note). */
data class SyncPair(val path: String, val noteId: Long, val hash: String, val modified: Long?, val size: Long)

/** One step of a sync. Paths are relative to the synced folder. */
sealed interface SyncAction {
    /** Both sides hold the same text: remember the pair as it is now. */
    data class Record(val noteId: Long, val path: String) : SyncAction

    /** Write the note's text to its file ([create]: a new one), first moving it from [movedFrom]. */
    data class WriteFile(val noteId: Long, val path: String, val create: Boolean, val movedFrom: String? = null) : SyncAction

    /** Move the file from [from] to [to] for a note renamed or moved in the app; its text is the same. */
    data class MoveFile(val noteId: Long, val from: String, val to: String) : SyncAction

    /** The file changed: the note takes its text (after the file is moved to [movedTo], if the note was renamed). */
    data class UpdateNote(val noteId: Long, val path: String, val movedTo: String? = null) : SyncAction

    /**
     * Both changed since the last sync (or meet for the first time with different text). The
     * newer wins ([keepFile]: the file's text); the other is kept in the note's version history.
     */
    data class Conflict(val noteId: Long, val path: String, val keepFile: Boolean) : SyncAction

    /** The file was moved or renamed outside the app: the note takes its new name and folder. */
    data class Adopt(val noteId: Long, val from: String, val to: String, val writeNote: Boolean) : SyncAction

    /** A file the app has no note for: it becomes one. */
    data class Import(val path: String) : SyncAction

    /** The note's file was deleted outside the app: the note goes to the app's trash. */
    data class TrashNote(val noteId: Long, val path: String) : SyncAction

    /** The note left the app (trashed or deleted) and its file is unchanged: the file goes to the folder's `.trash`. */
    data class TrashFile(val path: String) : SyncAction

    /** The note is in the app's trash, but its file changed since: the note comes back with the file's text. */
    data class RestoreNote(val noteId: Long, val path: String) : SyncAction

    /** Neither side is left: the pair is forgotten. */
    data class Forget(val path: String) : SyncAction
}

/**
 * Decides what a sync does, from the folder's markdown [files], the app's [notes] (those in
 * the trash too, if paired) and the [pairs] remembered from the last sync. A side changed
 * if its text's hash differs from the pair's; a file's text is read ([fileHash]) only when
 * its time or size moved, or it is new. Nothing is ever lost: a deleted note's file goes to the
 * folder's `.trash`, a deleted file's note to the app's trash, and of two different texts the
 * one not kept stays in the note's version history. [outside] lists the markdown files in the
 * folders left out of sync; it is asked only to follow a paired file moved into one.
 */
object SyncPlanner {
    fun plan(
        files: List<VaultFile>,
        notes: List<SyncNote>,
        pairs: List<SyncPair>,
        fileHash: (path: String) -> String,
        outside: () -> List<VaultFile> = { emptyList() },
    ): List<SyncAction> {
        val filesByPath = files.associateBy { it.path.lowercase() }
        val notesById = notes.associateBy { it.id }
        val out = ArrayList<SyncAction>()
        val usedFiles = HashSet<String>() // lower-cased paths accounted for
        val usedNotes = HashSet<Long>()
        // Paired paths too: a pair forgotten later in the plan must not take a file written earlier with it.
        val taken = (files.map { it.path.lowercase() } + pairs.map { it.path.lowercase() }).toHashSet()
        val gone = ArrayList<SyncPair>()

        fun changed(file: VaultFile, pair: SyncPair) =
            (file.modified != pair.modified || file.size != pair.size) && fileHash(file.path) != pair.hash

        for (pair in pairs) {
            val file = filesByPath[pair.path.lowercase()]
            if (file == null) {
                gone += pair
                continue
            }
            usedFiles += file.path.lowercase()
            val note = notesById[pair.noteId]
            val fileChanged = changed(file, pair)
            when {
                note == null -> out += if (fileChanged) SyncAction.Import(file.path) else SyncAction.TrashFile(file.path)
                note.trashed -> out += if (fileChanged) SyncAction.RestoreNote(note.id, file.path) else SyncAction.TrashFile(file.path)
                else -> {
                    usedNotes += note.id
                    val noteChanged = note.hash != pair.hash
                    val wanted = pathFor(note, current = file.path, taken)
                    val move = !wanted.equals(file.path, ignoreCase = true)
                    if (move) taken += wanted.lowercase()
                    out += when {
                        noteChanged && fileChanged ->
                            if (fileHash(file.path) == note.hash) SyncAction.Record(note.id, file.path)
                            else SyncAction.Conflict(note.id, file.path, keepFile = (file.modified ?: 0) > note.updatedAt)
                        noteChanged -> SyncAction.WriteFile(note.id, wanted, create = false, movedFrom = file.path.takeIf { move })
                        fileChanged -> SyncAction.UpdateNote(note.id, file.path, movedTo = wanted.takeIf { move })
                        move -> SyncAction.MoveFile(note.id, file.path, wanted)
                        file.modified != pair.modified || file.size != pair.size -> SyncAction.Record(note.id, file.path)
                        else -> continue
                    }
                }
            }
        }

        val unpaired = files.filter { it.path.lowercase() !in usedFiles }
        val outsideFiles by lazy(outside)
        for (pair in gone) {
            val note = notesById[pair.noteId]
            // The same text under another name, new to this sync: the file was moved or renamed.
            // Unchanged, it has the pair's size: only those are read.
            fun movedAmong(candidates: List<VaultFile>) =
                candidates.firstOrNull { it.size == pair.size && it.path.lowercase() !in usedFiles && fileHash(it.path) == pair.hash }
            // Into a folder left out of sync, only for a note that can follow it there.
            val moved = movedAmong(unpaired) ?: if (note != null && !note.trashed) movedAmong(outsideFiles) else null
            if (moved != null && note != null) {
                usedFiles += moved.path.lowercase()
                if (note.trashed) {
                    out += SyncAction.TrashFile(moved.path)
                } else {
                    usedNotes += note.id
                    out += SyncAction.Adopt(note.id, pair.path, moved.path, writeNote = note.hash != pair.hash)
                }
                continue
            }
            when {
                note == null || note.trashed -> out += SyncAction.Forget(pair.path)
                note.hash == pair.hash -> {
                    usedNotes += note.id
                    out += SyncAction.TrashNote(note.id, pair.path)
                }
                // Deleted outside but edited in the app since: the edit wins, the file comes back.
                else -> {
                    usedNotes += note.id
                    taken -= pair.path.lowercase()
                    val path = pathFor(note, current = null, taken).also { taken += it.lowercase() }
                    // Forgotten first: the new file may have the old path, and its pair with it.
                    out += SyncAction.Forget(pair.path)
                    out += SyncAction.WriteFile(note.id, path, create = true)
                }
            }
        }

        // Notes by the file they would have, for matching unpaired files to them.
        val byNaturalPath = notes.filter { !it.trashed && !it.blank }.groupBy { naturalPath(it).lowercase() }
        for (file in unpaired) {
            if (file.path.lowercase() in usedFiles) continue
            usedFiles += file.path.lowercase()
            // A note without a pair that would be this file: the first sync, or one made on both sides.
            val match = byNaturalPath[file.path.lowercase()]?.firstOrNull { it.id !in usedNotes }
            if (match == null) {
                out += SyncAction.Import(file.path)
                continue
            }
            usedNotes += match.id
            out += if (fileHash(file.path) == match.hash) SyncAction.Record(match.id, file.path)
            else SyncAction.Conflict(match.id, file.path, keepFile = (file.modified ?: 0) > match.updatedAt)
        }

        for (note in notes) {
            if (note.trashed || note.blank || note.id in usedNotes) continue
            val path = pathFor(note, current = null, taken)
            taken += path.lowercase()
            out += SyncAction.WriteFile(note.id, path, create = true)
        }
        return out
    }

    /** The folder path of [note]'s file, its folders made valid file names ("" at the top). */
    fun folderFor(note: SyncNote): String = folderFor(note.folder)

    /** The folder path in the synced folder for the app's folder [folder]. */
    fun folderFor(folder: String): String =
        folder.split('/').filter { it.isNotBlank() }.joinToString("/") { FileNames.safe(it, "Folder") }

    /** The file [note] would have with nothing in the way: "Folder/Title.md". */
    fun naturalPath(note: SyncNote): String {
        val folder = folderFor(note)
        val name = FileNames.safe(note.title) + ".md"
        return if (folder.isEmpty()) name else "$folder/$name"
    }

    /**
     * Where [note]'s file belongs: its [current] path while that still fits the note's folder
     * and title (possibly with a " (2)" a clash gave it), else the natural path, or the first
     * "Title (n).md" not [taken] (lower-cased paths of other files).
     */
    fun pathFor(note: SyncNote, current: String?, taken: Set<String>): String {
        val folder = folderFor(note)
        val base = FileNames.safe(note.title)
        if (current != null && fitsFolder(current.substringBeforeLast('/', ""), folder) && fitsName(current.substringAfterLast('/'), base)) {
            return current
        }
        var n = 1
        while (true) {
            val name = if (n == 1) "$base.md" else "$base ($n).md"
            val path = if (folder.isEmpty()) name else "$folder/$name"
            if (path.lowercase() !in taken || path.equals(current, ignoreCase = true)) return path
            n++
        }
    }

    private val NUMBERED = Regex("(.*) \\(\\d+\\)")

    /**
     * Whether the file [name] suits a note whose file name would be [base]: "base.md", or
     * "base (2).md" (a clash numbered it), ignoring case, or a name that means the same but was
     * spelled by someone else (a `?` in it, which [FileNames.safe] would have replaced).
     */
    fun fitsName(name: String, base: String): Boolean {
        if (!name.endsWith(".md", ignoreCase = true)) return false
        val stem = name.dropLast(3)
        val unnumbered = NUMBERED.matchEntire(stem)?.groupValues?.get(1)
        return listOfNotNull(stem, unnumbered).any { it.equals(base, ignoreCase = true) || FileNames.safe(it).equals(base, ignoreCase = true) }
    }

    /** Whether the folder path [current] is [wanted] ([folderFor]), each part as [fitsName] would judge it. */
    fun fitsFolder(current: String, wanted: String): Boolean {
        val a = current.split('/').filter { it.isNotEmpty() }
        val b = wanted.split('/').filter { it.isNotEmpty() }
        return a.size == b.size && a.zip(b).all { (x, y) -> x.equals(y, ignoreCase = true) || FileNames.safe(x, "Folder").equals(y, ignoreCase = true) }
    }
}
