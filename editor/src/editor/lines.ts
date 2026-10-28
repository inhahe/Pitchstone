// Lines within a paragraph. Enter starts a new line in the same paragraph (a line break: one
// newline in the markdown), as in Obsidian, and a blank line ends the paragraph. So a paragraph
// may hold several lines, and what acts a line at a time (a markdown shortcut typed at a line's
// start, the toolbar's block buttons) first makes its lines paragraphs of their own.

import {liftEmptyBlock} from 'prosemirror-commands'
import {InputRule} from 'prosemirror-inputrules'
import type {ResolvedPos} from 'prosemirror-model'
import {type Command, type EditorState, Selection, TextSelection, type Transaction} from 'prosemirror-state'
import {schema} from '../schema'

const {nodes} = schema

type Handler = (state: EditorState, match: RegExpMatchArray, start: number, end: number) => Transaction | null

/**
 * Whether `$pos` is in a paragraph whose lines may become paragraphs of their own: not one that
 * is a list item's (whose Enter makes items) or a cell's.
 */
function splittable($pos: ResolvedPos): boolean {
  if ($pos.parent.type !== nodes.paragraph || $pos.depth < 1) return false
  return $pos.node($pos.depth - 1).type !== nodes.list_item
}

/**
 * Makes the lines touching `from`..`to` paragraphs of their own: each line break that ends,
 * starts or lies between them becomes a paragraph break. Returns whether any did.
 */
export function splitLines(tr: Transaction, from: number, to: number): boolean {
  const breaks: number[] = []
  tr.doc.nodesBetween(from, to, (node, pos) => {
    if (!node.isTextblock) return true
    if (!splittable(tr.doc.resolve(pos + 1))) return false
    const start = pos + 1
    const lo = Math.max(from, start)
    const hi = Math.min(to, start + node.content.size)
    let before = -1
    let after = -1
    node.forEach((child, offset) => {
      if (child.type !== nodes.hard_break) return
      const at = start + offset
      if (at < lo) before = at
      else if (at < hi) breaks.push(at)
      else if (after < 0) after = at
    })
    if (before >= 0) breaks.push(before)
    if (after >= 0) breaks.push(after)
    return false
  })
  if (!breaks.length) return false
  for (const at of breaks.sort((a, b) => b - a)) {
    tr.delete(at, at + 1)
    tr.split(at)
  }
  return true
}

/**
 * Where `pos` is after [splitLines]: a position right after a line break goes to the start of
 * the new paragraph, any other stays where it was (the end of a line stays the end of its line).
 */
function mapOverSplit(state: EditorState, tr: Transaction, pos: number): number {
  const after = state.doc.resolve(pos).nodeBefore?.type === nodes.hard_break
  return tr.mapping.map(pos, after ? 1 : -1)
}

/** Appends the steps of `inner` (made on the document `tr` leads to) to `tr`, with its selection. */
function append(tr: Transaction, inner: Transaction): Transaction {
  for (const step of inner.steps) tr.step(step)
  tr.setSelection(Selection.fromJSON(tr.doc, inner.selection.toJSON()))
  if (inner.storedMarks) tr.setStoredMarks(inner.storedMarks)
  return tr
}

/**
 * `cmd`, a block command (a heading, a list, a quote...), on the selected lines: they become
 * paragraphs of their own first, so that the rest of their paragraph is left as it is.
 */
export function onLines(cmd: Command): Command {
  return (state, dispatch, view) => {
    const sel = state.selection
    if (!(sel instanceof TextSelection)) return cmd(state, dispatch, view)
    const tr = state.tr
    if (!splitLines(tr, sel.from, sel.to)) return cmd(state, dispatch, view)
    tr.setSelection(TextSelection.create(tr.doc, mapOverSplit(state, tr, sel.anchor), mapOverSplit(state, tr, sel.head)))
    const mid = state.apply(tr)
    let inner: Transaction | null = null
    if (!cmd(mid, (t) => (inner = t), view)) return false
    if (dispatch && inner) dispatch(append(tr, inner).scrollIntoView())
    return true
  }
}

/**
 * `rule`, a block shortcut whose pattern starts with `^` (the start of a textblock), and the
 * same at the start of a line after a line break: there the line becomes a paragraph of its own,
 * then `rule` applies to it.
 */
