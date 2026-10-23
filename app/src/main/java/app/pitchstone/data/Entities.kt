package app.pitchstone.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note. [body] is the note's markdown, exactly as the editor wrote it (or as imported).
 * [folder] is a slash-separated path ("" is the top level). [searchText] and [snippet] are
 * derived from title and body on every save, for searching and for the note list.
 */
@Entity(
    tableName = "notes",
    indices = [Index("folder"), Index("updatedAt"), Index("trashedAt")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val folder: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /** When the note was moved to the trash; null while it is not in the trash. */
    val trashedAt: Long? = null,
    /** Lower-cased title and plain text, for substring search. */
    val searchText: String,
    /** The start of the note's plain text, for the note list. */
    val snippet: String,
    /**
     * The title that [[links]] in other notes were last updated for. While it differs from
     * [title], the note has been renamed and links to it still use the old name; kept in the
     * database so that the update happens even if the app is stopped first.
     */
    val linkedTitle: String = title,
)

/** A folder, recorded so that empty folders exist too. Paths have no leading or trailing slash. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val path: String,
    val createdAt: Long,
)

/** A #tag that occurs in a note (stored without the #, lower-cased for lookup). */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tag"],
    indices = [Index("tag")],
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
)
data class NoteTagEntity(
    val noteId: Long,
    val tag: String,
    /** The tag as first written in the note, for display. */
    val display: String,
)

/** A [[wikilink]] from a note; [target] is the linked note's name, lower-cased. */
@Entity(
    tableName = "note_links",
    primaryKeys = ["noteId", "target"],
    indices = [Index("target")],
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
)
data class NoteLinkEntity(
    val noteId: Long,
    val target: String,
)

/** An earlier version of a note, kept so that any edit can be undone later. */
@Entity(
    tableName = "revisions",
    indices = [Index(value = ["noteId", "savedAt"])],
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
)
data class RevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val body: String,
    /** When this version was replaced by a newer one. */
    val savedAt: Long,
)

/** The columns of a note the list needs: everything but the body. */
data class NoteSummary(
    val id: Long,
    val title: String,
    val folder: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val trashedAt: Long?,
    val snippet: String,
)

data class TagCount(
    val tag: String,
    val display: String,
    @ColumnInfo(name = "count") val count: Int,
)

data class RevisionSummary(
    val id: Long,
    val noteId: Long,
    val title: String,
    val savedAt: Long,
    val length: Int,
)
