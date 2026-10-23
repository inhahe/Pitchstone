package app.inkdown.export

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

/** A directory the export writes into and the import reads from. */
interface DocumentDir {
    val name: String
    fun list(): List<DocEntry>
    /** The subdirectory [name], created if it does not exist. */
    fun subdirectory(name: String): DocumentDir
    fun openDirectory(entry: DocEntry): DocumentDir
    /** Creates a file and returns the name it was actually given, with a stream to write it. */
    fun createFile(name: String, mimeType: String): Pair<String, OutputStream>
    /** Opens an existing file for writing, emptying it first. */
    fun overwrite(entry: DocEntry, mimeType: String): OutputStream
    fun read(entry: DocEntry): InputStream
}

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

    override fun createFile(name: String, mimeType: String): Pair<String, OutputStream> {
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
        return actual to stream
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
            createFile(entry.name, mimeType).second
        }
    }

    override fun read(entry: DocEntry): InputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.handle as String)
        return resolver.openInputStream(uri) ?: throw FileNotFoundException(entry.name)
    }
}

/** A plain directory on disk (used by tests, and for the app's own cache). */
class FileDir(private val dir: File) : DocumentDir {
    override val name: String get() = dir.name

    override fun list(): List<DocEntry> =
        (dir.listFiles() ?: emptyArray()).map { DocEntry(it.name, it.isDirectory, it.length(), it.lastModified(), it) }

    override fun subdirectory(name: String): DocumentDir {
        val existing = dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        val sub = existing ?: File(dir, name).also { it.mkdirs() }
        return FileDir(sub)
    }

    override fun openDirectory(entry: DocEntry): DocumentDir = FileDir(entry.handle as File)

    override fun createFile(name: String, mimeType: String): Pair<String, OutputStream> {
        val f = File(dir, name)
        if (f.exists()) throw IllegalStateException("$name exists")
        return name to f.outputStream()
    }

    override fun overwrite(entry: DocEntry, mimeType: String): OutputStream = (entry.handle as File).outputStream()

    override fun read(entry: DocEntry): InputStream = (entry.handle as File).inputStream()
}
