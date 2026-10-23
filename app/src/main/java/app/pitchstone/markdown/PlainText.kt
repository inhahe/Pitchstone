package app.pitchstone.markdown

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * Renders a note as plain text: formatting symbols are gone, structure stays readable.
 * Lists keep their markers, task items show `[ ]`/`[x]`, quotes are prefixed with `> `,
 * tables are laid out in aligned columns, links show their address in parentheses, and
 * wikilinks show what they display. Front matter is left out.
 */
object PlainText {
    fun render(markdown: String): String = render(Markdown.parse(markdown).document)

    /**
     * The text of [document]. With [underlineHeadings], level 1 and 2 headings are underlined
     * with `=` and `-` (for files); without, headings are plain lines (for snippets and search).
     */
    fun render(document: Node, underlineHeadings: Boolean = true): String =
        Renderer(underlineHeadings).blockLines(document).joinToString("\n").trim('\n')

    /** The text of inline content, with line breaks as "\n". */
    fun inline(node: Node): String = Renderer(false).inline(node)
}

private class Renderer(private val underlineHeadings: Boolean) {

    fun inline(node: Node): String {
        val sb = StringBuilder()
        appendInline(node, sb)
        return sb.toString()
    }

    private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

    fun blockLines(node: Node): List<String> = when (node) {
        is Document, is FootnoteDefinition -> {
            val inner = joinBlocks(children(node).toList())
            if (node is FootnoteDefinition) prefix(inner, "[${node.label}] ", "    ") else inner
        }
        is Paragraph -> inline(node).split('\n')
        is Heading -> {
            val text = inline(node).replace('\n', ' ')
            when {
                !underlineHeadings -> listOf(text)
                node.level == 1 -> listOf(text, "=".repeat(text.length.coerceIn(3, 60)))
                node.level == 2 -> listOf(text, "-".repeat(text.length.coerceIn(3, 60)))
                else -> listOf(text)
            }
        }
        is BlockQuote -> joinBlocks(children(node).toList()).map { if (it.isEmpty()) ">" else "> $it" }
        is BulletList, is OrderedList -> listLines(node)
        is FencedCodeBlock -> node.literal.removeSuffix("\n").split('\n')
        is IndentedCodeBlock -> node.literal.removeSuffix("\n").split('\n')
        is HtmlBlock -> node.literal.removeSuffix("\n").split('\n')
        is ThematicBreak -> listOf("----------")
        is TableBlock -> tableLines(node)
        is LinkReferenceDefinition -> emptyList()
        else -> joinBlocks(children(node).toList())
    }

    private fun joinBlocks(blocks: List<Node>): List<String> {
        val out = ArrayList<String>()
        for (b in blocks) {
            val lines = blockLines(b)
            if (lines.isEmpty()) continue
            if (out.isNotEmpty()) out += ""
            out += lines
        }
        return out
    }

    private fun prefix(lines: List<String>, first: String, rest: String): List<String> =
        lines.mapIndexed { i, l -> if (i == 0) first + l else if (l.isEmpty()) l else rest + l }

    private fun listLines(list: Node): List<String> {
        val out = ArrayList<String>()
        var number = (list as? OrderedList)?.markerStartNumber ?: 1
        val tight = (list as? BulletList)?.isTight ?: (list as OrderedList).isTight
        for (item in children(list)) {
            if (item !is ListItem) continue
            var marker = if (list is OrderedList) "${number++}. " else "- "
            val blocks = children(item).toList()
            val task = blocks.firstOrNull() as? TaskListItemMarker
                ?: blocks.firstOrNull()?.firstChild as? TaskListItemMarker
            if (task != null) marker += if (task.isChecked) "[x] " else "[ ] "
            val content = blocks.filter { it !is TaskListItemMarker }
            // In a tight list an item's paragraph and nested list sit on consecutive lines.
            val inner = if (tight) content.flatMap { blockLines(it) } else joinBlocks(content)
            if (!tight && out.isNotEmpty()) out += ""
            out += prefix(inner.ifEmpty { listOf("") }, marker, " ".repeat(marker.length)).map { it.trimEnd() }
        }
        return out
    }

    private fun tableLines(table: TableBlock): List<String> {
        val rows = ArrayList<List<String>>()
        fun collect(node: Node) {
            for (child in children(node)) {
                if (child is TableRow) rows += children(child).filterIsInstance<TableCell>().map { inline(it).replace('\n', ' ') }.toList()
                else collect(child)
            }
        }
        collect(table)
        if (rows.isEmpty()) return emptyList()
        val columns = rows.maxOf { it.size }
        val widths = (0 until columns).map { c -> rows.maxOf { it.getOrElse(c) { "" }.length } }
        val lines = rows.map { row -> (0 until columns).joinToString(" | ") { c -> row.getOrElse(c) { "" }.padEnd(widths[c]) }.trimEnd() }
        val rule = widths.joinToString("-+-") { "-".repeat(it.coerceAtLeast(1)) }
        return listOf(lines.first(), rule) + lines.drop(1)
    }

    private fun appendInline(node: Node, sb: StringBuilder) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> sb.append(child.literal)
                is Code -> sb.append(child.literal)
                is SoftLineBreak, is HardLineBreak -> sb.append('\n')
                is HtmlInline -> sb.append(child.literal)
                is WikiLink -> sb.append(child.link.label)
                is FootnoteReference -> sb.append("[${child.label}]")
                is TaskListItemMarker -> {}
                is Image -> {
                    val alt = inline(child)
                    sb.append(if (alt.isNotBlank()) "[image: $alt]" else "[image]")
                }
                is Link -> {
                    val text = inline(child)
                    val dest = child.destination.removePrefix("mailto:")
                    sb.append(text)
                    if (dest.isNotEmpty() && dest != text && child.destination != text) sb.append(" (").append(dest).append(')')
                }
                is Emphasis, is StrongEmphasis, is Strikethrough, is Highlight -> appendInline(child, sb)
                else -> appendInline(child, sb)
            }
            child = child.next
        }
    }
}
