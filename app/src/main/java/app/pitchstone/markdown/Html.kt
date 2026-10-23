package app.pitchstone.markdown

import org.commonmark.ext.task.list.items.TaskListItemMarker
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

    private fun renderer(email: Boolean): HtmlRenderer {
        val builder = HtmlRenderer.builder()
            // Registered before the extensions, so these win over theirs for the same nodes.
            .nodeRendererFactory { context -> CustomRenderer(context, email) }
            .extensions(Markdown.extensions)
            .softbreak("<br />\n")
            .escapeHtml(true)
            .sanitizeUrls(true)
        if (email) {
            builder.attributeProviderFactory {
                AttributeProvider { _: Node, tagName: String, attributes: MutableMap<String, String> ->
                    EMAIL_STYLES[tagName]?.let { style ->
                        attributes["style"] = listOfNotNull(attributes["style"], style).joinToString(";")
                    }
                }
            }
        }
        return builder.build()
    }

    private val emailRenderer by lazy { renderer(email = true) }
    private val documentRenderer by lazy { renderer(email = false) }

    /** The note body as an HTML fragment with inline styles, for an email. */
    fun emailBody(markdown: String): String = emailRenderer.render(Markdown.parse(markdown).document)

    /** A standalone HTML page for a note, with its title as the heading. */
    fun document(title: String, markdown: String): String {
        val body = documentRenderer.render(Markdown.parse(markdown).document)
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

    private class CustomRenderer(private val context: HtmlNodeRendererContext, private val email: Boolean) : NodeRenderer {
        private val html = context.writer

        override fun getNodeTypes(): Set<Class<out Node>> =
            setOf(WikiLink::class.java, Highlight::class.java, TaskListItemMarker::class.java)

        override fun render(node: Node) {
            when (node) {
                is WikiLink -> {
                    val attrs = if (email) mapOf("style" to "color:#6750a4;font-weight:500") else mapOf("class" to "wikilink")
                    html.tag("span", attrs)
                    html.text(node.link.label)
                    html.tag("/span")
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
    }
}
