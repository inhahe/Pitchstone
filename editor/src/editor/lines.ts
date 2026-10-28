// Lines within a paragraph. Enter starts a new line in the same paragraph (a line break: one
// newline in the markdown), as in Obsidian, and a blank line ends the paragraph. So a paragraph
// may hold several lines, and what acts a line at a time (a markdown shortcut typed at a line's
// start, the toolbar's block buttons) first makes its lines paragraphs of their own.

import {liftEmptyBlock} from 'prosemirror-commands'
import {InputRule} from 'prosemirror-inputrules'
import type {Attrs, Node, NodeType, ResolvedPos} from 'prosemirror-model'
import {type Command, EditorState, NodeSelection, Selection, TextSelection, type Transaction} from 'prosemirror-state'
import {canJoin, findWrapping} from 'prosemirror-transform'
import {schema} from '../schema'

const {nodes} = schema

/** U+FFFC: what a line break (a leaf node) is in the text an input rule is matched against. */
const LEAF = String.fromCharCode(0xfffc)

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
 * Where `pos`, in the lines [splitLines] was given, is after it: at the start of a line (right
 * after a line break, which became a paragraph break), the start of that line's paragraph; any
 * other position stays on its line.
 */
function mapOverSplit(state: EditorState, tr: Transaction, pos: number): number {
  const lineStart = state.doc.resolve(pos).nodeBefore?.type === nodes.hard_break
  return tr.mapping.map(pos, -1) + (lineStart ? 2 : 0)
}

/** The state `tr` leads to, for working out a next step: without plugins (and their appended transactions). */
function stateAfter(state: EditorState, tr: Transaction): EditorState {
  return EditorState.create({schema: state.schema, doc: tr.doc, selection: tr.selection, storedMarks: tr.storedMarks})
}

/** Appends the steps of `inner` (made on the document `tr` leads to) to `tr`, with its selection. */
export function append(tr: Transaction, inner: Transaction): Transaction {
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
    const text = sel instanceof TextSelection
    // A selected link to a note or picture is on a line as the cursor would be.
    if (!text && !(sel instanceof NodeSelection && sel.node.isInline)) return cmd(state, dispatch, view)
    const tr = state.tr
    if (!splitLines(tr, sel.from, sel.to)) return cmd(state, dispatch, view)
    tr.setSelection(
      text
        ? TextSelection.create(tr.doc, mapOverSplit(state, tr, sel.anchor), mapOverSplit(state, tr, sel.head))
        : NodeSelection.create(tr.doc, mapOverSplit(state, tr, sel.from)),
    )
    let inner: Transaction | null = null
    if (!cmd(stateAfter(state, tr), (t) => (inner = t), view)) return false
    if (dispatch && inner) dispatch(append(tr, inner).scrollIntoView())
    return true
  }
}

/** What a block shortcut does with its line `start`..`end` (the typed text pending). */
type BlockHandler = (state: EditorState, match: RegExpMatchArray, start: number, end: number) => Transaction | null

/**
 * A block shortcut: `pattern` (anchored at the end with `$`, not at the start) typed at the start
 * of a paragraph, or of a line after a line break. The line becomes a paragraph of its own (the
 * lines before and after it stay in theirs), then `handler` makes it the block. `afterBreak`, if
 * given, says whether a match after a line break counts (a numbered list there only from 1, as
 * in CommonMark, where only that interrupts a paragraph).
 */
export function blockRule(pattern: RegExp, handler: BlockHandler, afterBreak?: (match: RegExpMatchArray) => boolean): InputRule {
  return new InputRule(new RegExp('(?:^|' + LEAF + ')' + pattern.source, pattern.flags), (state, match, start, end) => {
    if (match[0].startsWith(LEAF)) {
      // Other leaves (a link to a note) are U+FFFC too.
      if (state.doc.nodeAt(start)?.type !== nodes.hard_break || (afterBreak && !afterBreak(match))) return null
      start++
    }
    if (!splittable(state.doc.resolve(start))) return handler(state, match, start, end)
    const tr = state.tr
    if (!splitLines(tr, start, end)) return handler(state, match, start, end)
    const lineStart = mapOverSplit(state, tr, start)
    const lineEnd = mapOverSplit(state, tr, end)
    tr.setSelection(TextSelection.create(tr.doc, lineEnd))
    const done = handler(stateAfter(state, tr), match, lineStart, lineEnd)
    return done ? append(tr, done) : null
  })
}

