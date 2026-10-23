// How a wikilink finds its note. These are the rules of the app's NoteRepository.resolveLink and
// WikiLinks (key, lookupKeys, pathKey), so a link the editor shows as leading somewhere is one
// the app can open, and a suggestion it inserts is one the app resolves.

import type {Node} from 'prosemirror-model'

/** Java's `\s`, which the app uses: Unicode spaces other than these stay as they are. */
const JAVA_SPACE = /[ \t\n\x0B\f\r]+/g

/** A note title as written in a link: no `[ ] | # ^` or line breaks, runs of spaces as one. */
export function linkName(title: string): string {
  return title.replace(/[[\]|#^\n\r]/g, '').replace(JAVA_SPACE, ' ').trim()
}

/** The key under which the app indexes and looks up a note name. */
export function linkKey(name: string): string {
  return linkName(name).toLowerCase()
}

/** The note a wikilink target points at: the part before any `#heading` or `#^block`. */
export function wikilinkNote(target: string): string {
  const hash = target.indexOf('#')
  return (hash >= 0 ? target.slice(0, hash) : target).trim()
}

/**
 * A heading's text as `[[Note#Heading]]` links spell it. Obsidian writes characters a link
 * cannot hold (`# | ^ : % [ ]`) as spaces, so they are ignored on both sides.
 */
function headingKey(text: string): string {
  return text.replace(/[#|^:%[\]]/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase()
}

/**
 * Where the `#anchor` of a link points in `doc`: the position before the heading it names
 * (`Heading`, or `Parent#Heading`, matched by the last part) or the block whose text ends in
 * `^id` (`^id`). Null if there is no such heading or block.
 */
export function anchorPosition(doc: Node, anchor: string): number | null {
  const a = anchor.trim()
  let match: (node: Node) => boolean
  if (a.startsWith('^')) {
    const id = a.slice(1).toLowerCase()
    if (!id) return null
    match = (node) => /(?:^|\s)\^([\w-]+)\s*$/.exec(node.textContent)?.[1].toLowerCase() === id
  } else {
    const parts = a.split('#').map(headingKey).filter(Boolean)
    const want = parts[parts.length - 1]
    if (!want) return null
    match = (node) => node.type.name === 'heading' && headingKey(node.textContent) === want
  }
  let found: number | null = null
  doc.descendants((node, pos) => {
    if (found !== null) return false
    if (!node.isTextblock) return true
    if (match(node)) found = pos
    return false
  })
  return found
}

/**
 * Whether a link to `target` leads to a note. `keys` are the app's lookup keys of every note
 * (WikiLinks.lookupKeys): its title's key, and `folder/title` keys for each tail of its folder
 * path. A link is matched by its whole name first (titles may contain `/`), then as
 * `folder/note`. A target without a note part (`#Heading`) points into the note itself.
 */
export function noteExists(keys: ReadonlySet<string>, target: string): boolean {
  let name = wikilinkNote(target)
  if (!name) return true
  if (/\.md$/i.test(name)) name = name.slice(0, -3)
  if (keys.has(linkKey(name))) return true
  const slash = name.lastIndexOf('/')
  if (slash < 0) return false
  const base = linkKey(name.slice(slash + 1))
  if (!base) return false
  const folder = name
    .slice(0, slash)
    .split('/')
    .map((part) => part.trim())
    .filter(Boolean)
    .join('/')
  return keys.has(folder.toLowerCase() + '/' + base)
}
