// Every example of the CommonMark spec, twice: read and written back (the written markdown must
// parse to the same document, after normalization, and writing that again must give the same
// text), and rendered as the spec says it reads.

import {readFileSync} from 'node:fs'
import {describe, expect, it} from 'vitest'
import type {Node} from 'prosemirror-model'
import {createMarkdownIt} from '../src/markdown/md'
import {parseMarkdown} from '../src/markdown/parse'
import {serializeMarkdown} from '../src/markdown/serialize'

interface Example {
  markdown: string
  html: string
  example: number
  section: string
}

/** Text with line breaks as spaces and whitespace collapsed: what normalization must keep. */
const plain = (d: Node) =>
  d.textBetween(0, d.content.size, ' ', (leaf) => (leaf.type.name === 'hard_break' ? ' ' : '')).replace(/\s+/g, ' ')

const examples: Example[] = JSON.parse(readFileSync(new URL('./fixtures/commonmark-spec.json', import.meta.url), 'utf-8'))

describe('CommonMark spec examples survive a round trip', () => {
  for (const ex of examples) {
    it(`example ${ex.example} (${ex.section})`, () => {
      const d1 = parseMarkdown(ex.markdown)
      const r = serializeMarkdown(d1)
      const context = `input ${JSON.stringify(ex.markdown)}\noutput ${JSON.stringify(r.markdown)}`
      expect(r.exact, context).toBe(true)
      const d2 = parseMarkdown(r.markdown)
      expect(d2.eq(r.doc), context).toBe(true)
      expect(serializeMarkdown(d2).markdown, context).toBe(r.markdown)
      // Normalization may move whitespace out of formatting, but never changes the text.
      expect(plain(r.doc), context).toBe(plain(d1))
    })
  }
})

// The dialect is CommonMark with additions: every spec example must read as the spec says,
// unless one of the additions applies. (The round trip above only shows that the editor reads
// its own output consistently; a rule broken on both sides would pass it.)
describe('CommonMark spec examples read as the spec says', () => {
  const commonmark = createMarkdownIt({breaks: false})
  commonmark.renderer.rules.link_def = () => ''
  /** Examples where `[[...]]` is a wikilink in this dialect. */
  const WIKILINKS = new Set([548, 559, 590])
  // markdown-it writes an empty block quote without the line break inside.
  const html = (s: string) => s.replace(/<blockquote>\n<\/blockquote>/g, '<blockquote></blockquote>')
  for (const ex of examples) {
    it(`example ${ex.example} (${ex.section})`, () => {
      if (WIKILINKS.has(ex.example)) {
        expect(ex.markdown).toContain('[[')
        return
      }
      expect(html(commonmark.render(ex.markdown)), JSON.stringify(ex.markdown)).toBe(html(ex.html))
    })
  }
})
