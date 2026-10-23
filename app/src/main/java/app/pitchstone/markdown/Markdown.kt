package app.pitchstone.markdown

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.Nodes
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

/**
 * Reads notes with commonmark-java, in the dialect of the editor (editor/src/markdown/md.ts):
 * CommonMark with GFM tables, strikethrough (`~~` only) and task lists, `==highlights==`,
 * `[[wikilinks]]`, footnotes and front matter. A single newline is a line break.
 *
 * The editor is what users see, so where commonmark-java and markdown-it could disagree
 * (wikilinks, front matter) this follows markdown-it's rules. Used for export, sharing and
 * indexing; the editor itself never depends on it.
 */
object Markdown {
    /** Front matter at the very start of a note, as the editor recognises it. */
    private val FRONT_MATTER = Regex("^---[ \\t]*\\n(?:([\\s\\S]*?)\\n)??---[ \\t]*(?:\\n|$)")

    private const val OPEN = ''
    private const val CLOSE = ''

    val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.builder().requireTwoTildes(true).build(),
        TaskListItemsExtension.create(),
        FootnotesExtension.create(),
        AutolinkExtension.create(),
    )

    private val spanParser: Parser = Parser.builder()
        .extensions(extensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    /** A note split into its front matter (null if none) and the markdown after it. */
    data class Split(val frontMatter: String?, val body: String)

    fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    fun splitFrontMatter(markdown: String): Split {
        val src = normalizeNewlines(markdown)
        val m = FRONT_MATTER.find(src) ?: return Split(null, src)
        return Split(m.groupValues[1], src.substring(m.range.last + 1))
    }

    class Parsed(
        val document: Node,
        val frontMatter: String?,
        /** The markdown after the front matter, which offsets in [wikiLinks] refer to. */
        val body: String,
        val wikiLinks: List<WikiLinkMatch>,
    )

    fun parse(markdown: String): Parsed {
        val split = splitFrontMatter(markdown)
        val body = split.body
        val links = WikiLinks.scan(body, literalRanges(body))
        val withPlaceholders = StringBuilder(body.length)
        var last = 0
        links.forEachIndexed { i, link ->
            withPlaceholders.append(body, last, link.start).append(OPEN).append(i).append(CLOSE)
            last = link.end
        }
        withPlaceholders.append(body, last, body.length)
        val parser = Parser.builder()
            .extensions(extensions)
            .customInlineContentParserFactory(WikiLinkParserFactory(links))
            .customDelimiterProcessor(HighlightDelimiterProcessor())
            .build()
        return Parsed(parser.parse(withPlaceholders.toString()), split.frontMatter, body, links)
    }

    /** Wikilinks in a note (after its front matter): offsets are into [Split.body]. */
    fun wikiLinks(body: String): List<WikiLinkMatch> = WikiLinks.scan(body, literalRanges(body))

    /**
     * Source ranges where `[[` is literal: code, raw HTML, autolinks and link definitions.
     * Offsets are into [body].
     */
    fun literalRanges(body: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        val doc = spanParser.parse(body)
        doc.accept(object : AbstractVisitor() {
            override fun visit(code: Code) = add(code)
            override fun visit(block: FencedCodeBlock) = add(block)
            override fun visit(block: IndentedCodeBlock) = add(block)
            override fun visit(block: HtmlBlock) = add(block)
            override fun visit(html: HtmlInline) = add(html)
            override fun visit(def: LinkReferenceDefinition) = add(def)
            override fun visit(link: Link) {
                val span = link.sourceSpans.firstOrNull()
                if (span != null && body.startsWith("<", span.inputIndex)) add(link) else visitChildren(link)
            }

            private fun add(node: Node) {
                for (span in node.sourceSpans) ranges += span.inputIndex until span.inputIndex + span.length
            }
        })
        return ranges
    }

    /** Turns the placeholders [parse] put in place of wikilinks into [WikiLink] nodes. */
    private class WikiLinkParserFactory(private val links: List<WikiLinkMatch>) : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf(OPEN)

        override fun create(): InlineContentParser = InlineContentParser { state: InlineParserState ->
            val scanner = state.scanner()
            scanner.next()
            var index = 0
            var digits = 0
            while (scanner.peek() in '0'..'9' && digits < 9) {
                index = index * 10 + (scanner.peek() - '0')
                scanner.next()
                digits++
            }
            val link = links.getOrNull(index)
            if (digits == 0 || scanner.peek() != CLOSE || link == null) return@InlineContentParser ParsedInline.none()
            scanner.next()
            ParsedInline.of(WikiLink(link), scanner.position())
        }
    }

    /** `==text==`: like strikethrough, pairs of `=` (single ones stay literal). */
    private class HighlightDelimiterProcessor : DelimiterProcessor {
        override fun getOpeningCharacter() = '='
        override fun getClosingCharacter() = '='
        override fun getMinLength() = 2

        override fun process(openingRun: DelimiterRun, closingRun: DelimiterRun): Int {
            if (openingRun.length() < 2 || closingRun.length() < 2) return 0
            val opener = openingRun.opener
            val highlight = Highlight()
            for (node in Nodes.between(opener, closingRun.closer)) highlight.appendChild(node)
            opener.insertAfter(highlight)
            return 2
        }
    }
}
