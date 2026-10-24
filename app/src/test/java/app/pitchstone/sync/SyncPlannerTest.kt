package app.pitchstone.sync

import app.pitchstone.sync.SyncAction.Adopt
import app.pitchstone.sync.SyncAction.Conflict
import app.pitchstone.sync.SyncAction.Forget
import app.pitchstone.sync.SyncAction.Import
import app.pitchstone.sync.SyncAction.MoveFile
import app.pitchstone.sync.SyncAction.Record
import app.pitchstone.sync.SyncAction.RestoreNote
import app.pitchstone.sync.SyncAction.TrashFile
import app.pitchstone.sync.SyncAction.TrashNote
import app.pitchstone.sync.SyncAction.UpdateNote
import app.pitchstone.sync.SyncAction.WriteFile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncPlannerTest {
    /** A folder: path -> (text, modified time). Sizes are the texts' lengths. */
    private class Folder(vararg files: Pair<String, Pair<String, Long>>) {
        val files = linkedMapOf(*files)
        val read = ArrayList<String>()
        val list get() = files.map { (path, f) -> VaultFile(path, f.second, f.first.length.toLong()) }
        fun hash(path: String): String {
            read += path
            return SyncHash.of(files.entries.first { it.key.equals(path, ignoreCase = true) }.value.first)
        }
    }

    private fun note(id: Long, title: String, body: String, folder: String = "", updatedAt: Long = 100, trashed: Boolean = false) =
        SyncNote(id, title, folder, SyncHash.of(body), updatedAt, trashed, blank = title.isBlank() && body.isBlank())

    private fun pair(path: String, id: Long, text: String, modified: Long) = SyncPair(path, id, SyncHash.of(text), modified, text.length.toLong())

    private fun plan(folder: Folder, notes: List<SyncNote>, pairs: List<SyncPair> = emptyList()) =
        SyncPlanner.plan(folder.list, notes, pairs, folder::hash)

    @Test
    fun `a first sync writes the app's notes, imports the folder's, and pairs those in both`() {
        val folder = Folder(
            "Only here.md" to ("from the folder" to 50L),
            "Work/Same.md" to ("same text" to 50L),
            "Different.md" to ("folder text" to 500L),
        )
        val notes = listOf(
            note(1, "New", "app text", folder = "Projects/2026"),
            note(2, "same", "same text", folder = "work"),
            note(3, "Different", "app text", updatedAt = 100),
        )
        assertThat(plan(folder, notes)).containsExactly(
            Import("Only here.md"),
            Record(2, "Work/Same.md"),
            Conflict(3, "Different.md", keepFile = true),
            WriteFile(1, "Projects/2026/New.md", create = true),
        )
    }

    @Test
    fun `of two differing texts the newer wins`() {
        val folder = Folder("A.md" to ("folder" to 50L))
        assertThat(plan(folder, listOf(note(1, "A", "app", updatedAt = 100)))).containsExactly(Conflict(1, "A.md", keepFile = false))
        val folder2 = Folder("A.md" to ("folder" to 50L))
        assertThat(plan(folder2, listOf(note(1, "A", "app", updatedAt = 100)), listOf(pair("A.md", 1, "old", 40))))
            .containsExactly(Conflict(1, "A.md", keepFile = false))
    }

    @Test
    fun `when nothing changed there is nothing to do, and no file is read`() {
        val folder = Folder("A.md" to ("text" to 50L), "B/C.md" to ("more" to 60L))
        val notes = listOf(note(1, "A", "text"), note(2, "C", "more", folder = "B"))
        val pairs = listOf(pair("A.md", 1, "text", 50), pair("B/C.md", 2, "more", 60))
        assertThat(plan(folder, notes, pairs)).isEmpty()
        assertThat(folder.read).isEmpty()
    }

    @Test
    fun `a file touched but not changed is only recorded again`() {
        val folder = Folder("A.md" to ("text" to 70L))
        assertThat(plan(folder, listOf(note(1, "A", "text")), listOf(pair("A.md", 1, "text", 50)))).containsExactly(Record(1, "A.md"))
        assertThat(folder.read).containsExactly("A.md")
    }

    @Test
    fun `a change on one side goes to the other`() {
        val pairs = listOf(pair("A.md", 1, "old", 50))
        assertThat(plan(Folder("A.md" to ("old" to 50L)), listOf(note(1, "A", "new")), pairs))
            .containsExactly(WriteFile(1, "A.md", create = false))
        assertThat(plan(Folder("A.md" to ("new!" to 70L)), listOf(note(1, "A", "old")), pairs))
            .containsExactly(UpdateNote(1, "A.md"))
    }

    @Test
    fun `the same change on both sides is only recorded`() {
        assertThat(plan(Folder("A.md" to ("new" to 70L)), listOf(note(1, "A", "new")), listOf(pair("A.md", 1, "old", 50))))
            .containsExactly(Record(1, "A.md"))
    }

    @Test
    fun `a note renamed or moved in the app moves its file`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        val folder = Folder("A.md" to ("text" to 50L))
        assertThat(plan(folder, listOf(note(1, "B", "text")), pairs)).containsExactly(MoveFile(1, "A.md", "B.md"))
        assertThat(plan(folder, listOf(note(1, "A", "text", folder = "Work")), pairs)).containsExactly(MoveFile(1, "A.md", "Work/A.md"))
        assertThat(plan(folder, listOf(note(1, "B", "changed")), pairs)).containsExactly(WriteFile(1, "B.md", create = false, movedFrom = "A.md"))
        assertThat(plan(Folder("A.md" to ("file changed" to 70L)), listOf(note(1, "B", "text")), pairs))
            .containsExactly(UpdateNote(1, "A.md", movedTo = "B.md"))
    }

    @Test
    fun `a rename onto a taken name numbers the file`() {
        val folder = Folder("A.md" to ("text" to 50L), "B.md" to ("other" to 50L))
        val notes = listOf(note(1, "B", "text"), note(2, "B", "other"))
        val pairs = listOf(pair("A.md", 1, "text", 50), pair("B.md", 2, "other", 50))
        assertThat(plan(folder, notes, pairs)).containsExactly(MoveFile(1, "A.md", "B (2).md"))
    }

    @Test
    fun `a file whose name already suits the note stays where it is`() {
        // Numbered by a clash, differing in case, or spelled with a character FileNames.safe replaces.
        val folder = Folder("Plan (2).md" to ("x" to 50L), "plan b.md" to ("y" to 50L), "Why?.md" to ("z" to 50L), "work/Q.md" to ("q" to 50L))
        val notes = listOf(note(1, "Plan", "x"), note(2, "Plan B", "y"), note(3, "Why?", "z"), note(4, "Q", "q", folder = "Work"))
        val pairs = listOf(pair("Plan (2).md", 1, "x", 50), pair("plan b.md", 2, "y", 50), pair("Why?.md", 3, "z", 50), pair("work/Q.md", 4, "q", 50))
        assertThat(plan(folder, notes, pairs)).isEmpty()
    }

    @Test
    fun `a file renamed or moved outside renames the note`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        val folder = Folder("Work/B.md" to ("text" to 60L))
        assertThat(plan(folder, listOf(note(1, "A", "text")), pairs)).containsExactly(Adopt(1, "A.md", "Work/B.md", writeNote = false))
        // Edited in the app meanwhile: the note keeps its text, which goes to the renamed file.
        assertThat(plan(Folder("Work/B.md" to ("text" to 60L)), listOf(note(1, "A", "edited")), pairs))
            .containsExactly(Adopt(1, "A.md", "Work/B.md", writeNote = true))
    }

    @Test
    fun `a file moved into a folder left out is followed, and that folder is listed only then`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        // Moved, the file keeps its time.
        val leftOut = Folder("Archive/B.md" to ("text" to 50L))
        var listed = 0
        fun outside(): List<VaultFile> {
            listed++
            return leftOut.list
        }
        val plan = SyncPlanner.plan(emptyList(), listOf(note(1, "A", "text")), pairs, leftOut::hash, ::outside)
        assertThat(plan).containsExactly(Adopt(1, "A.md", "Archive/B.md", writeNote = false))
        assertThat(listed).isEqualTo(1)
        // Edited in the app meanwhile: the app's text goes to the moved file.
        assertThat(SyncPlanner.plan(emptyList(), listOf(note(1, "A", "edited")), pairs, leftOut::hash, ::outside))
            .containsExactly(Adopt(1, "A.md", "Archive/B.md", writeNote = true))
        // Nothing gone: the folders left out are not looked into.
        val before = listed
        SyncPlanner.plan(Folder("A.md" to ("text" to 50L)).list, listOf(note(1, "A", "text")), pairs, leftOut::hash, ::outside)
        assertThat(listed).isEqualTo(before)
    }

    @Test
    fun `a file of the same text there from before is not taken for one moved into a folder left out`() {
        // A note made from a template, its file deleted: the template has its own time.
        val pairs = listOf(pair("A.md", 1, "text", 50))
        val leftOut = Folder("Templates/T.md" to ("text" to 10L))
        val plan = SyncPlanner.plan(emptyList(), listOf(note(1, "A", "edited")), pairs, leftOut::hash, { leftOut.list })
        assertThat(plan.filterIsInstance<Adopt>()).isEmpty()
    }

    @Test
    fun `a file moved into a folder left out and edited on the way is followed by its name, and not written over`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        val leftOut = Folder("Archive/A.md" to ("text, edited on the way" to 70L))
        val plan = SyncPlanner.plan(emptyList(), listOf(note(1, "A", "edited here too")), pairs, leftOut::hash, { leftOut.list })
        assertThat(plan).containsExactly(Adopt(1, "A.md", "Archive/A.md", writeNote = false))
        // A template of the name, there from before (older than the last sync): the note's file
        // was deleted, not moved there.
        val template = Folder("Templates/A.md" to ("a template" to 10L))
        assertThat(SyncPlanner.plan(emptyList(), listOf(note(1, "A", "text")), pairs, template::hash, { template.list }))
            .containsExactly(TrashNote(1, "A.md"))
        // Two of that name: neither is taken for it.
        val two = Folder("Archive/A.md" to ("one" to 70L), "Old/A.md" to ("two" to 70L))
        assertThat(SyncPlanner.plan(emptyList(), listOf(note(1, "A", "text")), pairs, two::hash, { two.list }))
            .containsExactly(TrashNote(1, "A.md"))
    }

    @Test
    fun `looking for a moved file reads only files of the pair's size`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        val folder = Folder("Long.md" to ("much longer text" to 60L), "B.md" to ("text" to 60L))
        assertThat(plan(folder, listOf(note(1, "A", "text")), pairs)).containsExactly(Adopt(1, "A.md", "B.md", writeNote = false), Import("Long.md"))
        assertThat(folder.read).containsExactly("B.md")
    }

    @Test
    fun `a file deleted outside sends its note to the trash, unless the note changed since`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        assertThat(plan(Folder(), listOf(note(1, "A", "text")), pairs)).containsExactly(TrashNote(1, "A.md"))
        // In this order: the new pair has the old one's path.
        assertThat(plan(Folder(), listOf(note(1, "A", "edited")), pairs)).containsExactly(Forget("A.md"), WriteFile(1, "A.md", create = true)).inOrder()
    }

    @Test
    fun `the path of a file deleted outside is not given to another note in the same sync`() {
        val folder = Folder("Y.md" to ("why" to 50L))
        val notes = listOf(note(1, "X", "ex"), note(2, "X", "why"))
        val pairs = listOf(pair("X.md", 1, "ex", 50), pair("Y.md", 2, "why", 50))
        assertThat(plan(folder, notes, pairs)).containsExactly(MoveFile(2, "Y.md", "X (2).md"), TrashNote(1, "X.md"))
    }

    @Test
    fun `a note trashed or deleted in the app sends its file to the folder's trash, unless the file changed since`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        assertThat(plan(Folder("A.md" to ("text" to 50L)), listOf(note(1, "A", "text", trashed = true)), pairs)).containsExactly(TrashFile("A.md"))
        assertThat(plan(Folder("A.md" to ("text" to 50L)), emptyList(), pairs)).containsExactly(TrashFile("A.md"))
        assertThat(plan(Folder("A.md" to ("changed" to 70L)), listOf(note(1, "A", "text", trashed = true)), pairs))
            .containsExactly(RestoreNote(1, "A.md"))
        assertThat(plan(Folder("A.md" to ("changed" to 70L)), emptyList(), pairs)).containsExactly(Import("A.md"))
    }

    @Test
    fun `a trashed note's file moved outside goes to the trash from where it is now`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        assertThat(plan(Folder("B.md" to ("text" to 60L)), listOf(note(1, "A", "text", trashed = true)), pairs)).containsExactly(TrashFile("B.md"))
    }

    @Test
    fun `a pair gone on both sides is forgotten`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        assertThat(plan(Folder(), emptyList(), pairs)).containsExactly(Forget("A.md"))
        assertThat(plan(Folder(), listOf(note(1, "A", "text", trashed = true)), pairs)).containsExactly(Forget("A.md"))
    }

    @Test
    fun `notes of one name get numbered files, and new files do not take a paired file's name`() {
        val folder = Folder("Plan.md" to ("paired" to 50L))
        val notes = listOf(note(1, "Plan", "paired"), note(2, "Plan", "second"), note(3, "plan", "third"))
        assertThat(plan(folder, notes, listOf(pair("Plan.md", 1, "paired", 50)))).containsExactly(
            WriteFile(2, "Plan (2).md", create = true),
            WriteFile(3, "plan (3).md", create = true),
        )
    }

    @Test
    fun `blank notes are not written, and titles become valid file names`() {
        val notes = listOf(note(1, "", ""), note(2, "a/b: c?", "text"), note(3, "", "untitled text"))
        assertThat(plan(Folder(), notes)).containsExactly(
            WriteFile(2, "a b c.md", create = true),
            WriteFile(3, "Untitled.md", create = true),
        )
    }

    @Test
    fun `a file that cannot be read is never taken for a rename`() {
        val pairs = listOf(pair("A.md", 1, "text", 50))
        val folder = Folder("B.md" to ("text" to 60L))
        val plan = SyncPlanner.plan(folder.list, listOf(note(1, "A", "text")), pairs, fileHash = { "unreadable:$it" })
        assertThat(plan).containsExactly(TrashNote(1, "A.md"), Import("B.md"))
    }
}