/** Wraps the line in `type` (a list, a quote), joining a list just before it of the kind `join` accepts. */
export function wrapHandler(
  type: NodeType,
  getAttrs?: (match: RegExpMatchArray) => Attrs | null,
  join?: (match: RegExpMatchArray, list: Node) => boolean,
): BlockHandler {
  // As prosemirror-inputrules' wrappingInputRule.
  return (state, match, start, end) => {
    const tr = state.tr.delete(start, end)
    const range = tr.doc.resolve(start).blockRange()
    const wrapping = range && findWrapping(range, type, getAttrs ? getAttrs(match) : null)
    if (!range || !wrapping) return null
    tr.wrap(range, wrapping)
    const before = tr.doc.resolve(start - 1).nodeBefore
    if (before && before.type === type && canJoin(tr.doc, start - 1) && (!join || join(match, before))) tr.join(start - 1)
    return tr
  }
}

/** Makes the line a textblock of `type` (a heading, a code block). */
export function textblockHandler(type: NodeType, getAttrs?: (match: RegExpMatchArray) => Attrs | null): BlockHandler {
  // As prosemirror-inputrules' textblockTypeInputRule.
  return (state, match, start, end) => {
    const $start = state.doc.resolve(start)
    if (!$start.node(-1).canReplaceWith($start.index(-1), $start.indexAfter(-1), type)) return null
    return state.tr.delete(start, end).setBlockType(start, start, type, getAttrs ? getAttrs(match) : null)
  }
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
    // An empty last line in a quote: out of it, as a second Enter does in Obsidian.
    const $in = tr.doc.resolve(at + 2)
    if (!after && $in.depth > 1 && $in.node(-1).type === nodes.blockquote && $in.after() === $in.end(-1)) {
      let lifted: Transaction | null = null
      liftEmptyBlock(stateAfter(state, tr), (t) => (lifted = t))
      if (lifted) append(tr, lifted)
    }
    dispatch(tr.scrollIntoView())
  }
  return true
}

/**
 * Joins the paragraphs either side of `boundary` (the position between them) as lines of one
 * paragraph, with a line break between; `cursor` says where the cursor goes, given that break's
 * position.
 */
function joinAsLines(state: EditorState, boundary: number, cursor: (lineBreak: number) => number): Transaction {
  const tr = state.tr.join(boundary)
  tr.insert(boundary - 1, nodes.hard_break.create())
  tr.setSelection(TextSelection.create(tr.doc, cursor(boundary - 1)))
  return tr.scrollIntoView()
}

/**
 * Whether the block next to the cursor's paragraph, on the side `dir` (-1: before, 1: after), is
 * a paragraph with text in the same container, and the cursor's paragraph one whose lines may be
 * split: then the two may become lines of one.
 */
function joinsAsLines($pos: ResolvedPos, dir: -1 | 1): boolean {
  if (!splittable($pos)) return false
  const container = $pos.node($pos.depth - 1)
  const index = $pos.index($pos.depth - 1) + dir
  if (index < 0 || index >= container.childCount) return false
  const other = container.child(index)
  return other.type === nodes.paragraph && other.content.size > 0
}

/**
 * Backspace at the start of a paragraph after another removes the blank line between them, as
 * in Obsidian: they become lines of one paragraph. Another Backspace removes that line break.
 * After an empty paragraph (a second blank line), Backspace removes that, as usual.
 */
export const backspaceToLineBreak: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection) || !sel.empty || sel.$from.parentOffset > 0 || !joinsAsLines(sel.$from, -1)) return false
  if (dispatch) dispatch(joinAsLines(state, sel.$from.before(), (lineBreak) => lineBreak + 1))
  return true
}

/**
 * Delete at the end of a paragraph before another: the same, from the other side. On an empty
 * paragraph, Delete removes it, as usual.
 */
export const deleteToLineBreak: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection) || !sel.empty) return false
  const $from = sel.$from
  if ($from.parent.content.size === 0 || $from.parentOffset < $from.parent.content.size || !joinsAsLines($from, 1)) return false
  if (dispatch) dispatch(joinAsLines(state, $from.after(), (lineBreak) => lineBreak))
  return true
}

/**
 * The text of the cursor's line in a paragraph, from the line break before it (or the start) to
 * the cursor, if the cursor ends the line and the line is plain typed text; else null.
 */
export function lineBeforeCursor(state: EditorState): string | null {
  const {$cursor} = state.selection as TextSelection
  if (!$cursor || $cursor.parent.type !== nodes.paragraph) return null
  const after = $cursor.nodeAfter
  if (after && after.type !== nodes.hard_break) return null
  let text = ''
  let plain = true
  $cursor.parent.forEach((child, offset) => {
    if (offset >= $cursor.parentOffset) return
    if (child.type === nodes.hard_break) {
      text = ''
      plain = true
    } else if (!child.isText || child.marks.length) {
      plain = false
    } else {
      text += child.text
    }
  })
  return plain ? text : null
}
