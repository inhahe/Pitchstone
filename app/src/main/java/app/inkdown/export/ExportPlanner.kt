package app.inkdown.export

import app.inkdown.data.ExportFormat
import app.inkdown.data.ExportLayout
import app.inkdown.markdown.Markdown
import app.inkdown.markdown.PlainText
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A note as export sees it. [folder] is its folder path ("" at the top). */
data class ExportNote(
    val title: String,
    val body: String,
    val folder: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tags: List<String> = emptyList(),
)

/**
 * What to export and how.
 *
 * @property titleHeading start each note's file with its title as a heading (files of single
 *   notes are named after the title anyway; a combined file always has the titles)
 * @property metadata add created/modified dates and tags as front matter (Markdown only)
 * @property textKeepsMarkdown for .txt, write the markdown source instead of plain text
 * @property singleFileName the name (without extension) of the file when everything goes in
 *   one file, or when exporting a single note
 */
data class ExportRequest(
    val format: ExportFormat,
    val layout: ExportLayout,
    val keepFolders: Boolean,
    val titleHeading: Boolean,
    val metadata: Boolean,
    val textKeepsMarkdown: Boolean,
    val singleFileName: String,
)

/** A file to write: [folder] is the path of subfolders below the destination. */
data class PlannedFile(
    val folder: List<String>,
    val baseName: String,
    val extension: String,
    val content: String,
)

object ExportPlanner {
    private const val TEXT_SEPARATOR = "----------------------------------------"

    fun plan(notes: List<ExportNote>, request: ExportRequest, zone: ZoneId = ZoneId.systemDefault()): List<PlannedFile> {
        val ext = request.format.extension
        if (notes.size == 1 && request.layout == ExportLayout.FILE_PER_NOTE) {
            val note = notes.single()
            val name = FileNames.safe(request.singleFileName.ifBlank { note.title })
            return listOf(PlannedFile(emptyList(), name, ext, noteContent(note, request, zone)))
        }
        return when (request.layout) {
            ExportLayout.SINGLE_FILE -> {
                val name = FileNames.safe(request.singleFileName, "Notes")
                listOf(PlannedFile(emptyList(), name, ext, combined(notes, request, zone)))
            }
            ExportLayout.FILE_PER_NOTE -> notes.map { note ->
                val folder = if (request.keepFolders) {
                    note.folder.split('/').filter { it.isNotBlank() }.map { FileNames.safe(it, "Folder") }
                } else {
                    emptyList()
                }
                PlannedFile(folder, FileNames.safe(note.title), ext, noteContent(note, request, zone))
            }
        }
    }

    /** One note's file content. */
    fun noteContent(note: ExportNote, request: ExportRequest, zone: ZoneId = ZoneId.systemDefault()): String =
        when (request.format) {
            ExportFormat.MARKDOWN -> markdownNote(note, request.titleHeading, request.metadata, zone)
            ExportFormat.TEXT -> {
                val text = if (request.textKeepsMarkdown) note.body.trimEnd() else PlainText.render(note.body)
                (if (request.titleHeading && note.title.isNotBlank()) textTitle(note.title) else "") + text + "\n"
            }
        }

    private fun textTitle(title: String) = "$title\n${"=".repeat(title.length.coerceIn(3, 60))}\n\n"

    private fun iso(millis: Long, zone: ZoneId): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun yamlString(s: String): String =
        if (Regex("^[\\p{L}\\p{N}_/\\-]+$").matches(s)) s else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The note's markdown, with front matter extended by metadata and a title heading if asked. */
    private fun markdownNote(note: ExportNote, titleHeading: Boolean, metadata: Boolean, zone: ZoneId): String {
        val split = Markdown.splitFrontMatter(note.body)
        val body = split.body.trimEnd('\n')
        val fmLines = split.frontMatter?.lines()?.toMutableList() ?: mutableListOf()
        val hasFrontMatter = split.frontMatter != null
        if (metadata) {
            fun has(key: String) = fmLines.any { it.trimStart().startsWith("$key:", ignoreCase = true) }
            if (!has("created")) fmLines += "created: ${iso(note.createdAt, zone)}"
            if (!has("modified")) fmLines += "modified: ${iso(note.updatedAt, zone)}"
            if (!has("tags") && !has("tag") && note.tags.isNotEmpty()) {
                fmLines += "tags: [${note.tags.joinToString(", ") { yamlString(it) }}]"
            }
        }
        val sb = StringBuilder()
        if (hasFrontMatter || fmLines.isNotEmpty()) {
            sb.append("---\n")
            if (fmLines.isNotEmpty()) sb.append(fmLines.joinToString("\n")).append('\n')
            sb.append("---\n")
        }
        if (titleHeading && note.title.isNotBlank()) sb.append("# ").append(note.title).append("\n\n")
        sb.append(body)
        if (body.isNotEmpty()) sb.append('\n')
        return sb.toString()
    }

    /** All notes in one file, each under its title. */
    private fun combined(notes: List<ExportNote>, request: ExportRequest, zone: ZoneId): String {
        val sb = StringBuilder()
        notes.forEachIndexed { i, note ->
            val title = note.title.ifBlank { "Untitled" }
            when (request.format) {
                ExportFormat.MARKDOWN -> {
                    if (i > 0) sb.append("\n---\n\n")
                    sb.append("# ").append(title).append("\n\n")
                    val split = Markdown.splitFrontMatter(note.body)
                    // Front matter can only open a file; here it becomes a YAML block.
                    if (split.frontMatter != null && split.frontMatter.isNotBlank()) {
                        sb.append("```yaml\n").append(split.frontMatter).append("\n```\n\n")
                    }
                    if (request.metadata) {
                        sb.append("*Created ").append(iso(note.createdAt, zone)).append(", modified ")
                            .append(iso(note.updatedAt, zone)).append("*\n\n")
                    }
                    val body = split.body.trim('\n')
                    if (body.isNotEmpty()) sb.append(body).append("\n")
                }
                ExportFormat.TEXT -> {
                    if (i > 0) sb.append("\n").append(TEXT_SEPARATOR).append("\n\n")
                    sb.append(textTitle(title))
                    val text = if (request.textKeepsMarkdown) note.body.trim('\n') else PlainText.render(note.body)
                    if (text.isNotEmpty()) sb.append(text).append("\n")
                }
            }
        }
        return sb.toString()
    }
}
