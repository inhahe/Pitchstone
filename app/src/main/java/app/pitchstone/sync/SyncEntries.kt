package app.pitchstone.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.security.MessageDigest

/**
 * What a file in the synced folder and its counterpart in the app were at the last sync:
 * for a note's file ([noteId]), or a picture's ([attachment], its name in the store). [hash]
 * is the text's (or the picture's bytes'), which both sides had then; [modified] and [size]
 * are the file's, so that an untouched file need not be read again.
 */
@Entity(tableName = "sync_entries")
data class SyncEntryEntity(
    /** Relative to the synced folder, with `/`; unique ignoring case, as on phone storage. */
    @PrimaryKey @ColumnInfo(collate = ColumnInfo.NOCASE) val path: String,
    val noteId: Long? = null,
    val attachment: String? = null,
    val hash: String,
    val modified: Long?,
    val size: Long,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_entries")
    suspend fun all(): List<SyncEntryEntity>

    @Upsert
    suspend fun put(entry: SyncEntryEntity)

    @Query("DELETE FROM sync_entries WHERE path = :path")
    suspend fun remove(path: String)

    /** Forgets every pair: syncing with another folder starts afresh. */
    @Query("DELETE FROM sync_entries")
    suspend fun clear()
}

/** The hashes sync compares: SHA-256 of a note's text (UTF-8), or of a file's bytes. */
object SyncHash {
    fun of(text: String): String = of(text.toByteArray(Charsets.UTF_8))

    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
