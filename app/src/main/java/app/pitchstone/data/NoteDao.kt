package app.pitchstone.data

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

/** A note in folder :old or below it: exact, case-sensitive comparison (LIKE ignores case). */
private const val IN_FOLDER_TREE = "(folder = :old OR substr(folder, 1, length(:old) + 1) = :old || '/')"

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

    @Query("SELECT title, folder FROM notes WHERE trashedAt IS NULL")
    fun observeLinkTargets(): Flow<List<NoteTitle>>

    @Query("SELECT id, title, folder, updatedAt FROM notes WHERE trashedAt IS NULL")
    suspend fun allRefs(): List<NoteRef>

    /**
     * Moves notes from folder [old] (and below) to [new], keeping their place under it. Folder
     * matching is exact (case-sensitive), and lengths are measured by SQLite on both sides.
     */
    @Query("UPDATE notes SET folder = :new || substr(folder, length(:old) + 1) WHERE $IN_FOLDER_TREE")
    suspend fun renameNoteFolders(old: String, new: String)

    /** Notes renamed since links to them were last updated (see [NoteEntity.linkedTitle]). */
    @Query("SELECT * FROM notes WHERE title != linkedTitle AND trashedAt IS NULL")
    suspend fun pendingRenames(): List<NoteEntity>

    @Query("UPDATE notes SET linkedTitle = :title WHERE id = :id")
    suspend fun setLinkedTitle(id: Long, title: String)

    @Query("SELECT COUNT(*) FROM revisions WHERE noteId = :noteId")
    suspend fun revisionCount(noteId: Long): Int

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

    // ---- Folders --------------------------------------------------------------------------

    @Query("SELECT * FROM folders ORDER BY path")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun folders(): List<FolderEntity>

    /** The folders of notes not in the trash (the ones the folder list shows). */
    @Query("SELECT DISTINCT folder FROM notes WHERE trashedAt IS NULL")
    suspend fun noteFolders(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE path = :old OR substr(path, 1, length(:old) + 1) = :old || '/'")
    suspend fun deleteFolderTree(old: String)

    @Query("SELECT id FROM notes WHERE $IN_FOLDER_TREE")
    suspend fun notesInFolderTree(old: String): List<Long>

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

    /** Notes' bodies in id order, from after [after], [limit] at a time (trashed ones too). */
    @Query("SELECT id, body FROM notes WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun bodiesAfter(after: Long, limit: Int): List<IdBody>

    // ---- Revisions ------------------------------------------------------------------------

    /** Earlier versions' bodies in id order, from after [after], [limit] at a time. */
    @Query("SELECT id, body FROM revisions WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun revisionBodiesAfter(after: Long, limit: Int): List<IdBody>

    /** Note [noteId]'s latest earlier version, or null. */
    @Query("SELECT * FROM revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC LIMIT 1")
    suspend fun lastRevision(noteId: Long): RevisionEntity?

    @Insert
    suspend fun insertRevision(revision: RevisionEntity): Long

    @Query("SELECT MAX(savedAt) FROM revisions WHERE noteId = :noteId")
    suspend fun lastRevisionTime(noteId: Long): Long?

    @Query("SELECT id, noteId, title, savedAt, length(body) AS length FROM revisions WHERE noteId = :noteId ORDER BY savedAt DESC")
    fun observeRevisions(noteId: Long): Flow<List<RevisionSummary>>

    @Query("SELECT * FROM revisions WHERE id = :id")
    suspend fun revision(id: Long): RevisionEntity?

    @Query("DELETE FROM revisions WHERE noteId = :noteId AND id = :id")
    suspend fun deleteRevision(noteId: Long, id: Long): Int

    @Query("DELETE FROM revisions WHERE noteId = :noteId")
    suspend fun deleteRevisions(noteId: Long): Int

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

data class NoteTitle(val title: String, val folder: String)

data class NoteRef(
    val id: Long,
    val title: String,
    val folder: String,
    val updatedAt: Long,
)

data class IdBody(val id: Long, val body: String)
