// Lines within a paragraph. Enter starts a new line in the same paragraph (a line break: one
// newline in the markdown), as in Obsidian, and a blank line ends the paragraph. So a paragraph
// may hold several lines, and what acts a line at a time (a markdown shortcut typed at a line's
// start, the toolbar's block buttons) first makes its lines paragraphs of their own.
//
// An empty line in a paragraph is a blank line in the markdown, and lines are split off by the
// same reading the serializer writes them with (readRun), so that a split changes the file only
// by the blank line it puts between the lines it separates, each Enter adds one newline to it,
// and the editor shows no empty line the file lacks.

import {InputRule} from 'prosemirror-inputrules'
import {type Attrs, Fragment, type Node, type NodeType, type ResolvedPos, Slice} from 'prosemirror-model'
import {type Command, EditorState, NodeSelection, Selection, TextSelection, type Transaction} from 'prosemirror-state'
import {canJoin, findWrapping, liftTarget} from 'prosemirror-transform'
import {atomMarkdown, isBreak, readRun} from '../markdown/serialize'
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

/** Replaces the line breaks `from`..`to` with a paragraph break and `empty` empty paragraphs after it. */
function breaksToParagraphs(tr: Transaction, from: number, to: number, empty: number): void {
  const parts: Node[] = []
  for (let i = 0; i < empty + 2; i++) parts.push(nodes.paragraph.create())
  tr.replace(from, to, new Slice(Fragment.from(parts), 1, 1))
}

/** Where the run of line breaks ending at `pos` starts. */
function runStart(doc: Node, pos: number): number {
  while (isBreak(doc.nodeAt(pos - 1))) pos--
  return pos
}

/** Where the run of line breaks starting at `pos` ends. */
function runEnd(doc: Node, pos: number): number {
  while (isBreak(doc.nodeAt(pos))) pos++
  return pos
}

/** A run of line breaks to split a paragraph at, as found before any split. */
interface Run {
  from: number
  to: number
  /** No line before it (it starts the paragraph); no line after it (it ends it). */
  leading: boolean
  trailing: boolean
  /** In a top-level paragraph, where markdown keeps extra blank lines as spacers. */
  spacers: boolean
  /** Whether line breaks the reading keeps may stay, at the start of the lines after. */
  keep: boolean
}

/**
 * The run `from`..`to` in `doc`. A line being split off (`lineBefore`, `lineAfter`) counts as a
 * line next to it even when empty; line breaks the reading keeps stay only if `keep`.
 */
function run(doc: Node, from: number, to: number, opts: {lineBefore?: boolean; lineAfter?: boolean; keep?: boolean}): Run {
  const $from = doc.resolve(from)
  return {
    from,
    to,
    leading: !opts.lineBefore && from === $from.start(),
    trailing: !opts.lineAfter && to === $from.end(),
    spacers: $from.depth === 1,
    keep: !!opts.keep,
  }
}

/**
 * Splits a paragraph at `r` as markdown reads the run (readRun). A lone line break, which it
 * reads as a line break, becomes a paragraph break all the same: splitting is the point.
 * Returns where the lines after the run start.
 */
function splitAtRun(tr: Transaction, r: Run): number {
  const k = r.to - r.from
  const reading = readRun(k, r.leading, r.trailing, r.spacers)
  let {split, cut} = reading
  if (!split && !r.leading && !r.trailing) {
    split = true
    cut = k
  }
  // Kept line breaks would otherwise start a line about to become another block.
  if (!r.keep) cut = k
  if (!split) {
    tr.delete(r.from, r.from + cut)
    return r.to - cut
  }
  breaksToParagraphs(tr, r.from, r.from + cut, reading.empty)
  return r.to - cut + 2 + 2 * reading.empty
}

/**
 * Makes the lines touching `from`..`to` paragraphs of their own: the runs of line breaks between
 * them, before them and after them are split at (splitAtRun). Returns where a position in those
 * lines is afterwards (one at a line's start goes to the start of its paragraph), or null if no
 * line was split off.
 */
