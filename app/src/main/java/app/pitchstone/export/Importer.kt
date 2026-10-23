package app.pitchstone.export

import app.pitchstone.data.FolderPaths
import app.pitchstone.data.NoteRepository
import java.io.InputStream

/** A file that can be imported as a note. */
data class ImportFile(
    /** The note's title: the file name without its extension. */
    val title: String,
    val extension: String,
    /** Folder path relative to the imported folder ("" at its top). */
    val folder: String,
    val lastModified: Long?,
    val size: Long,
    val open: () -> InputStream,
)

data class ImportResult(
    val imported: Int,
    val alreadyPresent: Int,
    val failed: List<Pair<String, String>>,
    val skippedLarge: List<String>,
)

object Importer {
    val EXTENSIONS = setOf("md", "markdown", "txt")
    const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * The importable files in [dir] and its subfolders. Hidden folders (".obsidian", ".git",
     * ".trash") are skipped, as is anything that is not markdown or text.
     */
    fun collect(dir: DocumentDir, folder: String = ""): List<ImportFile> {
        val out = ArrayList<ImportFile>()
        for (entry in dir.list().sortedBy { it.name.lowercase() }) {
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                out += collect(dir.openDirectory(entry), FolderPaths.child(folder, FolderPaths.cleanName(entry.name)))
                continue
            }
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext !in EXTENSIONS) continue
            out += ImportFile(
                title = entry.name.substringBeforeLast('.'),
                extension = ext,
                folder = folder,
                lastModified = entry.lastModified,
                size = entry.size,
                open = { dir.read(entry) },
            )
        }
        return out
    }

    /** File content as text: UTF-8 (with or without a byte-order mark), newlines normalized. */
    fun readText(input: InputStream): String {
        val bytes = input.use { it.readBytes() }
        val text = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    /**
     * Markdown that shows [text] exactly as written: every punctuation character that could
     * mean something is escaped, and leading spaces are kept. Lines stay lines, since a single
     * newline is a line break in the app's markdown.
     */
    fun plainTextToMarkdown(text: String): String =
        text.split('\n').joinToString("\n") { line ->
            val lead = line.takeWhile { it == ' ' || it == '\t' }
            val trail = line.drop(lead.length).takeLastWhile { it == ' ' || it == '\t' }
            val middle = line.substring(lead.length, line.length - trail.length)
            if (middle.isEmpty()) return@joinToString ""
            val escaped = StringBuilder()
            // What starts a block at the beginning of a line: #, >, +, -, and "1." / "1)".
            val ordered = Regex("^\\d{1,9}[.)]").find(middle)
            middle.forEachIndexed { i, ch ->
                val lineStart = i == 0 && ch in "#>+-"
                val listDelimiter = ordered != null && i == ordered.value.length - 1
                if (ch in ALWAYS_ESCAPED || lineStart || listDelimiter) escaped.append('\\')
                escaped.append(ch)
            }
            // Leading and trailing whitespace would be dropped; entities keep it.
            lead.map { "&#${it.code};" }.joinToString("") + escaped + trail.map { "&#${it.code};" }.joinToString("")
        }

    /** Characters that can mean something anywhere in a line. */
    private const val ALWAYS_ESCAPED = "\\`*_[]<>|~=^&"

    /**
     * Imports [files] into [targetFolder]. A file whose note already exists unchanged (same
     * title, folder and content) is not imported again, so importing twice is harmless.
     * Plain-text files become notes that show their text literally unless [txtAsMarkdown].
     */
    suspend fun import(
        repo: NoteRepository,
        files: List<ImportFile>,
        targetFolder: String,
        keepFolders: Boolean,
        txtAsMarkdown: Boolean,
        progress: (Int) -> Unit = {},
    ): ImportResult {
        val existing = repo.allNotes().map { Triple(it.title, it.folder, it.body) }.toHashSet()
        var imported = 0
        var present = 0
        val failed = ArrayList<Pair<String, String>>()
        val large = ArrayList<String>()
        files.forEachIndexed { i, f ->
            val folder = FolderPaths.normalize(if (keepFolders) FolderPaths.child(targetFolder, f.folder) else targetFolder)
            val label = if (f.folder.isEmpty()) "${f.title}.${f.extension}" else "${f.folder}/${f.title}.${f.extension}"
            try {
                if (f.size > MAX_BYTES) {
                    large += label
                } else {
                    val text = readText(f.open())
                    val body = if (f.extension == "txt" && !txtAsMarkdown) plainTextToMarkdown(text) else text
                    if (Triple(f.title, folder, body) in existing) {
                        present++
                    } else {
                        repo.import(f.title, body, folder, createdAt = null, updatedAt = f.lastModified)
                        existing += Triple(f.title, folder, body)
                        imported++
                    }
                }
            } catch (e: Exception) {
                failed += label to (e.message ?: e.javaClass.simpleName)
            }
            progress(i + 1)
        }
        return ImportResult(imported, present, failed, large)
    }
}
