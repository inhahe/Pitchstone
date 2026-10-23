package app.inkdown.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

private const val SUMMARY = "id, title, folder, createdAt, updatedAt, pinned, trashedAt, snippet"

@Dao
interface NoteDao {
    // ---- Notes ----------------------------------------------------------------------------

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeNote(id: Long): Flow<NoteEntity?>

    @Query("SELECT $SUMMARY FROM notes WHERE trashedAt IS NULL")
    fun observeSummaries(): Flow<List<NoteSummary>>

    @Query("SELECT $SUMMARY FROM notes WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<NoteSummary>>

    @RawQuery(observedEntities = [NoteEntity::class])
    fun search(query: SupportSQLiteQuery): Flow<List<NoteSummary>>

    @Query("SELECT * FROM notes WHERE trashedAt IS NULL")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun notes(ids: Collection<Long>): List<NoteEntity>

    @Query("SELECT title FROM notes WHERE trashedAt IS NULL")
    fun observeTitles(): Flow<List<String>>

    @Query("SELECT id, title, folder, updatedAt FROM notes WHERE trashedAt IS NULL")
    suspend fun allRefs(): List<NoteRef>

    /** Moves notes from folder [old] (and below) to [new], keeping their place under it. */
    @Query(
        "UPDATE notes SET folder = :new || substr(folder, :oldLength + 1) " +
            "WHERE folder = :old OR folder LIKE :prefix ESCAPE '\\'",
    )
    suspend fun renameNoteFolders(old: String, new: String, oldLength: Int, prefix: String)

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query(
        "UPDATE notes SET title = :title, body = :body, updatedAt = :updatedAt, " +
            "searchText = :searchText, snippet = :snippet WHERE id = :id",
    )
    suspend fun updateContent(id: Long, title: String, body: String, updatedAt: Long, searchText: String, snippet: String)

    @Query("UPDATE notes SET pinned = :pinned WHERE id IN (:ids)")
    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean)

    @Query("UPDATE notes SET folder = :folder WHERE id IN (:ids)")
    suspend fun moveNotes(ids: Collection<Long>, folder: String)

    @Query("UPDATE notes SET trashedAt = :at WHERE id IN (:ids)")
    suspend fun setTrashed(ids: Collection<Long>, at: Long?)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("DELETE FROM notes WHERE trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun purgeTrash(before: Long): Int

    @Query("SELECT id FROM notes WHERE trashedAt IS NOT NULL")
    suspend fun trashedIds(): List<Long>

    @Query("SELECT COUNT(*) FROM notes WHERE trashedAt IS NULL")
    suspend fun countNotes(): Int

    // ---- Folders --------------------------------------------------------------------------

    @Query("SELECT * FROM folders ORDER BY path")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun folders(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE path = :path OR path LIKE :prefix ESCAPE '\\'")
    suspend fun deleteFolderTree(path: String, prefix: String)

    @Query("SELECT id FROM notes WHERE folder = :path OR folder LIKE :prefix ESCAPE '\\'")
    suspend fun notesInFolderTree(path: String, prefix: String): List<Long>

    @Query("SELECT DISTINCT folder FROM notes WHERE folder != ''")
    suspend fun usedFolders(): List<String>

    // ---- Tags and links -------------------------------------------------------------------

    @Query("DELETE FROM note_tags WHERE noteId = :noteId")
    suspend fun clearTags(noteId: Long)

    @Upsert
    suspend fun insertTags(tags: List<NoteTagEntity>)

    @Query("DELETE FROM note_links WHERE noteId = :noteId")
    suspend fun clearLinks(noteId: Long)

    @Upsert
    suspend fun insertLinks(links: List<NoteLinkEntity>)

    @Query(
        "SELECT t.tag AS tag, MIN(t.display) AS display, COUNT(*) AS count FROM note_tags t " +
            "JOIN notes n ON n.id = t.noteId WHERE n.trashedAt IS NULL GROUP BY t.tag ORDER BY t.tag",
    )
    fun observeTags(): Flow<List<TagCount>>

    @Query(
        "SELECT DISTINCT n.id, n.title, n.folder, n.createdAt, n.updatedAt, n.pinned, n.trashedAt, n.snippet " +
            "FROM notes n JOIN note_tags t ON t.noteId = n.id " +
            "WHERE n.trashedAt IS NULL AND (t.tag = :tag OR t.tag LIKE :prefix ESCAPE '\\')",
    )
    fun observeTagged(tag: String, prefix: String): Flow<List<NoteSummary>>

    @Query("SELECT display FROM note_tags WHERE noteId = :noteId ORDER BY tag")
    suspend fun tagsOf(noteId: Long): List<String>

    /** Notes whose wikilinks point at any of [targets] (lower-cased note names). */
    @Query(
        "SELECT DISTINCT n.id, n.title, n.folder, n.createdAt, n.updatedAt, n.pinned, n.trashedAt, n.snippet " +
            "FROM notes n JOIN note_links l ON l.noteId = n.id " +
            "WHERE n.trashedAt IS NULL AND l.target IN (:targets) AND n.id != :exclude ORDER BY n.title",
    )
    suspend fun backlinks(targets: Collection<String>, exclude: Long): List<NoteSummary>

    // ---- Revisions ------------------------------------------------------------------------

    @Insert
    suspend fun insertRevision(revision: RevisionEntity): Long

    @Query("SELECT MAX(savedAt) FROM revisions WHERE noteId = :noteId")
    suspend fun lastRevisionTime(noteId: Long): Long?

    @Query("SELECT id, noteId, title, savedAt, length(body) AS length FROM revisions WHERE noteId = :noteId ORDER BY savedAt DESC")
    fun observeRevisions(noteId: Long): Flow<List<RevisionSummary>>

    @Query("SELECT * FROM revisions WHERE id = :id")
    suspend fun revision(id: Long): RevisionEntity?

    /** Keeps the newest [keep] revisions of a note and drops older ones past [before]. */
    @Query(
        "DELETE FROM revisions WHERE noteId = :noteId AND savedAt < :before AND id NOT IN " +
            "(SELECT id FROM revisions WHERE noteId = :noteId ORDER BY savedAt DESC LIMIT :keep)",
    )
    suspend fun pruneRevisions(noteId: Long, before: Long, keep: Int)

    @Query(
        "DELETE FROM revisions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM revisions WHERE noteId = :noteId ORDER BY savedAt DESC LIMIT :max)",
    )
    suspend fun capRevisions(noteId: Long, max: Int)

    @Transaction
    suspend fun replaceIndex(noteId: Long, tags: List<NoteTagEntity>, links: List<NoteLinkEntity>) {
        clearTags(noteId)
        if (tags.isNotEmpty()) insertTags(tags)
        clearLinks(noteId)
        if (links.isNotEmpty()) insertLinks(links)
    }
}

data class NoteRef(
    val id: Long,
    val title: String,
    val folder: String,
    val updatedAt: Long,
)
