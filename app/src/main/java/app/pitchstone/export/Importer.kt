package app.pitchstone.export

import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.FolderPaths
import app.pitchstone.data.NoteRepository
import app.pitchstone.data.NoteTooLargeException
import app.pitchstone.markdown.LinkRewriter
import java.io.InputStream

/** A file that can be imported: a note, or an attachment notes link to. */
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
    /** Attachments the imported notes link to, stored with them (new ones only). */
    val attachments: Int = 0,
    /** Attachments a note links to that could not be stored (too large, unreadable), and why; the note is imported. */
    val failedAttachments: List<Pair<String, String>> = emptyList(),
)

object Importer {
    val EXTENSIONS = setOf("md", "markdown", "txt")
    /** The largest file imported: a note can hold no more (NoteRepository.MAX_NOTE_BYTES). */
    const val MAX_BYTES = NoteRepository.MAX_NOTE_BYTES.toLong()

    /** Whether [file] becomes a note (rather than an attachment a note links to). */
    fun isNote(file: ImportFile): Boolean = file.extension in EXTENSIONS

    /**
     * The importable files in [dir] and its subfolders: notes, and attachments (pictures, PDFs,
     * audio...: [AttachmentStore.isAttachment], stored only if an imported note links to them). Hidden folders (".obsidian", ".git", ".trash") are
     * skipped, as is anything else.
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
            if (ext !in EXTENSIONS && !AttachmentStore.isAttachment(entry.name)) continue
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

    /**
     * File content as text: UTF-8 (with or without a byte-order mark), newlines normalized.
     * Throws [NoteTooLargeException] past [MAX_BYTES], counted as it is read: a provider's
     * reported size can be missing or wrong.
     */
    fun readText(input: InputStream): String {
        val bytes = input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw NoteTooLargeException(out.size())
            }
            out.toByteArray()
        }
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
     * Imports the notes among [files] into [targetFolder], with the attachments among them that
     * they link to, stored in [attachments] (one whose name is taken by another one is stored
     * under a new name, and the note's mentions follow). A note that already exists unchanged
     * (same title, folder and content) is not imported again, and an attachment already stored is
     * not stored twice, so importing twice is harmless. Plain-text files become notes that show
     * their text literally unless [txtAsMarkdown]. [progress] gets the number of notes done.
     */
    suspend fun import(
        repo: NoteRepository,
        files: List<ImportFile>,
        targetFolder: String,
        keepFolders: Boolean,
        txtAsMarkdown: Boolean,
        attachments: AttachmentStore? = null,
        progress: (Int) -> Unit = {},
    ): ImportResult {
        // Folders compare ignoring case: a note is stored under an existing folder's spelling.
        val existing = repo.allNotes().map { Triple(it.title, it.folder.lowercase(), it.body) }.toHashSet()
        var imported = 0
        var present = 0
        val failed = ArrayList<Pair<String, String>>()
        val large = ArrayList<String>()
        val attachmentFiles = files.filterNot(::isNote).groupBy { "${it.title}.${it.extension}".lowercase() }
        val storedAs = HashMap<ImportFile, String>()
        val attachmentFailed = HashMap<ImportFile, String>()
        var newAttachments = 0
        val before = attachments?.names()?.map { it.lowercase() }?.toSet().orEmpty()
        files.filter(::isNote).forEachIndexed { i, f ->
            val folder = FolderPaths.normalize(if (keepFolders) FolderPaths.child(targetFolder, f.folder) else targetFolder)
            val label = if (f.folder.isEmpty()) "${f.title}.${f.extension}" else "${f.folder}/${f.title}.${f.extension}"
            try {
                if (f.size > MAX_BYTES) {
                    large += label
                } else {
                    val text = readText(f.open())
                    var body = if (f.extension == "txt" && !txtAsMarkdown) plainTextToMarkdown(text) else text
                    if (attachments != null) {
                        // Renamed all at once: one attachment's new name may be another's old one.
                        val renames = HashMap<String, String>()
                        for (mention in AttachmentStore.mentionedNames(body)) {
                            // The file nearest the note: in its folder, else the first found.
                            val candidates = attachmentFiles[mention] ?: continue
                            val file = candidates.firstOrNull { it.folder == f.folder } ?: candidates.first()
                            if (file in attachmentFailed) continue
                            val fileName = "${file.title}.${file.extension}"
                            // One that cannot be stored is reported; the note comes in without it.
                            val name = storedAs[file] ?: try {
                                attachments.addOrReuse(file.open(), fileName).also {
                                    storedAs[file] = it
                                    if (it.lowercase() !in before) newAttachments++
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                attachmentFailed[file] = e.message ?: e.javaClass.simpleName
                                continue
                            }
                            if (!name.equals(fileName, ignoreCase = true)) renames[fileName] = name
                        }
                        if (renames.isNotEmpty()) body = LinkRewriter.renameAttachments(body, renames)
                    }
                    if (Triple(f.title, folder.lowercase(), body) in existing) {
                        present++
                    } else {
                        repo.import(f.title, body, folder, createdAt = null, updatedAt = f.lastModified)
                        existing += Triple(f.title, folder.lowercase(), body)
                        imported++
                    }
                }
            } catch (e: NoteTooLargeException) {
                large += label
            } catch (e: Exception) {
                failed += label to (e.message ?: e.javaClass.simpleName)
            }
            progress(i + 1)
        }
        val failedAttachments = attachmentFailed.map { (f, why) -> (if (f.folder.isEmpty()) "" else "${f.folder}/") + "${f.title}.${f.extension}" to why }
        return ImportResult(imported, present, failed, large, newAttachments, failedAttachments)
    }
}
