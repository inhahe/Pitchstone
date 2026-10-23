// Every example of the CommonMark spec, read and written back: the written markdown must parse
// to the same document (after normalization), and writing that again must give the same text.

import {readFileSync} from 'node:fs'
import {describe, expect, it} from 'vitest'
import type {Node} from 'prosemirror-model'
import {parseMarkdown} from '../src/markdown/parse'
import {serializeMarkdown} from '../src/markdown/serialize'

interface Example {
  markdown: string
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
