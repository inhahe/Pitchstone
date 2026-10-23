package app.pitchstone.markdown

import app.pitchstone.data.FolderPaths
import org.commonmark.node.CustomNode

/**
 * A [[wikilink]] found in markdown source.
 *
 * @property start offset of the link in the source, including a leading `!` for an embed
 * @property end offset just past the closing `]]`
 * @property target what the link points at, as written (may end in `#heading`)
 * @property alias the text shown instead of the target, if given after `|`
 */
data class WikiLinkMatch(
    val start: Int,
    val end: Int,
    val target: String,
    val alias: String?,
    val embed: Boolean,
) {
    /** The linked note's name: the target without any `#heading` or `#^block` part. */
    val noteName: String get() = target.substringBefore('#').trim()

    /** The text a reader sees for this link. */
    val label: String
        get() = alias?.takeIf { it.isNotEmpty() } ?: target.let { t ->
            val hash = t.indexOf('#')
            if (hash < 0) t else listOf(t.substring(0, hash), t.substring(hash + 1).removePrefix("^"))
                .filter { it.isNotBlank() }.joinToString(" › ")
        }
}

/** A wikilink in a parsed document (see [Markdown]). */
class WikiLink(val link: WikiLinkMatch) : CustomNode()

/** ==Highlighted== text. */
class Highlight : CustomNode()

object WikiLinks {
    /**
     * Finds the wikilinks in [src] the way the editor's parser (markdown-it, see
     * editor/src/markdown/md.ts) does: `[[` up to the first `]]`, with no brackets or line
     * breaks between; not escaped by a backslash; outside the [excluded] ranges (code and raw
     * HTML). The target is the text before the first `|`; a backslash right before that `|`
     * (the table-safe spelling `[[a\|b]]`) is dropped.
     */
    fun scan(src: String, excluded: List<IntRange> = emptyList()): List<WikiLinkMatch> {
        val sorted = excluded.sortedBy { it.first }
        val out = ArrayList<WikiLinkMatch>()
        var i = src.indexOf("[[")
        while (i >= 0) {
            val skipTo = sorted.firstOrNull { i in it }
            if (skipTo != null) {
                i = src.indexOf("[[", skipTo.last + 1)
                continue
            }
            if (isEscaped(src, i)) {
                i = src.indexOf("[[", i + 1)
                continue
            }
            val close = src.indexOf("]]", i + 2)
            if (close < 0) break
            val inner = src.substring(i + 2, close)
            if (inner.any { it == '[' || it == ']' || it == '\n' }) {
                i = src.indexOf("[[", i + 1)
                continue
            }
            val bar = inner.indexOf('|')
            var target = if (bar < 0) inner else inner.substring(0, bar)
            val alias = if (bar < 0) null else inner.substring(bar + 1)
            if (bar >= 0 && target.endsWith('\\')) target = target.dropLast(1)
            // Only code or HTML containing the `[[` excludes it: markdown-it tries the wikilink
            // rule at `[` before it ever sees a backtick that comes later.
            if (target.isBlank()) {
                i = src.indexOf("[[", i + 1)
                continue
            }
            val embed = i > 0 && src[i - 1] == '!' && !isEscaped(src, i - 1)
            out += WikiLinkMatch(if (embed) i - 1 else i, close + 2, target, alias, embed)
            i = src.indexOf("[[", close + 2)
        }
        return out
    }

    /** Whether the character at [index] is escaped by an odd number of backslashes. */
    private fun isEscaped(src: String, index: Int): Boolean {
        var n = 0
        var j = index - 1
        while (j >= 0 && src[j] == '\\') {
            n++
            j--
        }
        return n % 2 == 1
    }

    /**
     * A note name as wikilinks can spell it: `[[`, `]]`, `|`, `#` and `^` have meanings
     * inside a link, so they are dropped. Links and titles are compared in this form.
     */
    fun linkName(title: String): String =
        title.replace(Regex("[\\[\\]|#^\\n\\r]"), "").replace(Regex("\\s+"), " ").trim()

    /** The lower-cased key under which a note name is indexed and looked up. */
    fun key(name: String): String = linkName(name).lowercase()

    /**
     * A link target as typed in the link dialog: brackets, `|` and line breaks would end the
     * link, so they go; `#heading` and `#^block` parts stay.
     */
    fun cleanTarget(target: String): String = target.replace(Regex("[\\[\\]|\\r\\n]"), "").trim()

    /**
     * The title keys a link's note name ([WikiLinkMatch.noteName]) may refer to, most specific
     * first: the whole name, then the part after each `/`. Titles may contain `/` themselves,
     * so `Work/Meeting 10/23` may be note "Meeting 10/23" in folder Work, or note "23" in
     * Work/Meeting 10. A `.md` suffix, as in links from other apps, is ignored.
     */
    fun targetKeys(noteName: String): List<String> {
        val name = noteName.trim().let { if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it }
        val tails = name.indices.filter { name[it] == '/' }.map { name.substring(it + 1) }
        return (listOf(name) + tails).map(::key).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * The keys under which a note with [title] in [folder] can be linked: its title's key, then
     * `folder/title` for every tail of its folder path (`work/sub/plan`, `sub/plan`), or
     * `/title` for a note at the top level. A link's [pathKey] is looked up among these.
     */
    fun lookupKeys(title: String, folder: String): List<String> {
        val k = key(title)
        if (k.isEmpty()) return emptyList()
        val parts = FolderPaths.normalize(folder).split('/').filter { it.isNotEmpty() }
        val tails = if (parts.isEmpty()) listOf("") else parts.indices.map { parts.subList(it, parts.size).joinToString("/") }
        return listOf(k) + tails.map { it.lowercase() + "/" + k }
    }

    /**
     * The key of a `folder/note` link name (without `#heading` or `.md`), to look up among
     * [lookupKeys]; null if the name has no folder part.
     */
    fun pathKey(noteName: String): String? {
        if (!noteName.contains('/')) return null
        val base = key(noteName.substringAfterLast('/'))
        if (base.isEmpty()) return null
        return FolderPaths.normalize(noteName.substringBeforeLast('/')).lowercase() + "/" + base
    }
}
