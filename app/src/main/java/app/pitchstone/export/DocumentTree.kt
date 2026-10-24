package app.pitchstone.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** An entry of a [DocumentDir]. */
data class DocEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long?,
    /** Implementation-specific handle (a document URI, or a File). */
    val handle: Any,
)

/** A file just created by [DocumentDir.createFile]: the [name] it was actually given, and a [stream] to write it. */
data class NewFile(val name: String, val stream: OutputStream, val handle: Any) {
    /** The file as an entry (size and time unknown until [DocumentDir.refresh]). */
    val entry: DocEntry get() = DocEntry(name, false, 0, null, handle)
}

/** A directory the export writes into, the import reads from, and folder sync keeps in step. */
interface DocumentDir {
    val name: String
    fun list(): List<DocEntry>
    /** The subdirectory [name], created if it does not exist. */
    fun subdirectory(name: String): DocumentDir
    fun openDirectory(entry: DocEntry): DocumentDir
    /** Creates a file; it may be given another name than [name] (a provider's own suffix, say). */
    fun createFile(name: String, mimeType: String): NewFile
    /** Opens an existing file for writing, emptying it first. */
    fun overwrite(entry: DocEntry, mimeType: String): OutputStream
    fun read(entry: DocEntry): InputStream
    /** [entry] as it is now (its size and time after a write), or null if it is gone. */
    fun refresh(entry: DocEntry): DocEntry?
    /** Gives the file or folder [entry] the name [name] (not taken); returns it as it is now. */
    fun rename(entry: DocEntry, name: String): DocEntry
    /** Moves [entry] into [target] (where its name is not taken); returns it as it is there. */
    fun moveTo(entry: DocEntry, target: DocumentDir): DocEntry
    /** Deletes the file [entry]. Folder sync only does so to a file it has just copied elsewhere. */
    fun delete(entry: DocEntry)
}

/** The entry named [name] in this directory, ignoring case, or null. */
fun DocumentDir.find(name: String): DocEntry? = list().firstOrNull { it.name.equals(name, ignoreCase = true) }

fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
    "txt" -> "text/plain"
    "html", "htm" -> "text/html"
    // Not text/markdown: some providers then append ".txt" or another extension of their own.
    else -> "application/octet-stream"
}