function splitLines(tr: Transaction, from: number, to: number): ((pos: number) => number) | null {
  const doc = tr.doc
  const runs: Run[] = []
  doc.nodesBetween(from, to, (node, pos) => {
    if (!node.isTextblock) return true
    const start = pos + 1
    if (!splittable(doc.resolve(start))) return false
    const lo = Math.max(from, start)
    const hi = Math.min(to, start + node.content.size)
    let before = -1
    let after = -1
    const within: number[] = []
    node.forEach((child, offset) => {
      if (child.type !== nodes.hard_break) return
      const at = start + offset
      if (at < lo) before = at
      else if (at < hi) within.push(at)
      else if (after < 0) after = at
    })
    if (before >= 0) runs.push(run(doc, runStart(doc, before), before + 1, {lineAfter: true}))
    // Between two of the lines; an empty line at the paragraph's end goes, as in the file.
    for (let i = 0, j = 1; i < within.length; i = j++) {
      while (j < within.length && within[j] === within[j - 1] + 1) j++
      runs.push(run(doc, within[i], within[j - 1] + 1, {}))
    }
    if (after >= 0) runs.push(run(doc, after, runEnd(doc, after), {lineBefore: true, keep: true}))
    return false
  })
  if (!runs.length) return null
  // From the end, so that each run is still where it was found; then a position after a run
  // moves as the run did.
  const moves = runs.sort((a, b) => b.from - a.from).map((r) => ({at: r.to, by: splitAtRun(tr, r) - r.to}))
  return (pos) => moves.reduce((p, move) => (pos >= move.at ? p + move.by : p), pos)
}

/**
 * Where the selection ends, by the lines it is on: one that reaches only the start of a line
 * (after a line break, or of a textblock) ends at the end of the line before, as in a code
 * editor.
 */
export function selectionEnd(state: EditorState): number {
  const sel = state.selection
  const $to = sel.$to
  if (sel.empty || !(sel instanceof TextSelection)) return sel.to
  if (isBreak($to.nodeBefore)) return sel.to - 1
  if ($to.parent.isTextblock && $to.parentOffset === 0) {
    const before = Selection.findFrom(state.doc.resolve($to.before()), -1, true)
    if (before && before.head >= sel.from) return before.head
  }
  return sel.to
}

/**
 * Splits off the lines after the one `pos` is on, in a paragraph whose lines may be split.
 * Returns the position right after the paragraph left holding that line, or null if no line
 * follows it.
 */
export function splitAfterLine(tr: Transaction, pos: number): number | null {
  const $pos = tr.doc.resolve(pos)
  if (!splittable($pos)) return null
  let at = -1
  $pos.parent.forEach((child, offset) => {
    if (at < 0 && child.type === nodes.hard_break && $pos.start() + offset >= pos) at = $pos.start() + offset
  })
  if (at < 0) return null
  const r = run(tr.doc, at, runEnd(tr.doc, at), {lineBefore: true, keep: true})
  splitAtRun(tr, r)
  return r.trailing ? null : at + 1
}

/**
 * If `pos` is on an empty line of a paragraph with other lines (whose lines may be split), makes
 * that line an empty paragraph of its own. Returns where that paragraph is, or null.
 */
export function splitOffEmptyLine(tr: Transaction, pos: number): number | null {
  const $pos = tr.doc.resolve(pos)
  if (!splittable($pos) || !$pos.parent.content.size) return null
  if (($pos.nodeBefore && !isBreak($pos.nodeBefore)) || ($pos.nodeAfter && !isBreak($pos.nodeAfter))) return null
  const map = splitLines(tr, pos, pos)
  return map && tr.doc.resolve(map(pos)).before()
}

/** Replaces the line breaks in the textblocks touching `from`..`to` with spaces (for blocks of one line). */
export function flattenBreaks(tr: Transaction, from: number, to: number): void {
  const breaks: number[] = []
  tr.doc.nodesBetween(from, to, (node, pos) => {
    if (node.type === nodes.hard_break) breaks.push(pos)
  })
  for (const pos of breaks.reverse()) tr.replaceWith(pos, pos + 1, schema.text(' '))
}

/** Whether `node` is an inline atom with markdown of its own (not text, not a line break). */
export const isMarkdownAtom = (node: Node) => node.isInline && node.isAtom && !node.isText && node.type !== nodes.hard_break

/**
 * Replaces the inline atoms (links to notes, images, footnote references, inline HTML) of the
 * textblocks touching `from`..`to` that are about to become code blocks with their markdown,
 * as text: a code block holds only text, and the atoms would otherwise vanish. Textblocks
 * that cannot become one (table cells) keep theirs, as setBlockType leaves them be.
 */
