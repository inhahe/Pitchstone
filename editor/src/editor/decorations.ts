// Decorations: #tags, bare URLs, wikilinks to notes that do not exist, find-in-note matches,
// and the empty-note placeholder. None of them change the document.

import type {Node} from 'prosemirror-model'
import {Plugin, PluginKey, type EditorState} from 'prosemirror-state'
import {Decoration, DecorationSet} from 'prosemirror-view'
import {schema} from '../schema'

const {nodes, marks} = schema

/** An Obsidian tag: `#` then letters, digits, `_`, `-`, `/`, with at least one non-digit. */
export const TAG = /(^|[\s([{])#([\p{L}\p{N}_\-/]*[\p{L}_\-/][\p{L}\p{N}_\-/]*)/gu
/** A bare http(s) URL, without trailing punctuation. */
export const URL_RE = /\bhttps?:\/\/[^\s<>"'`]*[^\s<>"'`.,;:!?)\]}]/g

export interface DecorationState {
  decos: DecorationSet
  /** Lower-cased titles (and folder/title paths) of existing notes. */
  titles: Set<string>
  query: string
  current: number
  matches: {from: number; to: number}[]
}

export const decorationsKey = new PluginKey<DecorationState>('inkdown-decorations')

type Meta = {titles?: string[]; query?: string; current?: number}

/** The note a wikilink target points at: the part before any `#heading` or `#^block`. */
export function wikilinkNote(target: string): string {
  const hash = target.indexOf('#')
  return (hash >= 0 ? target.slice(0, hash) : target).trim()
}

function noteExists(titles: Set<string>, target: string): boolean {
  const name = wikilinkNote(target).toLowerCase()
  if (!name) return true // [[#heading]] links within the note
  return titles.has(name) || titles.has(name.replace(/\.md$/, ''))
}

function findMatches(doc: Node, query: string): {from: number; to: number}[] {
  const out: {from: number; to: number}[] = []
  if (!query) return out
  const q = query.toLowerCase()
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
    const lower = text.toLowerCase()
    let i = lower.indexOf(q)
    while (i >= 0) {
      out.push({from: offsets[i], to: offsets[i + q.length - 1] + 1})
      i = lower.indexOf(q, i + Math.max(1, q.length))
    }
    return false
  })
  return out
}

function build(doc: Node, titles: Set<string>, matches: {from: number; to: number}[], current: number): DecorationSet {
  const decos: Decoration[] = []
  doc.descendants((node, pos) => {
    if (node.type === nodes.code_block || node.type === nodes.frontmatter) return false
    if (node.type === nodes.wikilink && !noteExists(titles, node.attrs.target)) {
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
        return {decos: build(state.doc, new Set(), [], 0), titles: new Set(), query: '', current: 0, matches: []}
      },
      apply(tr, prev, _old, state) {
        const meta = tr.getMeta(decorationsKey) as Meta | undefined
        if (!tr.docChanged && !meta) return prev
        const titles = meta?.titles ? new Set(meta.titles.map((t) => t.toLowerCase())) : prev.titles
        const query = meta?.query ?? prev.query
        const matches = findMatches(state.doc, query)
        let current = meta?.current ?? (meta?.query !== undefined ? 0 : prev.current)
        if (matches.length) current = ((current % matches.length) + matches.length) % matches.length
        else current = 0
        return {decos: build(state.doc, titles, matches, current), titles, query, current, matches}
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
