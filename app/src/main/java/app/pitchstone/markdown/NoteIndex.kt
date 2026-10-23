package app.pitchstone.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Text

/**
 * What is derived from a note on every save: its tags, the notes it links to, and its text for
 * search and for the note list.
 *
 * @property tags tag key (lower-cased, without `#`) to the tag as first written
 * @property links keys ([WikiLinks.key]) of the notes this note links to
 * @property searchText lower-cased title and plain text, for substring search
 * @property snippet the start of the plain text, whitespace collapsed
 */
data class NoteIndex(
    val tags: Map<String, String>,
    val links: Set<String>,
    val searchText: String,
    val snippet: String,
)

object NoteIndexer {
    /** The same pattern the editor highlights (editor/src/editor/decorations.ts). */
    private val TAG = Regex("(^|[\\s(\\[{])#([\\p{L}\\p{N}_\\-/]*[\\p{L}_\\-/][\\p{L}\\p{N}_\\-/]*)")
    private const val SNIPPET_LENGTH = 240

    fun index(title: String, body: String): NoteIndex {
        val parsed = Markdown.parse(body)
        val tags = LinkedHashMap<String, String>()
        fun addTag(raw: String) {
            val tag = raw.trim().removePrefix("#").trimEnd('/')
            if (tag.isNotEmpty() && tag.any { !it.isDigit() }) tags.putIfAbsent(tag.lowercase(), tag)
        }
        parsed.frontMatter?.let { fm -> frontMatterTags(fm).forEach(::addTag) }
        parsed.document.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                for (m in TAG.findAll(text.literal)) addTag(m.groupValues[2])
            }
            // Code and raw HTML never hold tags.
            override fun visit(code: Code) {}
            override fun visit(block: FencedCodeBlock) {}
            override fun visit(block: IndentedCodeBlock) {}
            override fun visit(block: HtmlBlock) {}
            override fun visit(html: HtmlInline) {}
        })
        // Under every name the link may mean: the whole name and, for folder/note, the note.
        val links = parsed.wikiLinks.flatMap { WikiLinks.targetKeys(it.noteName) }.toSet()
        val plain = PlainText.render(parsed.document, underlineHeadings = false)
        // A first heading that repeats the title (common in imported notes) is not news in the list.
        val first = parsed.document.firstChild
        val skip = if (first is Heading && PlainText.inline(first).trim().equals(title.trim(), ignoreCase = true)) {
            PlainText.inline(first).length
        } else {
            0
        }
        val collapsed = plain.trimStart().drop(skip).replace(Regex("\\s+"), " ").trim()
        return NoteIndex(
            tags = tags,
            links = links,
            searchText = (title + "\n" + plain).replace(Regex("[ \\t]+"), " ").lowercase(),
            snippet = collapsed.take(SNIPPET_LENGTH),
        )
    }

    /**
     * Tags listed in front matter, as Obsidian reads them: `tags: [a, b]`, `tags: a, b`, or a
     * `tags:` key followed by `- a` lines. `tag:` is accepted too.
     */
    fun frontMatterTags(frontMatter: String): List<String> {
        val lines = frontMatter.lines()
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val m = Regex("^(tags?)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE).find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) {
                out += splitList(rest.removePrefix("[").removeSuffix("]"))
                i++
            } else {
                i++
                while (i < lines.size && Regex("^\\s*-\\s+").containsMatchIn(lines[i])) {
                    out += lines[i].replaceFirst(Regex("^\\s*-\\s+"), "").trim().trim('"', '\'')
                    i++
                }
            }
        }
        return out
    }

    /** Items separated by commas or spaces; a quoted item may contain either. */
    private fun splitList(s: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> quote = c
                c == ',' || c.isWhitespace() -> {
                    if (current.isNotBlank()) out += current.toString().trim()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }
}

object LinkRewriter {
    /**
     * Points the wikilinks in [markdown] that lead to the note named [oldName] at [newName]
     * instead, keeping any `#heading` part, alias, embed marker and `.md` suffix. A link whose
     * whole name matches is renamed whole (titles may contain `/`); otherwise a `folder/name`
     * link whose part after a `/` matches keeps its folder. Returns null if no link changed; the
     * result has `\n` line endings.
     */
    fun rename(markdown: String, oldName: String, newName: String): String? {
        val oldKey = WikiLinks.key(oldName)
        val replacement = WikiLinks.linkName(newName)
        if (oldKey.isEmpty() || replacement.isEmpty()) return null
        val source = Markdown.normalizeNewlines(markdown)
        val body = Markdown.splitFrontMatter(source).body
        val head = source.length - body.length
        val out = StringBuilder()
        var last = 0
        var changed = false
        for (link in Markdown.wikiLinks(body)) {
            // The target follows `[[` (and `!` for an embed). Only the name is replaced: what
            // surrounds it (spacing, folder, `.md`, `#heading`, the backslash of a table-safe
            // `\|`) stays as written.
            val targetStart = link.start + (if (link.embed) 1 else 0) + 2
            val inner = body.substring(targetStart, link.end - 2)
            val bar = inner.indexOf('|')
            var rawTarget = if (bar < 0) inner else inner.substring(0, bar)
            if (bar >= 0 && rawTarget.endsWith('\\')) rawTarget = rawTarget.dropLast(1)
            val hash = rawTarget.indexOf('#')
            val notePart = if (hash >= 0) rawTarget.substring(0, hash) else rawTarget
            val lead = notePart.length - notePart.trimStart().length
            val trimmed = notePart.trim()
            val name = if (trimmed.endsWith(".md", ignoreCase = true)) trimmed.dropLast(3) else trimmed
            val nameFrom = targetStart + lead
            // The whole name, or the part after a `/` (see WikiLinks.targetKeys).
            val start = if (WikiLinks.key(name) == oldKey) 0 else {
                val slash = name.indices.firstOrNull { name[it] == '/' && WikiLinks.key(name.substring(it + 1)) == oldKey }
                    ?: continue
                val tail = name.substring(slash + 1)
                slash + 1 + (tail.length - tail.trimStart().length)
            }
            val from = nameFrom + start
            val to = nameFrom + name.length
            out.append(body, last, from).append(replacement)
            last = to
            changed = true
        }
        if (!changed) return null
        out.append(body, last, body.length)
        return source.substring(0, head) + out
    }
}