export function atomsToText(tr: Transaction, from: number, to: number): void {
  const atoms: {pos: number; node: Node}[] = []
  tr.doc.nodesBetween(from, to, (node, pos, parent, index) => {
    if (!node.isTextblock) return true
    if (!parent || !parent.canReplaceWith(index, index + 1, nodes.code_block)) return false
    node.forEach((child, offset) => {
      if (isMarkdownAtom(child)) atoms.push({pos: pos + 1 + offset, node: child})
    })
    return false
  })
  for (const {pos, node} of atoms.reverse()) tr.replaceWith(pos, pos + node.nodeSize, schema.text(atomMarkdown(node), node.marks))
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
 * `cmd`, a block command (a heading, a list, a quote...), on the selected lines (selectionEnd):
 * they become paragraphs of their own first, so that the rest of their paragraph is left as it
 * is.
 */
export function onLines(cmd: Command): Command {
  return (state, dispatch) => {
    const sel = state.selection
    const text = sel instanceof TextSelection
    // A selected link to a note or picture is on a line as the cursor would be.
    if (!text && !(sel instanceof NodeSelection && sel.node.isInline)) return cmd(state, dispatch)
    const to = selectionEnd(state)
    const tr = state.tr
    const map = splitLines(tr, sel.from, to) ?? ((pos: number) => pos)
    if (!tr.docChanged && to === sel.to) return cmd(state, dispatch)
    const mapEnd = (pos: number) => map(pos === sel.to ? to : pos)
    tr.setSelection(
      text ? TextSelection.create(tr.doc, mapEnd(sel.anchor), mapEnd(sel.head)) : NodeSelection.create(tr.doc, map(sel.from)),
    )
    let inner: Transaction | null = null
    if (!cmd(stateAfter(state, tr), dispatch && ((t) => (inner = t)))) return false
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
 * in CommonMark, where only that interrupts a paragraph). The lines of a paragraph that cannot be
 * split (a list item's) stay text after a line break.
 */
export function blockRule(pattern: RegExp, handler: BlockHandler, afterBreak?: (match: RegExpMatchArray) => boolean): InputRule {
  return new InputRule(new RegExp('(?:^|' + LEAF + ')' + pattern.source, pattern.flags), (state, match, start, end) => {
    const afterLineBreak = match[0].startsWith(LEAF)
    if (afterLineBreak) {
      // Other leaves (a link to a note) are U+FFFC too.
      if (!isBreak(state.doc.nodeAt(start)) || (afterBreak && !afterBreak(match))) return null
      start++
    }
    if (!splittable(state.doc.resolve(start))) return afterLineBreak ? null : handler(state, match, start, end)
    const tr = state.tr
    const map = splitLines(tr, start, end)
    if (!map) return handler(state, match, start, end)
    tr.setSelection(TextSelection.create(tr.doc, map(end)))
    const done = handler(stateAfter(state, tr), match, map(start), map(end))
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
    const tr = state.tr.delete(start, end)
    // What setBlockType would drop: a code block holds only text (its line breaks become
    // newlines), and a heading no line breaks, which would join the lines' words.
    const blockEnd = tr.mapping.map($start.end())
    if (type.whitespace === 'pre') atomsToText(tr, $start.start(), blockEnd)
    else if (!type.contentMatch.matchType(nodes.hard_break)) flattenBreaks(tr, $start.start(), blockEnd)
    return tr.setBlockType(start, start, type, getAttrs ? getAttrs(match) : null)
  }
}

/**
 * `enter`, the Enter key's commands, with the selection out of the way first: one over blocks
 * goes, as typing replaces it, and a selected picture or link to a note stays, Enter going in
 * before it (where the usual Enter splits a paragraph or a list item).
 */
export function enterReplacing(enter: Command): Command {
  return (state, dispatch, view) => {
    const sel = state.selection
    let tr: Transaction | null = null
    if (sel instanceof NodeSelection && sel.node.isInline) tr = state.tr.setSelection(TextSelection.create(state.doc, sel.from))
    else if (sel instanceof TextSelection && !sel.$from.sameParent(sel.$to)) tr = state.tr.deleteSelection()
    if (!tr) return enter(state, dispatch, view)
    let inner: Transaction | null = null
    if (!enter(stateAfter(state, tr), dispatch && ((t) => (inner = t)))) return false
    if (dispatch && inner) dispatch(append(tr, inner).scrollIntoView())
    return true
  }
}

/**
 * Enter in a paragraph starts a new line in it (a line break: one newline in the markdown), as
 * in Obsidian. On an empty line it ends the paragraph there (a blank line in the markdown), and
 * on the last line of a quote's paragraph it leaves the quote. An empty paragraph, or the start
 * of a top-level one, is left to the usual Enter: a new paragraph (another blank line). In a
 * quote or a footnote, which hold no empty paragraph, the start of one gets a line break.
 */
export const enterNewLine: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection)) return false
  const {$from, $to} = sel
  const para = $from.parent
  if (para.type !== nodes.paragraph || !$from.sameParent($to)) return false
  const contained = $from.depth > 1 && splittable($from)
  if (para.content.size === 0 || (sel.empty && $from.parentOffset === 0 && !contained)) return false
  const onEmptyLine = sel.empty && isBreak($from.nodeBefore) && (!$from.nodeAfter || isBreak($from.nodeAfter))
  if (!onEmptyLine || !splittable($from)) {
    if (dispatch) dispatch(state.tr.replaceSelectionWith(nodes.hard_break.create()).scrollIntoView())
    return true
  }
  if (dispatch) {
    const pos = $from.pos
    const from = runStart(state.doc, pos)
    const to = runEnd(state.doc, pos)
    const tr = state.tr
    const last = to === $from.end()
    let cursor: number
    if (last) {
      // Empty lines ending a paragraph are not written to the file (readRun): those above the
      // cursor's go as the run before a line would, and the cursor's line, with any below it,
      // starts a paragraph.
      cursor = splitAtRun(tr, run(tr.doc, from, pos, {lineAfter: true}))
    } else {
      // One more empty line, then the run as markdown reads it, the cursor on the line below
      // its own: a spacer at the top level, else a line break kept after the blank line.
      tr.insert(pos, nodes.hard_break.create())
      const r = run(tr.doc, from, to + 1, {keep: true})
      splitAtRun(tr, r)
      const above = pos - from - 1 + (r.leading ? 1 : 0)
      cursor = r.spacers ? from + 2 + 2 * above : r.leading ? pos + 1 : from + 2 + above
    }
    tr.setSelection(TextSelection.create(tr.doc, cursor))
    // The last line of a quote's paragraph, empty: out of the quote (splitting it, if more
    // follows), as a second Enter does in Obsidian; the quote could not keep it.
    const $in = tr.doc.resolve(cursor)
    const range = last && !$in.parent.content.size && $in.node(-1).type === nodes.blockquote ? $in.blockRange() : null
    const target = range && liftTarget(range)
    if (range && target !== null) tr.lift(range, target)
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
 * The cursor's line in a paragraph, from the line break before it (or the start) to the cursor,
 * if the cursor ends the line and the line is plain typed text: its text and where it starts.
 * In a paragraph whose lines may not be split, only a paragraph of one line counts.
 */
export function lineBeforeCursor(state: EditorState): {text: string; start: number} | null {
  const {$cursor} = state.selection as TextSelection
  if (!$cursor || $cursor.parent.type !== nodes.paragraph) return null
  const after = $cursor.nodeAfter
  if (after && after.type !== nodes.hard_break) return null
  const whole = !splittable($cursor)
  let text = ''
  let start = $cursor.start()
  let plain = true
  $cursor.parent.forEach((child, offset) => {
    if (child.type === nodes.hard_break) {
      if (whole) plain = false
      if (offset >= $cursor.parentOffset) return
      text = ''
      start = $cursor.start() + offset + 1
      if (!whole) plain = true
    } else if (offset >= $cursor.parentOffset) {
      return
    } else if (!child.isText || child.marks.length) {
      plain = false
    } else {
      text += child.text
    }
  })
  return plain ? {text, start} : null
}

/**
 * Enter at the end of a line of only `---` (at `lineStart`): the line becomes a horizontal rule,
 * and the cursor goes to a new line below it, before the lines that followed it.
 */
export const ruleOnEnter = (lineStart: number): Command => (state, dispatch) => {
  const {$cursor} = state.selection as TextSelection
  if (!$cursor) return false
  if (dispatch) {
    const tr = state.tr.delete(lineStart, $cursor.pos)
    // The lines above stay a paragraph of their own.
    const at =
      splittable($cursor) && isBreak(tr.doc.nodeAt(lineStart - 1))
        ? splitAtRun(tr, run(tr.doc, runStart(tr.doc, lineStart - 1), lineStart, {lineAfter: true}))
        : lineStart
    tr.insert(at - 1, nodes.horizontal_rule.create())
    tr.setSelection(TextSelection.create(tr.doc, at + 1))
    dispatch(tr.scrollIntoView())
  }
  return true
}
