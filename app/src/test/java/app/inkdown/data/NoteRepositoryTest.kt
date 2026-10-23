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
    fun `renaming a note updates links to it`() = runBlocking<Unit> {
        val target = repo.create("Old name", "")
        val linking = repo.create("Index", "See [[Old name]] and [[old name#h|here]].")
        repo.save(target, "New name", "")
        assertThat(repo.propagateRename(target, "Old name", "New name")).isEqualTo(1)
        assertThat(repo.note(linking)!!.body).isEqualTo("See [[New name]] and [[New name#h|here]].")
        assertThat(repo.backlinks(target).map { it.id }).containsExactly(linking)
    }

    @Test
    fun `links are left alone while another note keeps the old name`() = runBlocking<Unit> {
        val target = repo.create("Same", "")
        repo.create("Same", "the other one")
        val linking = repo.create("Index", "[[Same]]")
        repo.save(target, "Different", "")
        assertThat(repo.propagateRename(target, "Same", "Different")).isEqualTo(0)
        assertThat(repo.note(linking)!!.body).isEqualTo("[[Same]]")
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
