package app.inkdown.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoteRepository(db) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `saving keeps earlier versions at most every ten minutes`() = runBlocking<Unit> {
        val id = repo.create("T", "v1")
        assertThat(repo.save(id, "T", "v1")).isEqualTo(NoteRepository.SaveResult.UNCHANGED)
        now += 1000
        repo.save(id, "T", "v2") // first change: v1 is kept
        now += 1000
        repo.save(id, "T", "v3") // too soon: v2 is not kept
        now += NoteRepository.REVISION_INTERVAL_MS
        repo.save(id, "T", "v4") // v3 is kept
        val revisions = repo.revisions(id).first()
        val bodies = revisions.map { repo.revision(it.id)!!.body }
        assertThat(bodies).containsExactly("v3", "v1").inOrder()
        assertThat(repo.note(id)!!.body).isEqualTo("v4")
    }

    @Test
    fun `a new empty note keeps no revision of its emptiness`() = runBlocking<Unit> {
        val id = repo.create()
        repo.save(id, "Title", "text")
        assertThat(repo.revisions(id).first()).isEmpty()
    }

    @Test
    fun `emptying a note keeps what it held, however recent the last version`() = runBlocking<Unit> {
        val id = repo.create("T", "v1")
        now += 1000
        repo.save(id, "T", "v2") // v1 kept
        now += 1000
        repo.save(id, "T", "v3, written since") // too soon for a version
        now += 1000
        repo.save(id, "", "")
        val bodies = repo.revisions(id).first().map { repo.revision(it.id)!!.body }
        assertThat(bodies).containsExactly("v3, written since", "v1").inOrder()
    }

    @Test
    fun `cutting a note by more than half keeps what it held`() = runBlocking<Unit> {
        val id = repo.create("T", "x".repeat(100))
        now += 1000
        repo.save(id, "T", "x".repeat(101)) // first change: the original is kept
        now += 1000
        repo.save(id, "T", "x".repeat(60)) // too soon, and not cut by half
        now += 1000
        repo.save(id, "T", "x".repeat(20)) // cut by more than half
        val lengths = repo.revisions(id).first().map { repo.revision(it.id)!!.body.length }
        assertThat(lengths).containsExactly(60, 100).inOrder()
    }

    @Test
    fun `versions can be deleted one at a time, put back, or all at once`() = runBlocking<Unit> {
        val id = repo.create("T", "v1")
        val other = repo.create("O", "o1")
        now += 1000
        repo.save(id, "T", "v2")
        repo.save(other, "O", "o2")
        now += NoteRepository.REVISION_INTERVAL_MS
        repo.save(id, "T", "v3")
        val (newest, oldest) = repo.revisions(id).first()
        val otherRevision = repo.revisions(other).first().single()
        assertThat(repo.deleteRevision(id, otherRevision.id)).isNull() // not this note's
        val deleted = repo.deleteRevision(id, oldest.id)!!
        assertThat(deleted.body).isEqualTo("v1")
        assertThat(repo.revisions(id).first().map { it.id }).containsExactly(newest.id)
        repo.undeleteRevision(deleted)
        assertThat(repo.revisions(id).first().map { it.id }).containsExactly(newest.id, oldest.id).inOrder()
        assertThat(repo.deleteAllRevisions(id)).isEqualTo(2)
        assertThat(repo.revisions(id).first()).isEmpty()
        assertThat(repo.note(id)!!.body).isEqualTo("v3")
        assertThat(repo.revisions(other).first()).hasSize(1)
    }

    @Test
    fun `only a note never written is pristine`() = runBlocking<Unit> {
        val fresh = repo.create()
        assertThat(repo.isPristine(fresh)).isTrue()
        val written = repo.create()
        now += 1000
        repo.save(written, "T", "text")
        now += 1000
        repo.save(written, "", "")
        assertThat(repo.isPristine(written)).isFalse()
        assertThat(repo.isPristine(-1)).isFalse()
    }

    @Test
    fun `restoring a version keeps the current one`() = runBlocking<Unit> {
        val id = repo.create("T", "old")
        now += 1000
        repo.save(id, "T", "new")
        val rev = repo.revisions(id).first().single()
        now += 1000
        assertThat(repo.restoreRevision(id, rev.id)).isTrue()
        assertThat(repo.note(id)!!.body).isEqualTo("old")
        val bodies = repo.revisions(id).first().map { repo.revision(it.id)!!.body }
        assertThat(bodies).contains("new")
    }

    @Test
    fun `search matches every word, case-insensitively, wildcards literal`() = runBlocking<Unit> {
        repo.create("Shopping", "Buy MILK and eggs")
        repo.create("Budget", "50% of income")
        repo.create("Other", "milky way")
        assertThat(repo.search("milk eggs").first().map { it.title }).containsExactly("Shopping")
        assertThat(repo.search("milk").first().map { it.title }).containsExactly("Shopping", "Other")
        assertThat(repo.search("50%").first().map { it.title }).containsExactly("Budget")
        assertThat(repo.search("5_%").first()).isEmpty()
        assertThat(repo.search("  ").first()).isEmpty()
    }

    @Test
    fun `tags are indexed, nested tags included under their parent`() = runBlocking<Unit> {
        val a = repo.create("A", "#work/project text")
        repo.create("B", "#work")
        repo.create("C", "#workshop")
        assertThat(repo.tagged("work").first().map { it.title }).containsExactly("A", "B")
        assertThat(repo.tags().first().map { it.tag }).containsExactly("work", "work/project", "workshop")
        repo.moveToTrash(listOf(a))
        assertThat(repo.tags().first().map { it.tag }).containsExactly("work", "workshop")
    }

    @Test
    fun `links resolve by name, preferring the same folder`() = runBlocking<Unit> {
        val top = repo.create("Plan", "", "")
        val inWork = repo.create("Plan", "", "Work")
        assertThat(repo.resolveLink("plan", "Work")!!.id).isEqualTo(inWork)
        assertThat(repo.resolveLink("Plan#Goals", "Home")!!.id).isEqualTo(top)
        assertThat(repo.resolveLink("Work/Plan", "")!!.id).isEqualTo(inWork)
        assertThat(repo.resolveLink("Nothing", "")).isNull()
    }

    @Test
    fun `links resolve titles with a slash, folder paths and md names`() = runBlocking<Unit> {
        val dated = repo.create("Meeting 10/23", "", "Work")
        val deep = repo.create("Plan", "", "Work/Sub")
        val top = repo.create("Top", "", "")
        assertThat(repo.resolveLink("Meeting 10/23", "")!!.id).isEqualTo(dated)
        assertThat(repo.resolveLink("Work/Meeting 10/23", "")!!.id).isEqualTo(dated)
        assertThat(repo.resolveLink("Sub/Plan", "")!!.id).isEqualTo(deep)
        assertThat(repo.resolveLink(" work / sub /plan.md#Goals", "")!!.id).isEqualTo(deep)
        assertThat(repo.resolveLink("Home/Plan", "")).isNull()
        assertThat(repo.resolveLink("ub/Plan", "")).isNull()
        assertThat(repo.resolveLink("/Top", "")!!.id).isEqualTo(top)
        assertThat(repo.resolveLink("/Plan", "")).isNull()
    }

    @Test
    fun `renaming a note updates links to it once its editor is done`() = runBlocking<Unit> {
        val target = repo.create("Old name", "")
        val linking = repo.create("Index", "See [[Old name]] and [[old name#h|here]].")
        repo.save(target, "New name", "")
        assertThat(repo.note(linking)!!.body).isEqualTo("See [[Old name]] and [[old name#h|here]].")
        assertThat(repo.applyRename(target, updateLinks = true)).isEqualTo(1)
        assertThat(repo.note(linking)!!.body).isEqualTo("See [[New name]] and [[New name#h|here]].")
        assertThat(repo.note(target)!!.linkedTitle).isEqualTo("New name")
        assertThat(repo.backlinks(target).map { it.id }).containsExactly(linking)
        assertThat(repo.applyRename(target, updateLinks = true)).isEqualTo(0)
    }

    @Test
    fun `a rename left pending is completed later, from the title links last pointed at`() = runBlocking<Unit> {
        val target = repo.create("A", "")
        val linking = repo.create("Index", "[[A]]")
        repo.save(target, "B", "")
        repo.save(target, "C", "") // renamed twice before anything updated the links
        assertThat(repo.applyPendingRenames(updateLinks = true)).isEqualTo(1)
        assertThat(repo.note(linking)!!.body).isEqualTo("[[C]]")
        assertThat(repo.applyPendingRenames(updateLinks = true)).isEqualTo(0)
    }

    @Test
    fun `with link updates off, a rename changes no note but is no longer pending`() = runBlocking<Unit> {
        val target = repo.create("A", "")
        val linking = repo.create("Index", "[[A]]")
        repo.save(target, "B", "")
        assertThat(repo.applyRename(target, updateLinks = false)).isEqualTo(0)
        assertThat(repo.note(linking)!!.body).isEqualTo("[[A]]")
        assertThat(repo.note(target)!!.linkedTitle).isEqualTo("B")
    }

    @Test
    fun `links are left alone while another note keeps the old name`() = runBlocking<Unit> {
        val target = repo.create("Same", "")
        repo.create("Same", "the other one")
        val linking = repo.create("Index", "[[Same]]")
        repo.save(target, "Different", "")
        assertThat(repo.applyRename(target, updateLinks = true)).isEqualTo(0)
        assertThat(repo.note(linking)!!.body).isEqualTo("[[Same]]")
    }

    @Test
    fun `renaming a note with a slash in its title keeps links working`() = runBlocking<Unit> {
        val target = repo.create("Weekly meeting", "")
        val linking = repo.create("Index", "[[Weekly meeting]]")
        repo.save(target, "Weekly meeting 10/23", "")
        repo.applyRename(target, updateLinks = true)
        assertThat(repo.note(linking)!!.body).isEqualTo("[[Weekly meeting 10/23]]")
        assertThat(repo.resolveLink("Weekly meeting 10/23", "")!!.id).isEqualTo(target)
        assertThat(repo.backlinks(target).map { it.id }).containsExactly(linking)
    }

    @Test
    fun `md links count as backlinks and follow renames`() = runBlocking<Unit> {
        val target = repo.create("Plan", "")
        val linking = repo.create("Index", "[[Plan.md]]")
        assertThat(repo.backlinks(target).map { it.id }).containsExactly(linking)
        repo.save(target, "Roadmap", "")
        repo.applyRename(target, updateLinks = true)
        assertThat(repo.note(linking)!!.body).isEqualTo("[[Roadmap.md]]")
    }

    @Test
    fun `a save expecting another body is refused, and nothing changes`() = runBlocking<Unit> {
        val id = repo.create("T", "typed in the editor")
        assertThat(repo.save(id, "T", "rewritten", expectedBody = "read earlier"))
            .isEqualTo(NoteRepository.SaveResult.CONFLICT)
        assertThat(repo.note(id)!!.body).isEqualTo("typed in the editor")
        assertThat(repo.save(id, "T", "rewritten", expectedBody = "typed in the editor"))
            .isEqualTo(NoteRepository.SaveResult.SAVED)
    }

    @Test
    fun `folders rename with their notes and subfolders`() = runBlocking<Unit> {
        val a = repo.create("A", "", "Work")
        val b = repo.create("B", "", "Work/Sub")
        val c = repo.create("C", "", "Workshop")
        repo.createFolder("Work/Empty")
        repo.renameFolder("Work", "Job")
        assertThat(repo.note(a)!!.folder).isEqualTo("Job")
        assertThat(repo.note(b)!!.folder).isEqualTo("Job/Sub")
        assertThat(repo.note(c)!!.folder).isEqualTo("Workshop")
        assertThat(repo.folders().first()).containsExactly("Job", "Job/Empty", "Job/Sub", "Workshop")
    }

    @Test
    fun `deleting a folder trashes its notes, which can be restored`() = runBlocking<Unit> {
        val a = repo.create("A", "", "Work/Sub")
        val b = repo.create("B", "", "Other")
        assertThat(repo.deleteFolder("Work")).isEqualTo(1)
        assertThat(repo.note(a)!!.trashedAt).isNotNull()
        assertThat(repo.note(b)!!.trashedAt).isNull()
        assertThat(repo.folders().first()).containsExactly("Other")
        repo.restore(listOf(a))
        assertThat(repo.folders().first()).containsExactly("Other", "Work", "Work/Sub")
    }

    @Test
    fun `folders differing only in case are different folders`() = runBlocking<Unit> {
        val upper = repo.create("A", "", "Work")
        val lower = repo.create("B", "", "work/Sub")
        assertThat(repo.countNotesIn("Work")).isEqualTo(1)
        assertThat(repo.deleteFolder("Work")).isEqualTo(1)
        assertThat(repo.note(upper)!!.trashedAt).isNotNull()
        assertThat(repo.note(lower)!!.trashedAt).isNull()
        repo.renameFolder("work", "Jobs")
        assertThat(repo.note(lower)!!.folder).isEqualTo("Jobs/Sub")
        assertThat(repo.folders().first()).containsExactly("Jobs", "Jobs/Sub")
    }

    @Test
    fun `renaming a folder whose name has characters beyond 16 bits keeps the path`() = runBlocking<Unit> {
        val folder = "\uD83D\uDCDD Notes" // an emoji: two UTF-16 units, one character to SQLite
        val sub = repo.create("A", "", "$folder/Sub")
        repo.renameFolder(folder, "Archive")
        assertThat(repo.note(sub)!!.folder).isEqualTo("Archive/Sub")
        assertThat(repo.folders().first()).containsExactly("Archive", "Archive/Sub")
    }

    @Test
    fun `bulk actions handle more notes than SQLite allows variables`() = runBlocking<Unit> {
        // Android 8 to 11 allow 999 variables per statement. (The JVM's SQLite allows more, so
        // this checks that every chunk is applied, not the limit itself.)
        val ids = (1..1200).map { repo.create("N$it", "") }
        repo.moveToTrash(ids)
        assertThat(repo.trash().first()).hasSize(1200)
        repo.restore(ids)
        repo.move(ids, "Box")
        assertThat(repo.notes(ids).map { it.folder }.toSet()).containsExactly("Box")
        repo.deleteForever(ids)
        assertThat(repo.notes(ids)).isEmpty()
    }

    @Test
    fun `the trash empties itself after thirty days`() = runBlocking<Unit> {
        val a = repo.create("A", "")
        repo.moveToTrash(listOf(a))
        now += NoteRepository.TRASH_RETENTION_MS - 1
        assertThat(repo.purgeOldTrash()).isEqualTo(0)
        now += 2
        assertThat(repo.purgeOldTrash()).isEqualTo(1)
        assertThat(repo.note(a)).isNull()
    }
}
