// Decorations: #tags, bare URLs, wikilinks to notes that do not exist, find-in-note matches,
// and the empty-note placeholder. None of them change the document.

import type {Node} from 'prosemirror-model'
import {Plugin, PluginKey, type EditorState} from 'prosemirror-state'
import {Decoration, DecorationSet} from 'prosemirror-view'
import {schema} from '../schema'
import {noteExists} from './links'

const {nodes, marks} = schema

/** An Obsidian tag: `#` then letters, digits, `_`, `-`, `/`, with at least one non-digit. */
export const TAG = /(^|[\s([{])#([\p{L}\p{N}_\-/]*[\p{L}_\-/][\p{L}\p{N}_\-/]*)/gu
/** A bare http(s) URL, without trailing punctuation. */
export const URL_RE = /\bhttps?:\/\/[^\s<>"'`]*[^\s<>"'`.,;:!?)\]}]/g

export interface DecorationState {
  decos: DecorationSet
  /** The app's lookup keys of every note (see noteExists). */
  keys: Set<string>
  query: string
  current: number
  matches: {from: number; to: number}[]
}

export const decorationsKey = new PluginKey<DecorationState>('pitchstone-decorations')

type Meta = {keys?: string[]; query?: string; current?: number}

/**
 * Lower-cases `s` one UTF-16 unit at a time, leaving a character whose lower case is longer
 * (`İ`) as it is, so that positions in the result are positions in `s`.
 */
function foldCase(s: string): string {
  let out = ''
  for (const c of s.split('')) {
    const lower = c.toLowerCase()
    out += lower.length === 1 ? lower : c
  }
  return out
}

function findMatches(doc: Node, query: string): {from: number; to: number}[] {
  const out: {from: number; to: number}[] = []
  if (!query) return out
  const q = foldCase(query)
  doc.descendants((node, pos) => {
    if (!node.isTextblock) return true
    let text = ''
    const offsets: number[] = []
    node.forEach((child, offset) => {
      if (child.isText) {
        for (let i = 0; i < child.text!.length; i++) offsets.push(pos + 1 + offset + i)
        text += child.text
      } else {
        offsets.push(pos + 1 + offset)
        text += '￼'
      }
    })
    const lower = foldCase(text)
    let i = lower.indexOf(q)
    while (i >= 0) {
      out.push({from: offsets[i], to: offsets[i + q.length - 1] + 1})
      i = lower.indexOf(q, i + Math.max(1, q.length))
    }
    return false
  })
  return out
}

function build(doc: Node, keys: Set<string>, matches: {from: number; to: number}[], current: number): DecorationSet {
  const decos: Decoration[] = []
  doc.descendants((node, pos) => {
    if (node.type === nodes.code_block || node.type === nodes.frontmatter) return false
    if (node.type === nodes.wikilink && !noteExists(keys, node.attrs.target)) {
      decos.push(Decoration.node(pos, pos + node.nodeSize, {class: 'missing'}))
    }
    if (!node.isText) return true
    if (marks.code.isInSet(node.marks)) return false
    const text = node.text!
    if (!marks.link.isInSet(node.marks)) {
      for (const m of text.matchAll(URL_RE)) {
        const from = pos + m.index!
        decos.push(Decoration.inline(from, from + m[0].length, {class: 'bare-url', 'data-href': m[0]}))
      }
    }
    for (const m of text.matchAll(TAG)) {
      const from = pos + m.index! + m[1].length
      const tag = m[2]
      decos.push(Decoration.inline(from, from + tag.length + 1, {class: 'tag', 'data-tag': tag}))
    }
    return false
  })
  matches.forEach((m, i) => {
    decos.push(Decoration.inline(m.from, m.to, {class: i === current ? 'find-match find-current' : 'find-match'}))
  })
  const first = doc.firstChild
  if (doc.childCount === 1 && first && first.type === nodes.paragraph && first.content.size === 0) {
    decos.push(Decoration.node(0, first.nodeSize, {class: 'placeholder'}))
  }
  return DecorationSet.create(doc, decos)
}

export function decorationsPlugin(): Plugin<DecorationState> {
  return new Plugin<DecorationState>({
    key: decorationsKey,
    state: {
      init(_config, state) {
        return {decos: build(state.doc, new Set(), [], 0), keys: new Set(), query: '', current: 0, matches: []}
      },
      apply(tr, prev, _old, state) {
        const meta = tr.getMeta(decorationsKey) as Meta | undefined
        if (!tr.docChanged && !meta) return prev
        const keys = meta?.keys ? new Set(meta.keys) : prev.keys
        const query = meta?.query ?? prev.query
        const matches = findMatches(state.doc, query)
        let current = meta?.current ?? (meta?.query !== undefined ? 0 : prev.current)
        if (matches.length) current = ((current % matches.length) + matches.length) % matches.length
        else current = 0
        return {decos: build(state.doc, keys, matches, current), keys, query, current, matches}
      },
    },
    props: {
      decorations(state) {
        return decorationsKey.getState(state)!.decos
      },
    },
  })
}

export function decorationState(state: EditorState): DecorationState {
  return decorationsKey.getState(state)!
}
