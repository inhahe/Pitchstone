package app.pitchstone.data

import app.pitchstone.markdown.WikiLinks

/**
 * Which note a [[link]] leads to. The editor marks links to missing notes by the same rules
 * (editor/src/editor/links.ts), so a link it shows as working is one this finds.
 */
object LinkResolver {
    /**
     * The note among [refs] that a link with note name [noteName] ([WikiLinks.noteName]) leads
     * to, written in a note in folder [fromFolder]: first a note whose whole title matches
     * (titles may contain `/`), then, for `folder/note`, one whose [WikiLinks.lookupKeys]
     * include the link's [WikiLinks.pathKey]. Among several, the one in [fromFolder] wins, then
     * one at the top level, then the most recently edited. Null if none matches.
     */
    fun resolve(noteName: String, fromFolder: String, refs: List<NoteRef>): NoteRef? {
        if (noteName.isEmpty()) return null
        val prefer = compareBy<NoteRef>({ if (it.folder == fromFolder) 0 else if (it.folder.isEmpty()) 1 else 2 }, { -it.updatedAt })
        val whole = WikiLinks.key(noteName)
        refs.filter { WikiLinks.key(it.title) == whole }.minWithOrNull(prefer)?.let { return it }
        val path = WikiLinks.pathKey(noteName) ?: return null
        return refs.filter { path in WikiLinks.lookupKeys(it.title, it.folder) }.minWithOrNull(prefer)
    }
}
