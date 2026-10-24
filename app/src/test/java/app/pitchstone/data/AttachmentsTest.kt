package app.pitchstone.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pitchstone.export.ImportFile
import app.pitchstone.export.Importer
import app.pitchstone.markdown.Html
import app.pitchstone.markdown.LinkRewriter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class AttachmentsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var store: AttachmentStore
    private lateinit var dir: File
    private var now = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoteRepository(db) { now }
        dir = Files.createTempDirectory("attachments").toFile()
        store = AttachmentStore(dir, db) { now }
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun bytes(s: String) = s.toByteArray().inputStream()

    @Test
    fun `names are kept, made valid and unique ignoring case`() = runBlocking<Unit> {
        assertThat(store.add(bytes("a"), "Pasted image 1.png")).isEqualTo("Pasted image 1.png")
        assertThat(store.add(bytes("b"), "pasted image 1.PNG")).isEqualTo("pasted image 1 1.png")
        assertThat(store.add(bytes("c"), "a:b?.jpg")).isEqualTo("a b.jpg")
        assertThat(store.file("PASTED IMAGE 1.png")!!.readText()).isEqualTo("a")
        assertThat(store.file("some/folder/Pasted image 1.png")!!.readText()).isEqualTo("a")
        assertThat(store.file("../x.png")).isNull()
        assertThat(store.file(".incoming")).isNull()
    }

    @Test
    fun `an attachment over the limit is refused, and nothing is left behind`() = runBlocking<Unit> {
        val big = object : java.io.InputStream() {
            var left = AttachmentStore.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
        }
        assertThrows(AttachmentTooLargeException::class.java) { runBlocking { store.add(big, "big.png") } }
        assertThat(store.names()).isEmpty()
        assertThat(File(dir, ".incoming").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `the same picture imported twice is stored once, a different one under a new name`() = runBlocking<Unit> {
        assertThat(store.addOrReuse(bytes("one"), "pic.png")).isEqualTo("pic.png")
        assertThat(store.addOrReuse(bytes("one"), "pic.png")).isEqualTo("pic.png")
        assertThat(store.addOrReuse(bytes("two"), "pic.png")).isEqualTo("pic 1.png")
        assertThat(store.addOrReuse(bytes("two"), "PIC.png")).isEqualTo("pic 1.png")
        assertThat(store.names()).hasSize(2)
    }

    @Test
    fun `pictures nothing mentions go after thirty days, those mentioned stay`() = runBlocking<Unit> {
        val kept = store.add(bytes("k"), "kept.png")
        val inRevision = store.add(bytes("r"), "old.png")
        val unused = store.add(bytes("u"), "unused.png")
        val trashedNote = store.add(bytes("t"), "trashed.png")
        val note = repo.create("N", "![[kept.png]] and ![x](folder/old.png)")
        now += 1000
        repo.save(note, "N", "only ![[kept.png]] now") // old.png lives on in the version
        repo.moveToTrash(listOf(repo.create("T", "![[trashed.png]]")))
        // A file without a row (a crash mid-add) gets one.
        File(dir, "stray.png").writeText("s")

        repo.collectAttachmentGarbage(store)
        assertThat(store.names()).containsExactly(kept, inRevision, unused, trashedNote, "stray.png")
        now += AttachmentStore.GRACE_MS + 1
        repo.collectAttachmentGarbage(store)
        assertThat(store.names()).containsExactly(kept, inRevision, trashedNote)
        assertThat(File(dir, unused).exists()).isFalse()
    }

    @Test
    fun `mentions are found in embeds, links and image paths`() {
        val md = "![[Shot 1.png|300]] [[docs/Manual.pdf#p2]] ![a](pics/my%20cat.jpg \"t\") [x](<a b.png>)"
        assertThat(AttachmentStore.mentionedNames(md)).containsAtLeast("shot 1.png", "manual.pdf", "my cat.jpg", "a b.png")
    }

    @Test
    fun `renaming a picture follows embeds, folder paths and image links`() {
        val md = "![[pic.png]] ![[img/PIC.png|200]] ![x](assets/pic.png) ![y](<my pic.png>) ![z](<a/pic.png>) `![[pic.png]]`"
        val out = LinkRewriter.renameAttachment(md, "pic.png", "pic 1.png")
        assertThat(out).isEqualTo("![[pic 1.png]] ![[img/pic 1.png|200]] ![x](assets/pic%201.png) ![y](<my pic.png>) ![z](<a/pic 1.png>) `![[pic.png]]`")
        assertThat(LinkRewriter.renameAttachment("![y](<my pic.png>)", "my pic.png", "my pic 1.png")).isEqualTo("![y](<my pic 1.png>)")
    }

    @Test
    fun `a sized picture in a table cell is found and renamed by its name`() {
        // The editor writes `|` in a table cell as `\|`, Obsidian too.
        val md = "| a |\n| --- |\n| ![[pic.png\\|200]] |"
        assertThat(AttachmentStore.mentionedNames(md)).contains("pic.png")
        assertThat(LinkRewriter.renameAttachment(md, "pic.png", "pic 1.png")).isEqualTo("| a |\n| --- |\n| ![[pic 1.png\\|200]] |")
    }

    @Test
    fun `a picture's name in code is not renamed`() {
        val md = "![a](pic.png) `![b](pic.png)`\n\n```\n![c](pic.png)\n```\n"
        assertThat(LinkRewriter.renameAttachment(md, "pic.png", "pic 1.png"))
            .isEqualTo("![a](pic%201.png) `![b](pic.png)`\n\n```\n![c](pic.png)\n```\n")
    }

    @Test
    fun `pictures renamed together are each renamed once`() {
        // image.png becomes "image 1.png", the name another picture had: that one must not follow.
        val md = "![[image.png]] ![[image 1.png]] ![](image%201.png)"
        val out = LinkRewriter.renameAttachments(md, mapOf("image.png" to "image 1.png", "image 1.png" to "image 1 1.png"))
        assertThat(out).isEqualTo("![[image 1.png]] ![[image 1 1.png]] ![](image%201%201.png)")
    }

    @Test
    fun `an imported picture renamed to another's name does not take that one's mentions`() = runBlocking<Unit> {
        store.add(bytes("other"), "a.png")
        val files = listOf(
            // "a.png" is looked at first (HashSet order), and becomes "a 1.png", the next one's name.
            ImportFile("N", "md", "", null, 30, { bytes("![[a.png]] ![[a 1.png]]") }),
            ImportFile("a", "png", "", null, 1, { bytes("A") }),
            ImportFile("a 1", "png", "", null, 1, { bytes("B") }),
        )
        Importer.import(repo, files, "", keepFolders = true, txtAsMarkdown = false, attachments = store)
        assertThat(repo.allNotes().single().body).isEqualTo("![[a 1.png]] ![[a 1 1.png]]")
        assertThat(store.file("a 1.png")!!.readText()).isEqualTo("A")
        assertThat(store.file("a 1 1.png")!!.readText()).isEqualTo("B")
    }

    @Test
    fun `stored names leave out what would break a wikilink`() = runBlocking<Unit> {
        assertThat(store.add(bytes("a"), "Photo [1] #2 ^x|y.jpg")).isEqualTo("Photo 1 2 x y.jpg")
        // And the same bytes under that name are found again.
        assertThat(store.addOrReuse(bytes("a"), "Photo [1] #2 ^x|y.jpg")).isEqualTo("Photo 1 2 x y.jpg")
    }

    @Test
    fun `a picture a version mentions percent-encoded is kept`() = runBlocking<Unit> {
        val name = store.add(bytes("p"), "Pasted image 1.png")
        val note = repo.create("N", "![](attachments/Pasted%20image%201.png)")
        now += 1000
        repo.save(note, "N", "") // only the version mentions it now
        repo.collectAttachmentGarbage(store)
        now += AttachmentStore.GRACE_MS + 1
        repo.collectAttachmentGarbage(store)
        assertThat(store.names()).containsExactly(name)
    }

    @Test
    fun `a picture that cannot be stored is reported, and its note imported`() = runBlocking<Unit> {
        val big = object : java.io.InputStream() {
            var left = AttachmentStore.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
        }
        val files = listOf(
            ImportFile("Note", "md", "", null, 20, { bytes("![[huge.png]] text") }),
            ImportFile("huge", "png", "", null, AttachmentStore.MAX_BYTES + 1, { big }),
        )
        val result = Importer.import(repo, files, "", keepFolders = true, txtAsMarkdown = false, attachments = store)
        assertThat(result.imported).isEqualTo(1)
        assertThat(result.failed).isEmpty()
        assertThat(result.failedPictures.single().first).isEqualTo("huge.png")
        assertThat(repo.allNotes().single().body).isEqualTo("![[huge.png]] text")
    }

    @Test
    fun `an HTML page holds pictures up to a total, and names the rest`() = runBlocking<Unit> {
        val size = (Html.INLINE_TOTAL_BYTES / 3 + 1).toInt()
        for (n in 1..3) store.add(ByteArray(size).inputStream(), "p$n.png")
        val page = Html.document("T", "![[p1.png]] ![[p2.png]] ![[p3.png]]", store::file)
        assertThat(Regex("data:image/png").findAll(page).count()).isEqualTo(2)
        assertThat(page).contains("[p3.png]")
    }

    @Test
    fun `importing a vault brings the pictures its notes show, once`() = runBlocking<Unit> {
        store.add(bytes("other"), "cat.png") // a different picture already has the name
        fun file(name: String, folder: String, content: String) = ImportFile(
            title = name.substringBeforeLast('.'), extension = name.substringAfterLast('.'), folder = folder,
            lastModified = null, size = content.length.toLong(), open = { bytes(content) },
        )
        val vault = listOf(
            file("Pets.md", "", "My ![[cat.png]] and ![](att/dog.png)"),
            file("cat.png", "att", "CAT"),
            file("dog.png", "att", "DOG"),
            file("unused.png", "att", "X"),
        )
        val first = Importer.import(repo, vault, "", keepFolders = true, txtAsMarkdown = false, attachments = store)
        assertThat(first.imported).isEqualTo(1)
        assertThat(first.pictures).isEqualTo(2)
        val pets = repo.allNotes().single()
        assertThat(pets.body).isEqualTo("My ![[cat 1.png]] and ![](att/dog.png)")
        assertThat(store.file("cat 1.png")!!.readText()).isEqualTo("CAT")
        assertThat(store.file("unused.png")).isNull()
        val again = Importer.import(repo, vault, "", keepFolders = true, txtAsMarkdown = false, attachments = store)
        assertThat(again.imported).isEqualTo(0)
        assertThat(again.alreadyPresent).isEqualTo(1)
        assertThat(again.pictures).isEqualTo(0)
        assertThat(store.names()).hasSize(3)
    }

    @Test
    fun `a markdown export writes the pictures its notes show`() = runBlocking<Unit> {
        store.add(bytes("PNG"), "shown.png")
        store.add(bytes("NO"), "hidden.png")
        val note = app.pitchstone.export.ExportNote("N", "![[shown.png]] [[Other note]]", "", 0, 0)
        val request = app.pitchstone.export.ExportRequest(
            format = ExportFormat.MARKDOWN, layout = ExportLayout.FILE_PER_NOTE, keepFolders = true,
            titleHeading = false, metadata = false, textKeepsMarkdown = false, singleFileName = "",
        )
        val files = app.pitchstone.export.ExportPlanner.plan(listOf(note), request, attachments = store::file)
        val picture = files.single { it.source != null }
        assertThat(picture.folder).containsExactly("attachments")
        assertThat("${picture.baseName}.${picture.extension}").isEqualTo("shown.png")
        assertThat(picture.source!!().readBytes().decodeToString()).isEqualTo("PNG")
        val text = app.pitchstone.export.ExportPlanner.plan(listOf(note), request.copy(format = ExportFormat.TEXT), attachments = store::file)
        assertThat(text.none { it.source != null }).isTrue()
    }

    @Test
    fun `an HTML page holds its pictures, an email names them`() = runBlocking<Unit> {
        store.add(bytes("PNG"), "shown.png")
        val page = Html.document("T", "![[shown.png]] ![[missing.png]] ![c](a/shown.png)", store::file)
        assertThat(page).contains("<img src=\"data:image/png;base64,UE5H\" alt=\"shown.png\" />")
        assertThat(page).contains("[missing.png]")
        assertThat(page).contains("<img src=\"data:image/png;base64,UE5H\" alt=\"c\" />")
        val email = Html.emailBody("![[shown.png]] ![w](https://example.com/w.png)")
        assertThat(email).contains("[shown.png]")
        assertThat(email).doesNotContain("data:")
        assertThat(email).contains("src=\"https://example.com/w.png\"")
    }

    @Test
    fun `a version 1 database opens with its notes, and gains the attachments table`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/app.pitchstone.data.AppDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            for (entity in schema["entities"]!!.jsonArray) {
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                v1.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { v1.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { v1.execSQL(it.jsonPrimitive.content) }
            v1.execSQL(
                "INSERT INTO notes (id, title, body, folder, createdAt, updatedAt, pinned, trashedAt, searchText, snippet, linkedTitle) " +
                    "VALUES (1, 'Kept', 'Body', '', 1, 2, 0, NULL, 'kept body', 'Body', 'Kept')",
            )
            v1.version = 1
        }
        val v2 = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        try {
            assertThat(v2.notes().note(1)!!.body).isEqualTo("Body")
            assertThat(v2.attachments().all()).isEmpty()
            v2.attachments().insert(AttachmentEntity("a.png", 1, 1))
            assertThat(v2.attachments().get("A.PNG")!!.name).isEqualTo("a.png")
        } finally {
            v2.close()
            file.delete()
        }
    }
}
