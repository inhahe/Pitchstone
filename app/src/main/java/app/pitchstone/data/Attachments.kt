package app.pitchstone.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import app.pitchstone.export.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A file stored with the notes (an image), which notes show by its name, as Obsidian does:
 * `![[Pasted image 20261023211500.png]]`. The bytes are files/attachments/<name>; the row
 * records when no note or version mentions it any more, for [AttachmentStore.collectGarbage].
 */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    /** The file name, unique ignoring case (as on phone storage and in exports). */
    @PrimaryKey @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val size: Long,
    val createdAt: Long,
    /** Since when nothing mentions it; null while something does. */
    val orphanedAt: Long? = null,
)

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    /** The attachment named [name], ignoring case. */
    @Query("SELECT * FROM attachments WHERE name = :name")
    suspend fun get(name: String): AttachmentEntity?

    @Query("SELECT * FROM attachments")
    suspend fun all(): List<AttachmentEntity>

    @Query("UPDATE attachments SET orphanedAt = :at WHERE name IN (:names)")
    suspend fun setOrphaned(names: List<String>, at: Long?)

    @Query("DELETE FROM attachments WHERE name IN (:names)")
    suspend fun delete(names: List<String>)

    @Query("UPDATE attachments SET size = :size WHERE name = :name")
    suspend fun setSize(name: String, size: Long)
}

/** An attachment larger than [AttachmentStore.MAX_BYTES] was refused. */
class AttachmentTooLargeException :
    IllegalArgumentException("This file is larger than ${AttachmentStore.MAX_BYTES / 1_000_000} MB, the most an attachment can be")

/**
 * The files stored with the notes, in [dir], one per attachment, named as notes mention them.
 * Names are unique ignoring case. A file nothing mentions is kept for [GRACE_MS] (a deleted
 * note can still be restored, an edit undone) before [collectGarbage] removes it.
 */
