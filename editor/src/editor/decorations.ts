// Decorations: #tags, bare URLs, wikilinks to notes that do not exist, find-in-note matches,
// and the empty-note placeholder. None of them change the document.

import type {Node} from 'prosemirror-model'
import {Plugin, PluginKey, type EditorState, type Transaction} from 'prosemirror-state'
import {Decoration, DecorationSet} from 'prosemirror-view'
import {schema} from '../schema'
import {isImageName} from './attachments'
import {noteExists, wikilinkNote} from './links'

const {nodes, marks} = schema

/** An Obsidian tag: `#` then letters, digits, `_`, `-`, `/`, with at least one non-digit. */
export const TAG = /(^|[\s([{])#([\p{L}\p{N}_\-/]*[\p{L}_\-/][\p{L}\p{N}_\-/]*)/gu
/** A bare http(s) URL, without trailing punctuation. */
export const URL_RE = /\bhttps?:\/\/[^\s<>"'`]*[^\s<>"'`.,;:!?)\]}]/g

export interface DecorationState {
  /** Everything shown: `content` plus find matches and the placeholder. */
  decos: DecorationSet
  /** Tags, bare URLs and links to missing notes, kept up to date block by block. */
  content: DecorationSet
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

/** Adds the decorations of the textblock `block` at `pos` to `out`: tags, bare URLs, links to missing notes. */
function blockDecorations(block: Node, pos: number, keys: ReadonlySet<string>, out: Decoration[]): void {
  if (block.type === nodes.code_block || block.type === nodes.frontmatter) return
  block.forEach((node, offset) => {
    const at = pos + 1 + offset
    // An embedded image is a file, not a note: it shows the picture, or a box when missing.
    if (node.type === nodes.wikilink && !(node.attrs.embed && isImageName(wikilinkNote(node.attrs.target))) && !noteExists(keys, node.attrs.target)) {
      out.push(Decoration.node(at, at + node.nodeSize, {class: 'missing'}))
    }
    if (!node.isText || marks.code.isInSet(node.marks)) return
    const text = node.text!
    if (!marks.link.isInSet(node.marks)) {
      for (const m of text.matchAll(URL_RE)) {
        const from = at + m.index!
        out.push(Decoration.inline(from, from + m[0].length, {class: 'bare-url', 'data-href': m[0]}))
      }
    }
    for (const m of text.matchAll(TAG)) {
      const from = at + m.index! + m[1].length
      const tag = m[2]
      out.push(Decoration.inline(from, from + tag.length + 1, {class: 'tag', 'data-tag': tag}))
    }
  })
}

/** The tags, bare URLs and missing-note links of the whole document. */
function contentDecorations(doc: Node, keys: ReadonlySet<string>): DecorationSet {
  const out: Decoration[] = []
  doc.descendants((node, pos) => {
    if (!node.isTextblock) return true
    blockDecorations(node, pos, keys, out)
    return false
  })
  return DecorationSet.create(doc, out)
}

/**
 * `set` (from contentDecorations) after `tr`: mapped, with the textblocks the transaction
 * touched decorated afresh. Typing in a long note so costs a block, not the whole document.
 */
function updateContent(set: DecorationSet, tr: Transaction, keys: ReadonlySet<string>): DecorationSet {
  const doc = tr.doc
  let next = set.map(tr.mapping, doc)
  const touched: [number, number][] = []
  tr.mapping.maps.forEach((map, i) => {
    const later = tr.mapping.slice(i + 1)
    map.forEach((_oldFrom, _oldTo, from, to) => touched.push([later.map(from, -1), later.map(to, 1)]))
  })
  const done = new Set<number>()
  const out: Decoration[] = []
  const size = doc.content.size
  for (const [from, to] of touched) {
    // One position beyond each side, so that a block ending or starting at the edit counts.
    doc.nodesBetween(Math.max(0, Math.min(from, to) - 1), Math.min(size, Math.max(from, to) + 1), (node, pos) => {
      if (!node.isTextblock) return true
      if (!done.has(pos)) {
        done.add(pos)
        next = next.remove(next.find(pos, pos + node.nodeSize))
        blockDecorations(node, pos, keys, out)
      }
      return false
    })
  }
  return next.add(doc, out)
}

/** `content` with the find matches and the empty-note placeholder. */
function withExtras(doc: Node, content: DecorationSet, matches: {from: number; to: number}[], current: number): DecorationSet {
  const extra: Decoration[] = []
  matches.forEach((m, i) => {
    extra.push(Decoration.inline(m.from, m.to, {class: i === current ? 'find-match find-current' : 'find-match'}))
  })
  const first = doc.firstChild
  if (doc.childCount === 1 && first && first.type === nodes.paragraph && first.content.size === 0) {
    extra.push(Decoration.node(0, first.nodeSize, {class: 'placeholder'}))
  }
  return extra.length ? content.add(doc, extra) : content
}

export function decorationsPlugin(): Plugin<DecorationState> {
  return new Plugin<DecorationState>({
    key: decorationsKey,
    state: {
      init(_config, state) {
        const content = contentDecorations(state.doc, new Set())
        return {decos: withExtras(state.doc, content, [], 0), content, keys: new Set(), query: '', current: 0, matches: []}
      },
      apply(tr, prev, _old, state) {
        const meta = tr.getMeta(decorationsKey) as Meta | undefined
        if (!tr.docChanged && !meta) return prev
        const keys = meta?.keys ? new Set(meta.keys) : prev.keys
        const content = meta?.keys
          ? contentDecorations(state.doc, keys)
          : tr.docChanged
            ? updateContent(prev.content, tr, keys)
            : prev.content
        const query = meta?.query ?? prev.query
        const matches = findMatches(state.doc, query)
        let current = meta?.current ?? (meta?.query !== undefined ? 0 : prev.current)
        if (matches.length) current = ((current % matches.length) + matches.length) % matches.length
        else current = 0
        return {decos: withExtras(state.doc, content, matches, current), content, keys, query, current, matches}
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
