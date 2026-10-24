package app.pitchstone.ui.editor

import app.pitchstone.markdown.WikiLinks

/**
 * A row of the link dialog.
 *
 * @property label what the row says
 * @property target the link target it writes (`Plan`, `Plan#Goals`, `#Goals`)
 * @property alias the display text to write unless the user typed one: the title, when the
 *   target has to spell it differently (a title may hold `#` or `|`)
 */
data class LinkChoice(val label: String, val target: String, val alias: String?, val kind: Kind) {
    enum class Kind { THIS_NOTE, NEW_NOTE, NOTE }
}

object LinkChoices {
    private val NOT_IN_ALIAS = Regex("[\\[\\]|\\r\\n]")

    /**
     * The rows for what was typed ([query]) among the notes' [titles]. A title may itself hold
     * `#` ("Issue #42"), so titles matching the whole query come first and link without a
     * heading. With a `#` typed, titles matching the part before it follow, linked to the
     * heading after it ("Plan#Goals"). A query naming no note offers a new one first; one
     * naming only a heading (`#Goals`) offers that heading in this note.
     */
    fun of(query: String, titles: List<String>, limit: Int = 50): List<LinkChoice> {
        val whole = query.trim()
        val note = query.substringBefore('#').trim()
        val section = if ('#' in query) "#" + query.substringAfter('#').trim() else ""
        val names = titles.filter { it.isNotBlank() }.distinct()

        fun matching(text: String): List<String> {
            val q = text.lowercase()
            return names.filter { q.isEmpty() || it.lowercase().contains(q) }
                .sortedWith(compareBy<String>({ !it.lowercase().startsWith(q) }, { it.length }, { it.lowercase() }))
        }

        fun row(title: String, heading: String): LinkChoice {
            val name = WikiLinks.linkName(title)
            val label = if (heading.length > 1) "$title › ${heading.drop(1)}" else title
            return LinkChoice(label, name + heading, title.takeIf { it != name && !NOT_IN_ALIAS.containsMatchIn(it) }, LinkChoice.Kind.NOTE)
        }

        val out = ArrayList<LinkChoice>()
        if (note.isEmpty() && section.length > 1) {
            out += LinkChoice("Link to “${section.drop(1)}” in this note", section, null, LinkChoice.Kind.THIS_NOTE)
        }
        val wholeMatches = matching(whole).take(limit)
        val sectionMatches = if (section.isNotEmpty() && note.isNotEmpty()) matching(note).filter { it !in wholeMatches } else emptyList()
        val exact = whole.isEmpty() || names.any { WikiLinks.key(it) == WikiLinks.key(whole) } ||
            (section.isNotEmpty() && (note.isEmpty() || names.any { WikiLinks.key(it) == WikiLinks.key(note) }))
        if (!exact && note.isNotEmpty()) {
            val name = WikiLinks.linkName(note)
            out += LinkChoice("Link to new note “$name”", name + section, null, LinkChoice.Kind.NEW_NOTE)
        }
        wholeMatches.forEach { out += row(it, "") }
        sectionMatches.take((limit - wholeMatches.size).coerceAtLeast(0)).forEach { out += row(it, section) }
        return out
    }
}
