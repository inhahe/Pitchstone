package app.pitchstone.markdown

import org.commonmark.ext.task.list.items.TaskListItemMarker
import app.pitchstone.data.AttachmentStore
import org.commonmark.node.Image
import org.commonmark.node.ListItem
import org.commonmark.node.Text
import java.io.File
import java.util.Base64
import org.commonmark.node.Node
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders notes as HTML. Raw HTML in a note is shown as text (the editor shows it as source,
 * and it must not run in an email), unsafe link targets are dropped, and a single newline is a
 * line break, as in the editor.
 */
object Html {
    /** Inline styles for email, where `<style>` elements are often stripped. */
    private val EMAIL_STYLES = mapOf(
        "table" to "border-collapse:collapse;margin:8px 0",
        "th" to "border:1px solid #c9c9d1;padding:4px 8px;background:#f3f3f6;text-align:left",
        "td" to "border:1px solid #c9c9d1;padding:4px 8px",
        "code" to "font-family:Consolas,Menlo,monospace;background:#f1f1f4;border-radius:3px;padding:0 3px",
        "pre" to "font-family:Consolas,Menlo,monospace;background:#f6f6f8;border:1px solid #e3e3e8;border-radius:6px;padding:8px 10px;white-space:pre-wrap",
        "blockquote" to "margin:8px 0;padding:2px 0 2px 12px;border-left:3px solid #8b7fc7;color:#555",
        "mark" to "background:#fff3a8",
        "img" to "max-width:100%",
        "hr" to "border:none;border-top:1px solid #ccc",
    )

    private fun renderer(email: Boolean, attachments: (String) -> File? = { null }): HtmlRenderer {
        val builder = HtmlRenderer.builder()
            // Registered before the extensions, so these win over theirs for the same nodes.
            .nodeRendererFactory { context -> CustomRenderer(context, email, attachments) }
            .extensions(Markdown.extensions)
            .softbreak("<br />\n")
            .escapeHtml(true)
            .sanitizeUrls(true)
        builder.attributeProviderFactory {
            AttributeProvider { node: Node, tagName: String, attributes: MutableMap<String, String> ->
                fun style(css: String) {
                    attributes["style"] = listOfNotNull(attributes["style"], css).joinToString(";")
                }
                // A task item shows its ☐ or ☑ in place of a bullet.
                if (tagName == "li" && isTask(node)) {
                    if (email) style("list-style:none") else attributes["class"] = "task"
                }
                if (email) EMAIL_STYLES[tagName]?.let(::style)
            }
        }
        return builder.build()
    }

    /** Whether [node] is a task list item (one holding a TaskListItemMarker). */
    private fun isTask(node: Node): Boolean =
        node is ListItem && (node.firstChild is TaskListItemMarker || node.firstChild?.firstChild is TaskListItemMarker)

    private val emailRenderer by lazy { renderer(email = true) }

    /**
     * The note body as an HTML fragment with inline styles, for an email. Pictures stored with
     * the note show as their names: mail apps drop pictures written into the HTML itself.
     */
    fun emailBody(markdown: String): String = emailRenderer.render(Markdown.parse(markdown).document)

    /**
     * A standalone HTML page for a note, with its title as the heading. The pictures it shows,
     * found by [attachments] (AttachmentStore.file), are written into the page (data: URLs), so
     * the one file holds everything.
     */
    fun document(title: String, markdown: String, attachments: (String) -> File? = { null }): String {
        val body = renderer(email = false, attachments).render(Markdown.parse(markdown).document)
        val heading = if (title.isNotBlank()) "<h1>${escape(title)}</h1>\n" else ""
        return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title.ifBlank { "Note" })}</title>