class AttachmentStore(
    val dir: File,
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.attachments()
    private val naming = Mutex()

    companion object {
        const val MAX_BYTES = 25_000_000L
        const val GRACE_MS = 30L * 24 * 60 * 60 * 1000

        /** File types shown as pictures in notes (the editor's attachments.ts has the same list). */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")

        fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        /** The file name in a reference: no folder part (`attachments/pic.png` -> `pic.png`). */
        fun baseName(reference: String): String = reference.substringAfterLast('/').substringAfterLast('\\').trim()

        private val LINK_BREAKING = Regex("[\\[\\]|#^]")
        private val WIKILINK_TARGET = Regex("\\[\\[([^\\[\\]\\n|#]+)")
        /** A link or image destination: `](<a b.png>` (spaces allowed) or `](a%20b.png`. */
        private val LINK_DESTINATION = Regex("\\]\\(\\s*(?:<([^>\\n]*)>|([^)\\s]+))")

        /**
         * The attachment names [markdown] may mention, lower-cased: `[[x]]` and `![[x]]` targets
         * and link or image destinations (`![](attachments/x.png)`), by their file name. More
         * rather than fewer (a name in code counts too): it decides what is kept.
         */
        fun mentionedNames(markdown: String): Set<String> {
            val out = HashSet<String>()
            // `![[pic.png\|200]]` in a table: the backslash belongs to the `|`, not the name.
            for (m in WIKILINK_TARGET.findAll(markdown)) out += baseName(m.groupValues[1].removeSuffix("\\")).lowercase()
            for (m in LINK_DESTINATION.findAll(markdown)) {
                val angled = m.groups[1]?.value
                if (angled != null) {
                    out += baseName(angled).lowercase()
                    continue
                }
                val dest = m.groupValues[2].substringBefore('#').substringBefore('?')
                val decoded = runCatching { URLDecoder.decode(dest.replace("+", "%2B"), "UTF-8") }.getOrDefault(dest)
                out += baseName(decoded).lowercase()
            }
            out.remove("")
            return out
        }
    }

    /** The file of the attachment a note mentions as [reference] (case and folders ignored), or null. */
    fun file(reference: String): File? {
        val name = baseName(reference)
        if (name.isEmpty() || name.startsWith(".")) return null
        File(dir, name).takeIf { it.isFile }?.let { return it }
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Stores [input] as a new attachment named after [preferredName] ("photo.jpg"), made a
     * valid file name and unique ignoring case ("photo 1.jpg"). Returns the name to write in
     * the note. Throws [AttachmentTooLargeException] past [MAX_BYTES], counted as it is read.
     */
    suspend fun add(input: InputStream, preferredName: String): String = withContext(Dispatchers.IO) {
        val incoming = File(dir, ".incoming").apply { mkdirs() }
        val tmp = File.createTempFile("add", ".tmp", incoming)
        try {
            val size = copyLimited(input, tmp)
            naming.withLock {
                val name = uniqueName(preferredName)
                if (!tmp.renameTo(File(dir, name))) error("Could not store $name")
                dao.insert(AttachmentEntity(name, size, clock()))
                name
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Stores [input] as [name], unless an attachment named so (or as a unique variant of it,
     * "name 1.png") holds the same bytes already: then that one is used. Returns the name
     * stored under, which notes then mention (for an import: importing twice stores once).
     */
    suspend fun addOrReuse(input: InputStream, name: String): String = withContext(Dispatchers.IO) {
        val incoming = File(dir, ".incoming").apply { mkdirs() }
        val tmp = File.createTempFile("import", ".tmp", incoming)
        try {
            copyLimited(input, tmp)
            val ext = name.substringAfterLast('.', "")
            val stem = Regex.escape(safeStem(if (ext.isEmpty()) name else name.substringBeforeLast('.')))
            val variants = Regex(stem + "( \\d+)?" + (if (ext.isEmpty()) "" else "\\." + Regex.escape(ext)), RegexOption.IGNORE_CASE)
            val tmpDigest by lazy { digest(tmp) }
            val same = dir.listFiles()?.firstOrNull { f ->
                f.isFile && variants.matches(f.name) && f.length() == tmp.length() && digest(f) == tmpDigest
            }
            same?.name ?: add(tmp.inputStream(), name)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Replaces the bytes of the stored attachment [name] with [input] (a picture changed in a
     * synced folder). Notes go on mentioning it by the same name.
     */
    suspend fun replace(name: String, input: InputStream) = withContext(Dispatchers.IO) {
        val target = file(name) ?: error("No attachment $name")
        val incoming = File(dir, ".incoming").apply { mkdirs() }
        val tmp = File.createTempFile("replace", ".tmp", incoming)
        try {
            val size = copyLimited(input, tmp)
            naming.withLock {
                // Atomic: a reader sees the old picture or the new one, never part of one.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                dao.setSize(target.name, size)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun copyLimited(input: InputStream, to: File): Long {
        var size = 0L
        input.use { src ->
            to.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > MAX_BYTES) throw AttachmentTooLargeException()
                    out.write(buffer, 0, n)
                }
            }
        }
        return size
    }

    private fun digest(file: File): String = Sha256.of(file.inputStream())

    /**
     * [stem] as a file name that a wikilink can name too: valid on every file system
     * ([FileNames.safe]), and without `[ ] | # ^`, which end or split a `![[name]]`.
     */
    private fun safeStem(stem: String) = FileNames.safe(stem.replace(LINK_BREAKING, " "), fallback = "Attachment")

    /** [preferred] as a valid file name not yet taken (ignoring case): "x.png", "x 1.png", "x 2.png"... */
    private suspend fun uniqueName(preferred: String): String {
        val ext = preferred.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(10)
        val base = safeStem(if (ext.isEmpty()) preferred else preferred.substringBeforeLast('.'))
        fun candidate(n: Int) = (if (n == 0) base else "$base $n") + if (ext.isEmpty()) "" else ".$ext"
        var n = 0
        while (file(candidate(n)) != null || dao.get(candidate(n)) != null) n++
        return candidate(n)
    }

    /** The names of all stored attachments. */
    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        (dao.all().map { it.name } + dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty())
            .distinctBy { it.lowercase() }
    }

    /**
     * Keeps the attachments whose lower-cased names are [mentioned] (by any note or version)
     * and removes those nothing has mentioned for [GRACE_MS]. Files without a row (a crash
     * between storing and recording) are recorded; rows without a file are dropped.
     */
    suspend fun collectGarbage(mentioned: Set<String>) = withContext(Dispatchers.IO) {
        val now = clock()
        // Under the naming lock, so that no add is between storing a file and recording it.
        naming.withLock {
            db.withTransaction {
                val rows = dao.all().associateBy { it.name.lowercase() }
                val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                for (f in files) {
                    if (f.name.lowercase() !in rows) dao.insert(AttachmentEntity(f.name, f.length(), now))
                }
                val present = files.map { it.name.lowercase() }.toSet()
                // In chunks: SQLite before 3.32 allows 999 variables per statement.
                rows.values.filter { it.name.lowercase() !in present }.map { it.name }.chunked(500).forEach { dao.delete(it) }
                val (used, unused) = dao.all().partition { it.name.lowercase() in mentioned }
                used.filter { it.orphanedAt != null }.map { it.name }.chunked(500).forEach { dao.setOrphaned(it, null) }
                unused.filter { it.orphanedAt == null }.map { it.name }.chunked(500).forEach { dao.setOrphaned(it, now) }
                val expired = unused.filter { it.orphanedAt != null && now - it.orphanedAt >= GRACE_MS }
                for (a in expired) File(dir, a.name).delete()
                expired.map { it.name }.chunked(500).forEach { dao.delete(it) }
            }
        }
        // Leftovers of adds a crash interrupted (an add still copying is younger than this).
        File(dir, ".incoming").listFiles()?.filter { now - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }
    }
}
