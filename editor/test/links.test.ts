import {describe, expect, it} from 'vitest'
import {linkKey, linkName, noteExists} from '../src/editor/links'
import {parseMarkdown} from '../src/markdown/parse'

// The keys the app sends for notes "Plan" in Work/Sub, "Meeting 10/23" in Work and "Top" at
// the top level (WikiLinks.lookupKeys; the Kotlin tests check the app's side of these).
const keys = new Set([
  'plan', 'work/sub/plan', 'sub/plan',
  'meeting 10/23', 'work/meeting 10/23',
  'top', '/top',
])

describe('wikilink targets', () => {
  it('link names drop what a link cannot hold, as the app does', () => {
    expect(linkName('My #1 [draft] | v2^')).toBe('My 1 draft v2')
    expect(linkKey('  Mixed\tCase  ')).toBe('mixed case')
    // Java's \s does not include the no-break space, so neither does the key.
    expect(linkKey('a b')).toBe('a b')
  })

  it('match by whole name, then as folder/note', () => {
    expect(noteExists(keys, 'Plan')).toBe(true)
    expect(noteExists(keys, 'plan.md#Goals')).toBe(true)
    expect(noteExists(keys, 'Sub/Plan')).toBe(true)
    expect(noteExists(keys, ' Work / Sub /Plan')).toBe(true)
    expect(noteExists(keys, 'Home/Plan')).toBe(false)
    expect(noteExists(keys, 'Meeting 10/23')).toBe(true)
    expect(noteExists(keys, 'Work/Meeting 10/23')).toBe(true)
    expect(noteExists(keys, '/Top')).toBe(true)
    expect(noteExists(keys, '/Plan')).toBe(false)
    expect(noteExists(keys, 'Nothing')).toBe(false)
    expect(noteExists(keys, 'Work/')).toBe(false)
  })

  it('a link into the note itself always exists', () => {
    expect(noteExists(new Set(), '#Heading')).toBe(true)
  })
})

describe('links in tables', () => {
  it('are read as the app reads them: a link cannot span cells', () => {
    // The same note as the app's WikiLinksTest: its header row has three cells.
    const doc = parseMarkdown('| [[a|b]] | [[c\\|d]] |\n| --- | --- | --- |\n| [[e]] | f |\n\nOutside: [[g|h]]\n')
    const links: [string, string | null][] = []
    doc.descendants((n) => {
      if (n.type.name === 'wikilink') links.push([n.attrs.target, n.attrs.alias])
    })
    expect(links).toEqual([['c', 'd'], ['e', null], ['g', 'h']])
  })
})
