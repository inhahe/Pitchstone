package app.pitchstone.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pitchstone.data.AppDatabase
import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.NoteRepository
import app.pitchstone.export.FileDir
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class FolderSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var store: AttachmentStore
    private lateinit var engine: FolderSync
    private lateinit var vault: File
    private lateinit var pictures: File
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoteRepository(db) { now }
        pictures = Files.createTempDirectory("attachments").toFile()
        store = AttachmentStore(pictures, db) { now }
        engine = FolderSync(db, repo, store) { now }
        vault = Files.createTempDirectory("vault").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        vault.deleteRecursively()
        pictures.deleteRecursively()
    }

    private fun file(path: String) = File(vault, path)

    /** Writes a file in the folder as another app would, with a time of its own (later than all before). */
    private fun put(path: String, text: String) = putBytes(path, text.toByteArray())

    private fun putBytes(path: String, bytes: ByteArray) {
        now += 60_000
        val f = file(path)
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(now)
    }

    private suspend fun sync(gone: GoneFiles = GoneFiles.ASK, busy: (Long) -> Boolean = { false }): SyncReport {
        now += 60_000
        return engine.sync(FileDir(vault), updateLinks = true, busy = busy, gone = gone)
    }

    private suspend fun note(title: String) = repo.allNotes().single { it.title == title }

    private suspend fun versions(id: Long) = repo.revisions(id).first().map { repo.revision(it.id)!!.body }

    @Test
    fun `a first sync writes notes as files and files as notes, and the next has nothing to do`() = runBlocking<Unit> {
        repo.create("Plan", "# Plan\n\nText", folder = "Work")
        put("Ideas.md", "ideas")
        put("Sub/Deep.md", "deep")
        put(".obsidian/Hidden.md", "settings, not a note")
        put("Sub/notes.txt", "not markdown")

        val first = sync()
        assertThat(first.written).isEqualTo(1)
        assertThat(first.imported).isEqualTo(2)
        assertThat(first.problems).isEmpty()
        assertThat(file("Work/Plan.md").readText()).isEqualTo("# Plan\n\nText")
        assertThat(note("Ideas").body).isEqualTo("ideas")
        assertThat(note("Deep").folder).isEqualTo("Sub")
        assertThat(repo.allNotes().map { it.title }).containsExactly("Plan", "Ideas", "Deep")

        val second = sync()
        assertThat(second.changes).isEqualTo(0)
        assertThat(second.problems).isEmpty()
    }

    @Test
    fun `an edit on either side reaches the other`() = runBlocking<Unit> {
        val id = repo.create("Plan", "first")
        sync()
        repo.save(id, "Plan", "edited here")
        assertThat(sync().written).isEqualTo(1)
        assertThat(file("Plan.md").readText()).isEqualTo("edited here")

        put("Plan.md", "edited there")
        assertThat(sync().updated).isEqualTo(1)
        assertThat(repo.note(id)!!.body).isEqualTo("edited there")
        assertThat(sync().changes).isEqualTo(0)
    }

    @Test
    fun `a file's byte order mark and CRLF line ends are not changes, and the file keeps them`() = runBlocking<Unit> {
        val bytes = "﻿one\r\ntwo\r\n".toByteArray()
        putBytes("Windows.md", bytes)
        sync()
        assertThat(note("Windows").body).isEqualTo("one\ntwo\n")
        assertThat(sync().changes).isEqualTo(0)
        assertThat(file("Windows.md").readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `renaming and moving a note in the app renames and moves its file`() = runBlocking<Unit> {
        val id = repo.create("Old", "text")
        sync()
        repo.save(id, "New", "text")
        assertThat(sync().renamed).isEqualTo(1)
        assertThat(file("Old.md").exists()).isFalse()
        assertThat(file("New.md").readText()).isEqualTo("text")

        repo.move(listOf(id), "Archive/2026")
        sync()
        assertThat(file("New.md").exists()).isFalse()
        assertThat(file("Archive/2026/New.md").readText()).isEqualTo("text")

        // Renamed and edited at once.
        repo.save(id, "Newer", "more text")
        sync()
        assertThat(file("Archive/2026/New.md").exists()).isFalse()
        assertThat(file("Archive/2026/Newer.md").readText()).isEqualTo("more text")
        assertThat(sync().changes).isEqualTo(0)
    }

    @Test
    fun `renaming a file outside renames its note, and links to it follow`() = runBlocking<Unit> {
        val target = repo.create("Target", "the target")
        val linker = repo.create("Linker", "see [[Target]]")
        sync()
        File(vault, "Projects").mkdirs()
        assertThat(file("Target.md").renameTo(file("Projects/Renamed.md"))).isTrue()

        assertThat(sync().renamed).isEqualTo(1)
        assertThat(repo.note(target)!!.title).isEqualTo("Renamed")
        assertThat(repo.note(target)!!.folder).isEqualTo("Projects")
        assertThat(repo.note(linker)!!.body).isEqualTo("see [[Renamed]]")
        // The rewritten link reaches its file at the next sync.
        sync()
        assertThat(file("Linker.md").readText()).isEqualTo("see [[Renamed]]")
        assertThat(sync().changes).isEqualTo(0)
    }

    @Test
    fun `deleting a file outside trashes its note, and trashing a note moves its file to the folder's trash`() = runBlocking<Unit> {
        val a = repo.create("A", "a")
        val b = repo.create("B", "b")
        sync()
        assertThat(file("A.md").delete()).isTrue()
        assertThat(sync().trashedNotes).isEqualTo(1)
        assertThat(repo.note(a)!!.trashedAt).isNotNull()

        repo.moveToTrash(listOf(b))
        assertThat(sync().trashedFiles).isEqualTo(1)
        assertThat(file("B.md").exists()).isFalse()
        assertThat(file(".trash/B.md").readText()).isEqualTo("b")

        // Taken out of the trash: the file comes back.
        repo.restore(listOf(a))
        sync()
        assertThat(file("A.md").readText()).isEqualTo("a")
    }

    @Test
    fun `a file trashed into a folder trash that has its name already is numbered`() = runBlocking<Unit> {
        put(".trash/B.md", "older")
        val b = repo.create("B", "b")
        sync()
        repo.moveToTrash(listOf(b))
        sync()
        assertThat(file(".trash/B.md").readText()).isEqualTo("older")
        assertThat(file(".trash/B 1.md").readText()).isEqualTo("b")
    }

    @Test
    fun `a note changed on both sides keeps the newer text, and the other in its history`() = runBlocking<Unit> {
        val id = repo.create("C", "base")
        sync()
        now += 60_000
        repo.save(id, "C", "edited here")
        put("C.md", "edited there, later")
        val report = sync()
        assertThat(report.conflicts).containsExactly("C")
        assertThat(repo.note(id)!!.body).isEqualTo("edited there, later")
        assertThat(versions(id)).contains("edited here")

        // The other way round: the app's edit is the later one.
        put("C.md", "edited there")
        now += 60_000
        repo.save(id, "C", "edited here, later")
        sync()
        assertThat(repo.note(id)!!.body).isEqualTo("edited here, later")
        assertThat(file("C.md").readText()).isEqualTo("edited here, later")
        assertThat(versions(id)).contains("edited there")
        assertThat(sync().changes).isEqualTo(0)
    }

    @Test
    fun `many files deleted at once are held back until the user decides`() = runBlocking<Unit> {
        val ids = (1..FolderSync.TRASH_LIMIT + 1).map { repo.create("Note $it", "text $it") }
        // One file stays: the folder can be read, and the count alone holds the rest back.
        repo.create("Stays", "here")
        sync()
        for (i in 1..ids.size) assertThat(file("Note $i.md").delete()).isTrue()

        val asked = sync()
        assertThat(asked.heldBack).isEqualTo(ids.size)
        assertThat(asked.trashedNotes).isEqualTo(0)
        assertThat(repo.allNotes()).hasSize(ids.size + 1)

        assertThat(sync(GoneFiles.WRITE_AGAIN).written).isEqualTo(ids.size)
        assertThat(file("Note 1.md").readText()).isEqualTo("text 1")
        assertThat(sync().changes).isEqualTo(0)

        for (i in 1..ids.size) assertThat(file("Note $i.md").delete()).isTrue()
        assertThat(sync(GoneFiles.TRASH_NOTES).trashedNotes).isEqualTo(ids.size)
        assertThat(repo.allNotes().map { it.title }).containsExactly("Stays")
    }

    @Test
    fun `a folder that lists empty does not trash even a few notes`() = runBlocking<Unit> {
        repo.create("Only", "text")
        sync()
        assertThat(file("Only.md").delete()).isTrue()
        assertThat(sync().heldBack).isEqualTo(1)
        assertThat(repo.allNotes()).hasSize(1)
    }

    @Test
    fun `a note open in an editor is changed only once it is closed`() = runBlocking<Unit> {
        val id = repo.create("Open", "before")
        sync()
        put("Open.md", "changed outside")
        sync(busy = { it == id })
        assertThat(repo.note(id)!!.body).isEqualTo("before")
        sync()
        assertThat(repo.note(id)!!.body).isEqualTo("changed outside")
    }

    @Test
    fun `a note opened while a sync runs is not changed by it`() = runBlocking<Unit> {
        val id = repo.create("Opened", "before")
        sync()
        put("Opened.md", "changed outside")
        // Not open when the plan is made, open by the time its step runs.
        var asked = 0
        sync(busy = { asked++ > 0 })
        assertThat(repo.note(id)!!.body).isEqualTo("before")
    }

    @Test
    fun `a file too large for a note is reported, and its note left alone`() = runBlocking<Unit> {
        val id = repo.create("Big", "small")
        sync()
        put("Big.md", "x".repeat(NoteRepository.MAX_NOTE_BYTES + 1))
        val report = sync()
        assertThat(report.problems.single()).startsWith("Big.md: ")
        assertThat(repo.note(id)!!.body).isEqualTo("small")
        assertThat(repo.note(id)!!.trashedAt).isNull()
    }

    @Test
    fun `pictures notes show are copied both ways, and a picture changed in the folder replaces the app's`() = runBlocking<Unit> {
        // Obsidian's setting for where new attachments go.
        put(".obsidian/app.json", """{"attachmentFolderPath": "attachments"}""")
        val name = store.add(byteArrayOf(1, 2, 3).inputStream(), "here.png")
        repo.create("Shows a picture", "![[$name]]")
        put("From there.md", "![[there.png]]")
        putBytes("img/there.png", byteArrayOf(4, 5, 6))
        putBytes("img/unused.png", byteArrayOf(7))

        val report = sync()
        assertThat(report.pictures).isEqualTo(2)
        assertThat(report.problems).isEmpty()
        assertThat(file("attachments/here.png").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(store.file("there.png")!!.readBytes()).isEqualTo(byteArrayOf(4, 5, 6))
        assertThat(store.file("unused.png")).isNull()
        assertThat(sync().changes).isEqualTo(0)

        putBytes("img/there.png", byteArrayOf(9, 9))
        val changed = sync()
        assertThat(changed.problems).isEmpty()
        assertThat(changed.pictures).isEqualTo(1)
        assertThat(store.file("there.png")!!.readBytes()).isEqualTo(byteArrayOf(9, 9))
    }

    @Test
    fun `two different pictures of one name meeting for the first time are both left as they are`() = runBlocking<Unit> {
        store.add(byteArrayOf(1).inputStream(), "same.png")
        repo.create("Here", "![[same.png]]")
        putBytes("same.png", byteArrayOf(2))
        val report = sync()
        assertThat(report.problems.single()).startsWith("same.png: ")
        assertThat(store.file("same.png")!!.readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(file("same.png").readBytes()).isEqualTo(byteArrayOf(2))
    }

    @Test
    fun `a different picture of one name is never replaced, even when the folder's copy changes later`() = runBlocking<Unit> {
        store.add(byteArrayOf(1).inputStream(), "photo.png")
        repo.create("Here", "![[photo.png]]")
        putBytes("photo.png", byteArrayOf(2))
        sync()
        // Still reported while it lasts.
        assertThat(sync().problems.single()).startsWith("photo.png: ")
        putBytes("photo.png", byteArrayOf(3))
        assertThat(sync().problems.single()).startsWith("photo.png: ")
        assertThat(store.file("photo.png")!!.readBytes()).isEqualTo(byteArrayOf(1))
        // Made the same: synced as one from then on.
        putBytes("photo.png", byteArrayOf(1))
        assertThat(sync().problems).isEmpty()
        putBytes("photo.png", byteArrayOf(4))
        sync()
        assertThat(store.file("photo.png")!!.readBytes()).isEqualTo(byteArrayOf(4))
    }

    @Test
    fun `a file moved outside keeps the note's title where the name still suits it`() = runBlocking<Unit> {
        val id = repo.create("Meeting 10/23", "notes")
        val linker = repo.create("Linker", "see [[Meeting 10/23]]")
        sync()
        assertThat(file("Meeting 10 23.md").exists()).isTrue()
        File(vault, "Archive").mkdirs()
        assertThat(file("Meeting 10 23.md").renameTo(file("Archive/Meeting 10 23.md"))).isTrue()
        sync()
        assertThat(repo.note(id)!!.title).isEqualTo("Meeting 10/23")
        assertThat(repo.note(id)!!.folder).isEqualTo("Archive")
        assertThat(repo.note(linker)!!.body).isEqualTo("see [[Meeting 10/23]]")
    }

    @Test
    fun `a folder in the synced folder is the app's folder of that file name`() = runBlocking<Unit> {
        repo.create("Old", "x", folder = "Q&A: misc")
        sync()
        assertThat(file("Q&A misc/Old.md").exists()).isTrue()
        put("Q&A misc/New.md", "new")
        put("Elsewhere.md", "moved")
        sync()
        assertThat(note("New").folder).isEqualTo("Q&A: misc")
        // Moved into it outside: into the app's folder too.
        assertThat(file("Elsewhere.md").renameTo(file("Q&A misc/Elsewhere.md"))).isTrue()
        sync()
        assertThat(note("Elsewhere").folder).isEqualTo("Q&A: misc")
    }

    @Test
    fun `a folder that lists empty changes nothing, not even pairs, until it is back`() = runBlocking<Unit> {
        repo.create("A", "a")
        val b = repo.create("B", "b")
        val c = repo.create("C", "c")
        sync()
        repo.moveToTrash(listOf(b))
        repo.save(c, "C", "c edited")
        // The folder is away (a card taken out): its files are elsewhere for a while.
        val away = Files.createTempDirectory("away").toFile()
        for (name in listOf("A.md", "B.md", "C.md")) assertThat(file(name).renameTo(File(away, name))).isTrue()
        assertThat(sync().heldBack).isEqualTo(2)
        assertThat(file("C.md").exists()).isFalse()
        for (name in listOf("A.md", "B.md", "C.md")) assertThat(File(away, name).renameTo(file(name))).isTrue()
        away.delete()

        sync()
        assertThat(repo.allNotes().map { it.title }).containsExactly("A", "C")
        assertThat(file(".trash/B.md").exists()).isTrue()
        assertThat(file("C.md").readText()).isEqualTo("c edited")
        assertThat(vault.list()!!.filter { it.endsWith(".md") }).containsExactly("A.md", "C.md")
    }

    @Test
    fun `the preview of a first sync counts what it would do, and does nothing`() = runBlocking<Unit> {
        repo.create("Only here", "a")
        repo.create("Both", "same")
        repo.create("Differs", "app")
        put("Both.md", "same")
        put("Differs.md", "folder")
        put("Only there.md", "b")
        assertThat(engine.preview(FileDir(vault))).isEqualTo(SyncPreview(toFolder = 1, fromFolder = 1, inBoth = 2, differing = 1))
        assertThat(file("Only here.md").exists()).isFalse()
        assertThat(repo.allNotes()).hasSize(3)
    }

    @Test
    fun `a version 2 database opens with its notes, and gains the sync table`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("migration-test-2.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.pitchstone.data.AppDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { v2 ->
            for (entity in schema["entities"]!!.jsonArray) {
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                v2.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { v2.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { v2.execSQL(it.jsonPrimitive.content) }
            v2.execSQL(
                "INSERT INTO notes (id, title, body, folder, createdAt, updatedAt, pinned, trashedAt, searchText, snippet, linkedTitle) " +
                    "VALUES (1, 'Kept', 'Body', '', 1, 2, 0, NULL, 'kept body', 'Body', 'Kept')",
            )
            v2.version = 2
        }
        val v3 = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            assertThat(v3.notes().note(1)!!.body).isEqualTo("Body")
            assertThat(v3.sync().all()).isEmpty()
            v3.sync().put(SyncEntryEntity("Kept.md", noteId = 1, hash = "h", modified = 5, size = 4))
            assertThat(v3.sync().all().single().path).isEqualTo("Kept.md")
            v3.sync().remove("KEPT.MD")
            assertThat(v3.sync().all()).isEmpty()
        } finally {
            v3.close()
            dbFile.delete()
        }
    }
}
