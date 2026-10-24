import {Node} from 'prosemirror-model'
import {describe, expect, it} from 'vitest'
import {DocInterner} from '../src/markdown/interner'
import {parseMarkdown} from '../src/markdown/parse'
import {serializeMarkdown} from '../src/markdown/serialize'
import {schema} from '../src/schema'
import {Gen, build, rng} from './fuzz/generate'

describe('documents for the autosave worker', () => {
  it('are the documents the page sent, and serialize the same', () => {
    const docs = new DocInterner()
    for (let i = 0; i < 300; i++) {
      const doc = build(new Gen(rng(7_000_003 + i)).doc())!
      const rebuilt = docs.fromJSON(doc.toJSON())
      expect(rebuilt.eq(doc), `case ${i}`).toBe(true)
      rebuilt.check()
      expect(serializeMarkdown(rebuilt).markdown).toBe(serializeMarkdown(Node.fromJSON(schema, doc.toJSON())).markdown)
    }
  })

  it('keep the nodes of unchanged blocks from one document to the next', () => {
    const docs = new DocInterner()
    const before = parseMarkdown('# Title\n\n- one\n- two\n\nLast **para**.\n')
    const a = docs.fromJSON(before.toJSON())
    const after = parseMarkdown('# Title\n\n- one\n- two!\n\nLast **para**.\n')
    const b = docs.fromJSON(after.toJSON())
    const blocks = (d: Node) => {
      const out: Node[] = []
      d.descendants((n) => {
        if (n.isTextblock) out.push(n)
        return !n.isTextblock
      })
      return out
    }
    const [aTitle, aOne, aTwo, aLast] = blocks(a)
    const [bTitle, bOne, bTwo, bLast] = blocks(b)
    expect(bTitle).toBe(aTitle)
    expect(bOne).toBe(aOne)
    expect(bLast).toBe(aLast)
    expect(bTwo).not.toBe(aTwo)
    expect(b.eq(after)).toBe(true)
  })
})
