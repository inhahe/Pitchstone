package app.pitchstone.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.pitchstone.sync.SyncDao
import app.pitchstone.sync.SyncEntryEntity

/**
 * The notes database. Each version's schema is exported to app/schemas; versions after 1 are
 * reached by automatic migrations, tested in MigrationTest.
 *
 * 2: attachments (images stored with notes).
 * 3: sync_entries (folder sync).
 */
@Database(
    entities = [
        NoteEntity::class, FolderEntity::class, NoteTagEntity::class, NoteLinkEntity::class, RevisionEntity::class,
        AttachmentEntity::class, SyncEntryEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun attachments(): AttachmentDao
    abstract fun sync(): SyncDao

    companion object {
        const val FILE_NAME = "pitchstone.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                // Write-ahead logging: readers never block the writer, and a crash mid-write
                // leaves the last committed state intact.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