/** A directory chosen through the Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE). */
class SafDir private constructor(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    override val name: String,
) : DocumentDir {
    companion object {
        fun root(resolver: ContentResolver, treeUri: Uri): SafDir {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            var name = id.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Folder" }
            resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            return SafDir(resolver, treeUri, id, name)
        }
    }

    private val documentUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun list(): List<DocEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<DocEntry>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 }
                out += DocEntry(name, mime == Document.MIME_TYPE_DIR, size, modified, id)
            }
        }
        return out
    }

    override fun subdirectory(name: String): DocumentDir {
        list().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }?.let { return openDirectory(it) }
        val uri = DocumentsContract.createDocument(resolver, documentUri, Document.MIME_TYPE_DIR, name)
            ?: throw FileNotFoundException("Could not create folder $name")
        return SafDir(resolver, treeUri, DocumentsContract.getDocumentId(uri), displayName(uri) ?: name)
    }

    override fun openDirectory(entry: DocEntry): DocumentDir = SafDir(resolver, treeUri, entry.handle as String, entry.name)

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    override fun createFile(name: String, mimeType: String): NewFile {
        var uri = DocumentsContract.createDocument(resolver, documentUri, mimeType, name)
            ?: throw FileNotFoundException("Could not create $name")
        var actual = displayName(uri) ?: name
        if (actual != name && actual.startsWith(name.substringBeforeLast('.'))) {
            // Some providers add an extension of their own ("note.md.txt"); try to undo that.
            runCatching { DocumentsContract.renameDocument(resolver, uri, name) }.getOrNull()?.let {
                uri = it
                actual = displayName(it) ?: name
            }
        }
        val stream = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("Could not write $actual")
        return NewFile(actual, stream, DocumentsContract.getDocumentId(uri))
    }

    override fun overwrite(entry: DocEntry, mimeType: String): OutputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.handle as String)
        // "wt" truncates. Providers that refuse it get a fresh file instead: plain "w" may
        // leave the end of a longer old file in place.
        return try {
            resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(entry.name)
        } catch (e: Exception) {
            if (e !is FileNotFoundException && e !is IllegalArgumentException && e !is UnsupportedOperationException) throw e
            DocumentsContract.deleteDocument(resolver, uri)
            createFile(entry.name, mimeType).stream
        }
    }

    override fun read(entry: DocEntry): InputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.handle as String)
        return resolver.openInputStream(uri) ?: throw FileNotFoundException(entry.name)
    }

    private fun uriOf(entry: DocEntry): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.handle as String)

    /** [uri]'s entry, read back from the provider, or null if it has none. */
    private fun stat(uri: Uri): DocEntry? {
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val c = try {
            resolver.query(uri, projection, null, null, null)
        } catch (e: Exception) {
            // A document deleted meanwhile: providers throw rather than return no row.
            if (e !is FileNotFoundException && e !is IllegalArgumentException && e !is IllegalStateException) throw e
            null
        } ?: return null
        c.use {
            if (!c.moveToFirst()) return null
            return DocEntry(
                c.getString(0) ?: return null,
                c.getString(1) == Document.MIME_TYPE_DIR,
                if (c.isNull(2)) 0L else c.getLong(2),
                if (c.isNull(3)) null else c.getLong(3).takeIf { it > 0 },
                DocumentsContract.getDocumentId(uri),
            )
        }
    }

    private fun entryOf(uri: Uri, fallbackName: String): DocEntry =
        stat(uri) ?: DocEntry(fallbackName, false, 0, null, DocumentsContract.getDocumentId(uri))

    override fun refresh(entry: DocEntry): DocEntry? = stat(uriOf(entry))

    override fun rename(entry: DocEntry, name: String): DocEntry {
        val renamed = DocumentsContract.renameDocument(resolver, uriOf(entry), name)
            ?: throw FileNotFoundException("Could not rename ${entry.name}")
        return entryOf(renamed, name)
    }

    override fun moveTo(entry: DocEntry, target: DocumentDir): DocEntry {
        val to = target as SafDir
        try {
            val moved = DocumentsContract.moveDocument(resolver, uriOf(entry), documentUri, to.documentUri)
            if (moved != null) return entryOf(moved, entry.name)
        } catch (e: Exception) {
            // Providers without FLAG_SUPPORTS_MOVE refuse; a copy and a delete do the same.
            if (e !is UnsupportedOperationException && e !is IllegalArgumentException && e !is IllegalStateException) throw e
        }
        if (entry.isDirectory) throw FileNotFoundException("Could not move the folder ${entry.name}")
        val copy = to.createFile(entry.name, mimeTypeFor(entry.name.substringAfterLast('.', "")))
        copy.stream.use { o -> read(entry).use { it.copyTo(o) } }
        delete(entry)
        return to.refresh(copy.entry) ?: copy.entry
    }

    override fun delete(entry: DocEntry) {
        if (!DocumentsContract.deleteDocument(resolver, uriOf(entry))) throw FileNotFoundException("Could not delete ${entry.name}")
    }
}

/** A plain directory on disk (used by tests, and for the app's own cache). */
class FileDir(val dir: File) : DocumentDir {
    override val name: String get() = dir.name

    override fun list(): List<DocEntry> =
        (dir.listFiles() ?: emptyArray()).map { DocEntry(it.name, it.isDirectory, it.length(), it.lastModified(), it) }

    override fun subdirectory(name: String): DocumentDir {
        val existing = dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        val sub = existing ?: File(dir, name).also { it.mkdirs() }
        return FileDir(sub)
    }

    override fun openDirectory(entry: DocEntry): DocumentDir = FileDir(entry.handle as File)

    override fun createFile(name: String, mimeType: String): NewFile {
        val f = File(dir, name)
        if (f.exists()) throw IllegalStateException("$name exists")
        return NewFile(name, f.outputStream(), f)
    }

    override fun overwrite(entry: DocEntry, mimeType: String): OutputStream = (entry.handle as File).outputStream()

    override fun read(entry: DocEntry): InputStream = (entry.handle as File).inputStream()

    private fun entryOf(f: File) = DocEntry(f.name, f.isDirectory, f.length(), f.lastModified(), f)

    override fun refresh(entry: DocEntry): DocEntry? = (entry.handle as File).takeIf { it.exists() }?.let(::entryOf)

    override fun rename(entry: DocEntry, name: String): DocEntry {
        val from = entry.handle as File
        val to = File(from.parentFile, name)
        if (to.exists() && !to.name.equals(from.name, ignoreCase = true)) throw IllegalStateException("$name exists")
        if (!from.renameTo(to)) throw FileNotFoundException("Could not rename ${entry.name}")
        return entryOf(to)
    }

    override fun moveTo(entry: DocEntry, target: DocumentDir): DocEntry {
        val from = entry.handle as File
        val to = File((target as FileDir).dir, from.name)
        if (to.exists()) throw IllegalStateException("${from.name} exists")
        if (!from.renameTo(to)) throw FileNotFoundException("Could not move ${entry.name}")
        return entryOf(to)
    }

    override fun delete(entry: DocEntry) {
        if (!(entry.handle as File).delete()) throw FileNotFoundException("Could not delete ${entry.name}")
    }
}
