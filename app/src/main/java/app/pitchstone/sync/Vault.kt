package app.pitchstone.sync

import app.pitchstone.data.AttachmentStore
import app.pitchstone.export.DocEntry
import app.pitchstone.export.DocumentDir

/** A file in the synced folder, where it is. */
internal class Located(val path: String, val dir: DocumentDir, val entry: DocEntry)

internal fun joinPath(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

/** Why [e] happened, for a problem line. */
internal fun describe(e: Exception) = e.message ?: e.javaClass.simpleName

/**
 * The folders left out of sync, as the user chose them ("Templates", "Archive/2020"): a path is
 * left out if it is one of them or inside one, ignoring case.
 */
internal class Excluded(folders: Collection<String>) {
    private val paths = folders.map { normalize(it).lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun isEmpty() = paths.isEmpty()

    /** Whether the file or folder at [path] is left out. */
    fun contains(path: String): Boolean {
        val p = path.lowercase()
        return paths.any { p == it || p.startsWith("$it/") }
    }

    /** Whether a file in the folder [folder] ("" at the top) is left out. */
    fun containsFolder(folder: String) = folder.isNotEmpty() && contains(folder)

    companion object {
        fun normalize(path: String) = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
    }
}

/**
 * The synced folder as listed: markdown files by lower-cased path, and attachments (pictures,
 * PDFs, audio...) by lower-cased name, all outside hidden folders (`.obsidian`, `.trash`, `.git`)
 * and the folders left out ([excluded]). The markdown files of those are listed only if asked
 * ([outside]), to follow a file moved into one.
 */
internal class Vault private constructor(val root: DocumentDir, val excluded: Excluded) {
    val notes = HashMap<String, Located>()
    val attachments = HashMap<String, MutableList<Located>>()
    private val dirs = HashMap<String, DocumentDir>()
    private val excludedDirs = ArrayList<Pair<String, DocumentDir>>()
    private val outsideNotes = HashMap<String, Located>()
    private var outsideListed = false

    /**
     * Whether the folder listed nothing at all, hidden files included: one that cannot be read
     * right now (a card not there, a folder moved) rather than one whose notes were deleted.
     */
    var listedNothing = false
        private set

    /** The folder at [path] ("" for the top), created if missing. */
    fun dir(path: String): DocumentDir = dirs[path.lowercase()] ?: run {
        val parent = if (path.contains('/')) dir(path.substringBeforeLast('/')) else root
        parent.subdirectory(path.substringAfterLast('/')).also { dirs[path.lowercase()] = it }
    }

    /** The markdown files in sync, as the planner sees them. */
    val files: List<VaultFile> get() = notes.values.map { it.file }

    /** The markdown file at [path], in sync or left out (if listed), or null. */
    fun note(path: String): Located? = notes[path.lowercase()] ?: outsideNotes[path.lowercase()]

    /** Records the markdown file now at [at.path] (written or moved there). */
    fun put(at: Located) {
        if (excluded.contains(at.path)) outsideNotes[at.path.lowercase()] = at else notes[at.path.lowercase()] = at
    }

    fun remove(path: String) {
        notes.remove(path.lowercase())
        outsideNotes.remove(path.lowercase())
    }

    /** The markdown files in the folders left out, listed the first time this is asked. */
    fun outside(): List<VaultFile> {
        if (!outsideListed) {
            outsideListed = true
            for ((path, dir) in excludedDirs) walk(dir, path, { outsideNotes[it.path.lowercase()] = it })
        }
        return outsideNotes.values.map { it.file }
    }

    private val Located.file get() = VaultFile(path, entry.lastModified, entry.size)

    /** Lists [dir] (at [path]) and below, giving [note] each markdown file; attachments and folders go to this vault. */
    private fun walk(dir: DocumentDir, path: String, note: (Located) -> Unit, entries: List<DocEntry> = dir.list()) {
        for (entry in entries) {
            // Hidden: .obsidian, .trash, .git, .stfolder (Syncthing) and the like.
            if (entry.name.startsWith(".")) continue
            val child = joinPath(path, entry.name)
            when {
                entry.isDirectory -> {
                    val sub = dir.openDirectory(entry)
                    dirs[child.lowercase()] = sub
                    if (excluded.contains(child) && !excluded.contains(path)) excludedDirs += child to sub else walk(sub, child, note)
                }
                entry.name.endsWith(".md", ignoreCase = true) -> note(Located(child, dir, entry))
                !excluded.contains(child) && AttachmentStore.isAttachment(entry.name) ->
                    attachments.getOrPut(entry.name.lowercase()) { ArrayList() } += Located(child, dir, entry)
            }
        }
    }

    companion object {
        /** Lists the folder [root], leaving out the folders [excluded] (paths in it). */
        fun scan(root: DocumentDir, excluded: Collection<String>): Vault {
            val vault = Vault(root, Excluded(excluded))
            vault.dirs[""] = root
            val top = root.list()
            vault.listedNothing = top.isEmpty()
            vault.walk(root, "", { vault.notes[it.path.lowercase()] = it }, top)
            return vault
        }

        /** The folders in [root] and below (not hidden), by path, sorted: for choosing which to leave out. */
        fun folders(root: DocumentDir, limit: Int = 2000): List<String> {
            val out = ArrayList<String>()
            fun walk(dir: DocumentDir, path: String) {
                for (entry in dir.list().sortedBy { it.name.lowercase() }) {
                    if (out.size >= limit) return
                    if (!entry.isDirectory || entry.name.startsWith(".")) continue
                    val child = joinPath(path, entry.name)
                    out += child
                    walk(dir.openDirectory(entry), child)
                }
            }
            walk(root, "")
            return out
        }
    }
}
