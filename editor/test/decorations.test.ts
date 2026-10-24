import {EditorState, TextSelection, type Transaction} from 'prosemirror-state'
import type {Decoration} from 'prosemirror-view'
import {describe, expect, it} from 'vitest'
import {decorationState, decorationsKey, decorationsPlugin} from '../src/editor/decorations'
import {parseMarkdown} from '../src/markdown/parse'
import {schema} from '../src/schema'
import {rng} from './fuzz/generate'

const START = [
  '# Plan #top',
  '',
  'See [[Plan]] and [[Nope]] at https://example.com/x, #tag and `#code`.',
  '',
  '- item #one with [[Plan|alias]]',
  '- [ ] task https://e.org',
  '',
  '> quote #q',
  '',
  '| a #t | [[Nope]] |',
  '| --- | --- |',
  '| https://x.y | b |',
  '',
  '```',
  '#not a tag',
  '```',
].join('\n')

const KEYS = ['plan']
const SNIPPETS = ['#tag', ' ', 'x', 'https://e.com/a ', '#', 'a', '(#x)', '.', ' #b/c ', '`']

function describe_(decos: Decoration[]): string[] {
  return decos.map((d) => `${d.from}-${d.to} ${JSON.stringify((d as unknown as {type: {attrs: unknown}}).type.attrs)}`).sort()
}

function withKeys(state: EditorState): EditorState {
  return state.apply(state.tr.setMeta(decorationsKey, {keys: KEYS}))
}

/** The decorations a fresh plugin computes for `state`'s document. */
function fresh(state: EditorState): string[] {
  const s = withKeys(EditorState.create({doc: state.doc, plugins: [decorationsPlugin()]}))
  return describe_(decorationState(s).content.find())
}

/** A random edit: typing, deleting a range (possibly across blocks), splitting, or a link. */
function randomEdit(state: EditorState, r: () => number): Transaction | null {
  const size = state.doc.content.size
  const pos = Math.floor(r() * (size + 1))
  const $pos = state.doc.resolve(pos)
  const tr = state.tr
  const kind = Math.floor(r() * 4)
  try {
    if (kind === 0 && $pos.parent.inlineContent) {
      tr.insertText(SNIPPETS[Math.floor(r() * SNIPPETS.length)], pos)
    } else if (kind === 1) {
      const end = Math.min(size, pos + 1 + Math.floor(r() * 12))
      tr.delete(pos, end)
    } else if (kind === 2 && $pos.parent.isTextblock && $pos.depth > 0) {
      tr.split(pos)
    } else if (kind === 3 && $pos.parent.inlineContent && !$pos.parent.type.spec.code) {
      tr.insert(pos, schema.nodes.wikilink.create({target: r() < 0.5 ? 'Plan' : 'Nope', alias: null, embed: false}))
    } else {
      return null
    }
    tr.setSelection(TextSelection.near(tr.doc.resolve(Math.min(pos, tr.doc.content.size))))
    return tr
  } catch {
    return null
  }
}

describe('decorations kept up to date block by block', () => {
  it('equal a full rebuild after every edit', () => {
    for (let seed = 1; seed <= 20; seed++) {
      const r = rng(seed)
      let state = withKeys(EditorState.create({doc: parseMarkdown(START), plugins: [decorationsPlugin()]}))
      expect(describe_(decorationState(state).content.find())).toEqual(fresh(state))
      for (let step = 0; step < 60; step++) {
        const tr = randomEdit(state, r)
        if (!tr || !tr.docChanged) continue
        state = state.apply(tr)
        expect(describe_(decorationState(state).content.find()), `seed ${seed} step ${step}`).toEqual(fresh(state))
      }
    }
  })
})
