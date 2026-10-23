package app.inkdown.export

import app.inkdown.data.ConflictPolicy
import app.inkdown.data.ExportFormat
import app.inkdown.data.ExportLayout
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import org.junit.After
import org.junit.Test

class FileNamesTest {
    @Test
    fun `forbidden characters, dots and device names`() {
        assertThat(FileNames.safe("a/b\\c:d*e?f\"g<h>i|j")).isEqualTo("a b c d e f g h i j")
        assertThat(FileNames.safe("  ..hidden.  ")).isEqualTo("hidden")
        assertThat(FileNames.safe("CON")).isEqualTo("CON_")
        assertThat(FileNames.safe("   ")).isEqualTo("Untitled")
    }

    @Test
    fun `long names are cut at a character boundary within the byte limit`() {
        val name = FileNames.safe("é".repeat(300))
        assertThat(name.toByteArray(Charsets.UTF_8).size).isAtMost(200)
        assertThat(name.all { it == 'é' }).isTrue()
        val emoji = FileNames.safe("😀".repeat(100))
        assertThat(emoji.codePoints().allMatch { it == 0x1F600 }).isTrue()
    }

    @Test
    fun `unique names are case-insensitive`() {
        assertThat(FileNames.unique("Note", "md", setOf("note.md", "note (2).md"))).isEqualTo("Note (3).md")
        assertThat(FileNames.unique("Note", "md", emptySet())).isEqualTo("Note.md")
    }
}

class ExportPlannerTest {
    private val zone = ZoneOffset.UTC
    private val a = ExportNote("Alpha", "Body *a*\n", "", 0, 86_400_000, listOf("x"))
    private val b = ExportNote("Beta", "---\nk: v\n---\nBody b\n", "Work/Sub", 0, 0)
    private fun req(
        format: ExportFormat = ExportFormat.MARKDOWN,
        layout: ExportLayout = ExportLayout.FILE_PER_NOTE,
        keepFolders: Boolean = true,
        titleHeading: Boolean = false,
        metadata: Boolean = false,
        textKeepsMarkdown: Boolean = false,
        name: String = "",
    ) = ExportRequest(format, layout, keepFolders, titleHeading, metadata, textKeepsMarkdown, name)

    @Test
    fun `one file per note keeps folders and the markdown as is`() {
        val files = ExportPlanner.plan(listOf(a, b), req(), zone)
        assertThat(files.map { it.folder to "${it.baseName}.${it.extension}" }).containsExactly(
            emptyList<String>() to "Alpha.md",
            listOf("Work", "Sub") to "Beta.md",
        )
        assertThat(files[0].content).isEqualTo("Body *a*\n")
        assertThat(files[1].content).isEqualTo("---\nk: v\n---\nBody b\n")
    }

    @Test
    fun `metadata joins existing front matter, title heading follows it`() {
        val files = ExportPlanner.plan(listOf(b), req(metadata = true, titleHeading = true), zone)
        assertThat(files.single().content).isEqualTo(
            "---\nk: v\ncreated: 1970-01-01T00:00:00Z\nmodified: 1970-01-01T00:00:00Z\n---\n# Beta\n\nBody b\n",
        )
        val withTags = ExportPlanner.noteContent(a, req(metadata = true), zone)
        assertThat(withTags).startsWith("---\ncreated: 1970-01-01T00:00:00Z\nmodified: 1970-01-02T00:00:00Z\ntags: [x]\n---\n")
    }

    @Test
    fun `plain text strips the formatting unless asked to keep it`() {
        assertThat(ExportPlanner.noteContent(a, req(format = ExportFormat.TEXT), zone)).isEqualTo("Body a\n")
        assertThat(ExportPlanner.noteContent(a, req(format = ExportFormat.TEXT, textKeepsMarkdown = true), zone)).isEqualTo("Body *a*\n")
    }

    @Test
    fun `one file holds all notes under their titles`() {
        val files = ExportPlanner.plan(listOf(a, b), req(layout = ExportLayout.SINGLE_FILE, name = "All"), zone)
        val f = files.single()
        assertThat(f.baseName).isEqualTo("All")
        assertThat(f.content).isEqualTo("# Alpha\n\nBody *a*\n\n---\n\n# Beta\n\n```yaml\nk: v\n```\n\nBody b\n")
        val text = ExportPlanner.plan(listOf(a, b), req(format = ExportFormat.TEXT, layout = ExportLayout.SINGLE_FILE, name = "All"), zone).single()
        assertThat(text.content).startsWith("Alpha\n=====\n\nBody a\n\n----")
    }

    @Test
    fun `a single note takes the given file name`() {
        val f = ExportPlanner.plan(listOf(a), req(name = "Custom: name"), zone).single()
        assertThat(f.baseName).isEqualTo("Custom name")
    }
}

class ExportWriterTest {
    private val dir: File = Files.createTempDirectory("export").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String, folder: List<String> = emptyList()) = PlannedFile(folder, name, "md", content)

    @Test
    fun `same titles in one export never overwrite each other`() {
        val r = ExportWriter.write(FileDir(dir), listOf(file("A", "1"), file("A", "2"), file("a", "3")), ConflictPolicy.REPLACE)
        assertThat(r.written).containsExactly("A.md", "A (2).md", "a (3).md")
        assertThat(File(dir, "A.md").readText()).isEqualTo("1")
        assertThat(File(dir, "A (2).md").readText()).isEqualTo("2")
    }

    @Test
    fun `existing files follow the policy`() {
        File(dir, "A.md").writeText("an old and longer file")
        ExportWriter.write(FileDir(dir), listOf(file("A", "new")), ConflictPolicy.REPLACE)
        assertThat(File(dir, "A.md").readText()).isEqualTo("new")

        val kept = ExportWriter.write(FileDir(dir), listOf(file("A", "other")), ConflictPolicy.KEEP_BOTH)
        assertThat(kept.written).containsExactly("A (2).md")
        assertThat(File(dir, "A.md").readText()).isEqualTo("new")

        val skipped = ExportWriter.write(FileDir(dir), listOf(file("A", "x")), ConflictPolicy.SKIP)
        assertThat(skipped.skipped).containsExactly("A.md")
        assertThat(File(dir, "A.md").readText()).isEqualTo("new")
    }

    @Test
    fun `subfolders are created and reused`() {
        val r = ExportWriter.write(
            FileDir(dir),
            listOf(file("x", "1", listOf("Work", "Sub")), file("y", "2", listOf("work", "Sub"))),
            ConflictPolicy.KEEP_BOTH,
        )
        assertThat(r.ok).isTrue()
        assertThat(File(dir, "Work/Sub/x.md").readText()).isEqualTo("1")
        assertThat(File(dir, "Work/Sub/y.md").readText()).isEqualTo("2")
    }

    @Test
    fun `importing collects markdown and text, skipping hidden folders`() {
        File(dir, "a.md").writeText("A")
        File(dir, "b.txt").writeText("B")
        File(dir, "c.pdf").writeText("C")
        File(dir, ".obsidian").mkdirs()
        File(dir, ".obsidian/x.md").writeText("hidden")
        File(dir, "Sub").mkdirs()
        File(dir, "Sub/d.markdown").writeText("D")
        val files = Importer.collect(FileDir(dir))
        assertThat(files.map { it.folder to it.title }).containsExactly("" to "a", "" to "b", "Sub" to "d")
    }
}