<style>
body{font-family:system-ui,-apple-system,Roboto,'Segoe UI',sans-serif;line-height:1.6;max-width:46em;margin:2em auto;padding:0 1em;color:#1d1b20}
h1,h2,h3,h4,h5,h6{line-height:1.3}
a,.wikilink{color:#6750a4}
code{font-family:Consolas,Menlo,monospace;background:#f1f1f4;border-radius:3px;padding:0 3px}
pre{background:#f6f6f8;border:1px solid #e3e3e8;border-radius:6px;padding:8px 10px;white-space:pre-wrap}
pre code{background:none;padding:0}
blockquote{margin:8px 0;padding:2px 0 2px 12px;border-left:3px solid #8b7fc7;color:#555}
table{border-collapse:collapse}th,td{border:1px solid #c9c9d1;padding:4px 8px}th{background:#f3f3f6;text-align:left}
mark{background:#fff3a8}
img{max-width:100%}
li.task{list-style:none}
</style>
</head>
<body>
$heading$body</body>
</html>
"""
    }

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    /** Pictures larger than this are not written into a page; their name stands in. */
    private const val INLINE_MAX_BYTES = 10_000_000L

    private class CustomRenderer(
        private val context: HtmlNodeRendererContext,
        private val email: Boolean,
        private val attachments: (String) -> File?,
    ) : NodeRenderer {
        private val html = context.writer

        override fun getNodeTypes(): Set<Class<out Node>> =
            setOf(WikiLink::class.java, Highlight::class.java, TaskListItemMarker::class.java, Image::class.java)

        override fun render(node: Node) {
            when (node) {
                is WikiLink -> {
                    if (node.link.embed && AttachmentStore.isImage(node.link.noteName)) {
                        picture(node.link.noteName, AttachmentStore.baseName(node.link.noteName))
                        return
                    }
                    val attrs = if (email) mapOf("style" to "color:#6750a4;font-weight:500") else mapOf("class" to "wikilink")
                    html.tag("span", attrs)
                    html.text(node.link.label)
                    html.tag("/span")
                }
                is Image -> {
                    val dest = node.destination
                    val alt = altText(node)
                    when {
                        Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(dest) -> {
                            val attrs = linkedMapOf("src" to dest, "alt" to alt)
                            node.title?.let { attrs["title"] = it }
                            html.tag("img", context.extendAttributes(node, "img", attrs), true)
                        }
                        // A path: a picture stored with the notes, by its file name.
                        !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(dest) && dest.isNotBlank() -> {
                            val path = runCatching { java.net.URLDecoder.decode(dest.replace("+", "%2B"), "UTF-8") }.getOrDefault(dest)
                            picture(path, alt.ifBlank { AttachmentStore.baseName(path) })
                        }
                        else -> html.text(alt)
                    }
                }
                is Highlight -> {
                    html.tag("mark", context.extendAttributes(node, "mark", emptyMap()))
                    var child = node.firstChild
                    while (child != null) {
                        val next = child.next
                        context.render(child)
                        child = next
                    }
                    html.tag("/mark")
                }
                is TaskListItemMarker -> {
                    // A character, not a checkbox input: mail apps drop form controls.
                    html.text(if (node.isChecked) "☑ " else "☐ ")
                }
            }
        }

        /** A stored picture: in a page, the picture itself; in an email, or if missing, its name. */
        private fun picture(reference: String, alt: String) {
            val file = attachments(reference)
            if (!email && file != null && file.length() <= INLINE_MAX_BYTES) {
                val mime = MimeTypes.forExtension(file.extension)
                val data = Base64.getEncoder().encodeToString(file.readBytes())
                html.tag("img", mapOf("src" to "data:$mime;base64,$data", "alt" to alt), true)
            } else {
                html.tag("span", mapOf("style" to "color:#666"))
                html.text("[$alt]")
                html.tag("/span")
            }
        }

        private fun altText(node: Node): String = buildString {
            fun walk(n: Node?) {
                var c = n?.firstChild
                while (c != null) {
                    if (c is Text) append(c.literal) else walk(c)
                    c = c.next
                }
            }
            walk(node)
        }
    }

    /** Media types of the picture formats notes show. */
    private object MimeTypes {
        fun forExtension(ext: String): String = when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }
}