export function atLineStarts(rule: InputRule): InputRule[] {
  // Not in prosemirror-inputrules' declarations, but there.
  const parts = rule as unknown as {match: RegExp; handler: Handler; undoable: boolean; inCode: boolean | 'only'; inCodeMark: boolean}
  const source = parts.match.source
  if (!source.startsWith('^')) return [rule]
  // In the text a rule is matched against, a line break (a leaf node) is U+FFFC.
  const pattern = new RegExp('\ufffc' + source.slice(1), parts.match.flags)
  const onLine = new InputRule(
    pattern,
    (state, match, start, end) => {
      // `start` is the line break's position: other leaves (a link to a note) are U+FFFC too.
      if (state.doc.nodeAt(start)?.type !== nodes.hard_break || !splittable(state.doc.resolve(start))) return null
      const inner = parts.match.exec(match[0].slice(1))
      if (!inner) return null
      const tr = state.tr.delete(start, start + 1)
      tr.split(start)
      const lineEnd = tr.mapping.map(end, 1)
      tr.setSelection(TextSelection.create(tr.doc, lineEnd))
      const done = parts.handler(state.apply(tr), inner, start + 2, lineEnd)
      return done ? append(tr, done) : null
    },
    {undoable: parts.undoable, inCode: parts.inCode, inCodeMark: parts.inCodeMark},
  )
  return [rule, onLine]
}

/**
 * Enter in a paragraph starts a new line in it (a line break: one newline in the markdown), as
 * in Obsidian. On an empty line it ends the paragraph there (a blank line in the markdown), and
 * an empty last line of a quote leaves the quote. An empty paragraph, or the start of one, is
 * left to the usual Enter: a new paragraph (another blank line).
 */
export const enterNewLine: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection)) return false
  const {$from, $to} = sel
  const para = $from.parent
  if (para.type !== nodes.paragraph || !$from.sameParent($to)) return false
  if (para.content.size === 0 || (sel.empty && $from.parentOffset === 0)) return false
  const before = $from.nodeBefore
  const after = $from.nodeAfter
  const onEmptyLine = sel.empty && before?.type === nodes.hard_break && (!after || after.type === nodes.hard_break)
  if (!onEmptyLine || !splittable($from)) {
    if (dispatch) dispatch(state.tr.replaceSelectionWith(nodes.hard_break.create()).scrollIntoView())
    return true
  }
  if (dispatch) {
    // The empty line becomes a blank line: the lines around it, two paragraphs. Between lines,
    // the cursor stays on a line of its own, an empty paragraph (a second blank line).
    const pos = $from.pos
    const tr = state.tr
    if (after) tr.delete(pos, pos + 1)
    tr.delete(pos - 1, pos)
    const at = pos - 1
    tr.split(at)
    if (after) tr.split(at + 2)
    tr.setSelection(TextSelection.create(tr.doc, at + 2))
    const mid = state.apply(tr)
    const $in = mid.selection.$from
    // An empty last line in a quote: out of it, as a second Enter does in Obsidian.
    if (!after && $in.depth > 1 && $in.node(-1).type === nodes.blockquote && $in.after() === $in.end(-1)) {
      let lifted: Transaction | null = null
      liftEmptyBlock(mid, (t) => (lifted = t))
      if (lifted) append(tr, lifted)
    }
    dispatch(tr.scrollIntoView())
  }
  return true
}

/**
 * Backspace at the start of a paragraph after another removes the blank line between them, as
 * in Obsidian: they become lines of one paragraph. Another Backspace removes that line break.
 */
export const backspaceToLineBreak: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection) || !sel.empty) return false
  const $from = sel.$from
  if ($from.parentOffset > 0 || !splittable($from)) return false
  const index = $from.index($from.depth - 1)
  if (index === 0) return false
  const previous = $from.node($from.depth - 1).child(index - 1)
  // After an empty paragraph (a second blank line), Backspace removes that, as usual.
  if (previous.type !== nodes.paragraph || previous.content.size === 0) return false
  if (dispatch) {
    const boundary = $from.before()
    const tr = state.tr.join(boundary)
    tr.insert(boundary - 1, nodes.hard_break.create())
    tr.setSelection(TextSelection.create(tr.doc, boundary))
    dispatch(tr.scrollIntoView())
  }
  return true
}

/** Delete at the end of a paragraph before another: the same, from the other side. */
export const deleteToLineBreak: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection) || !sel.empty) return false
  const $from = sel.$from
  const para = $from.parent
  if (para.content.size === 0 || $from.parentOffset < para.content.size || !splittable($from)) return false
  const container = $from.node($from.depth - 1)
  const index = $from.index($from.depth - 1)
  if (index + 1 >= container.childCount) return false
  const next = container.child(index + 1)
  if (next.type !== nodes.paragraph || next.content.size === 0) return false
  if (dispatch) {
    const boundary = $from.after()
    const tr = state.tr.join(boundary)
    tr.insert(boundary - 1, nodes.hard_break.create())
    tr.setSelection(TextSelection.create(tr.doc, $from.pos))
    dispatch(tr.scrollIntoView())
  }
  return true
}
