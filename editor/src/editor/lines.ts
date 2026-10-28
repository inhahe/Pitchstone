// Lines within a paragraph. Enter starts a new line in the same paragraph (a line break: one
// newline in the markdown), as in Obsidian, and a blank line ends the paragraph. So a paragraph
// may hold several lines, and what acts a line at a time (a markdown shortcut typed at a line's
// start, the toolbar's block buttons) first makes its lines paragraphs of their own.
//
// An empty line in a paragraph is a blank line in the markdown (paragraphsOfLines in
// serialize.ts). Lines are split off the way the file reads them, so that a split changes the
// file only by the blank line it puts between the lines it separates, and each Enter adds one
// newline to it.

import {liftEmptyBlock} from 'prosemirror-commands'
import {InputRule} from 'prosemirror-inputrules'
import {type Attrs, Fragment, type Node, type NodeType, type ResolvedPos, Slice} from 'prosemirror-model'
import {type Command, EditorState, NodeSelection, Selection, TextSelection, type Transaction} from 'prosemirror-state'
import {canJoin, findWrapping} from 'prosemirror-transform'
import {schema} from '../schema'

const {nodes} = schema

/** U+FFFC: what a line break (a leaf node) is in the text an input rule is matched against. */
const LEAF = String.fromCharCode(0xfffc)

const isBreak = (node: Node | null | undefined) => node?.type === nodes.hard_break

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

/**
 * Splits a paragraph at the run of line breaks `from`..`to`, as the markdown reads the empty
 * lines in it: a paragraph break, then an empty paragraph for each further empty line, and for
 * each one the paragraph starts with. Without lines after it (`linesAfter` false), at the end of
 * the paragraph, the run is not written to the file, and just goes. Returns where the lines
 * after the run start (`from`, without any).
 */
function splitAtBreaks(tr: Transaction, from: number, to: number, linesAfter: boolean): number {
  if (!linesAfter) {
    tr.delete(from, to)
    return from
  }
  const k = to - from
  const empty = from === tr.doc.resolve(from).start() ? k - 1 : Math.max(0, k - 2)
  breaksToParagraphs(tr, from, to, empty)
  return from + 2 + 2 * empty
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

/**
 * Makes the lines touching `from`..`to` paragraphs of their own: the line breaks between them,
 * and the runs of them before and after, become paragraph breaks (see splitAtBreaks). Returns
 * where a position in those lines is afterwards (one at a line's start goes to the start of its
 * paragraph), or null if no line was split off.
 */
function splitLines(tr: Transaction, from: number, to: number): ((pos: number) => number) | null {
  const runs: {from: number; to: number; linesAfter: boolean}[] = []
  tr.doc.nodesBetween(from, to, (node, pos) => {
    if (!node.isTextblock) return true
    const start = pos + 1
    if (!splittable(tr.doc.resolve(start))) return false
    const end = start + node.content.size
    const lo = Math.max(from, start)
    const hi = Math.min(to, end)
    let before = -1
    let after = -1
    node.forEach((child, offset) => {
      if (child.type !== nodes.hard_break) return
      const at = start + offset
      if (at < lo) before = at
      else if (at < hi) {
        // Between two of the lines: a run of its own, or one more in the run before it.
        const last = runs[runs.length - 1]
        if (last && last.to === at && last.from >= lo) last.to++
        else runs.push({from: at, to: at + 1, linesAfter: true})
      } else if (after < 0) after = at
    })
    if (before >= 0) runs.push({from: runStart(tr.doc, before), to: before + 1, linesAfter: true})
    if (after >= 0) {
      const runTo = runEnd(tr.doc, after)
      runs.push({from: after, to: runTo, linesAfter: runTo < end})
    }
    return false
  })
  if (!runs.length) return null
  // From the end, so that each run is still where it was found; then a position after a run
  // moves as the run did.
  const moves = runs
    .sort((a, b) => b.from - a.from)
    .map((run) => ({at: run.to, by: splitAtBreaks(tr, run.from, run.to, run.linesAfter) - run.to}))
  return (pos) => moves.reduce((p, move) => (pos >= move.at ? p + move.by : p), pos)
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
  const to = runEnd(tr.doc, at)
  const linesAfter = to < $pos.end()
  splitAtBreaks(tr, at, to, linesAfter)
  return linesAfter ? at + 1 : null
}

/** Replaces the line breaks in the textblocks touching `from`..`to` with spaces (for blocks of one line). */
export function flattenBreaks(tr: Transaction, from: number, to: number): void {
  const breaks: number[] = []
  tr.doc.nodesBetween(from, to, (node, pos) => {
    if (node.type === nodes.hard_break) breaks.push(pos)
  })
  for (const pos of breaks.reverse()) tr.replaceWith(pos, pos + 1, schema.text(' '))
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
 * paragraphs of their own first, so that the rest of their paragraph is left as it is. A
 * selection that reaches only the start of a line leaves that line out, as in a code editor.
 */
export function onLines(cmd: Command): Command {
  return (state, dispatch) => {
    const sel = state.selection
    const text = sel instanceof TextSelection
    // A selected link to a note or picture is on a line as the cursor would be.
    if (!text && !(sel instanceof NodeSelection && sel.node.isInline)) return cmd(state, dispatch)
    const to = !sel.empty && isBreak(sel.$to.nodeBefore) && splittable(sel.$to) ? sel.to - 1 : sel.to
    const tr = state.tr
    const map = splitLines(tr, sel.from, to)
    if (!map) return cmd(state, dispatch)
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
    // A heading holds no line break (setBlockType would drop them, joining the lines' words); a
    // code block turns them into newlines.
    if (type.whitespace !== 'pre' && !type.contentMatch.matchType(nodes.hard_break)) {
      flattenBreaks(tr, $start.start(), tr.mapping.map($start.end()))
    }
    return tr.setBlockType(start, start, type, getAttrs ? getAttrs(match) : null)
  }
}

/**
 * Enter in a paragraph starts a new line in it (a line break: one newline in the markdown), as
 * in Obsidian. On an empty line it ends the paragraph there (a blank line in the markdown), and
 * an empty last line of a quote leaves the quote. An empty paragraph, or the start of one, is
 * left to the usual Enter: a new paragraph (another blank line). A selection over paragraphs
 * goes, and Enter is pressed where it was; a selected picture or link to a note gets a line
 * break before it, where the usual Enter would split the paragraph.
 */
export const enterNewLine: Command = (state, dispatch) => {
  const sel = state.selection
  if (sel instanceof NodeSelection) {
    const $from = sel.$from
    if (!sel.node.isInline || $from.parent.type !== nodes.paragraph || $from.parentOffset === 0) return false
    if (dispatch) dispatch(state.tr.insert(sel.from, nodes.hard_break.create()).scrollIntoView())
    return true
  }
  if (!(sel instanceof TextSelection)) return false
  const {$from, $to} = sel
  const para = $from.parent
  if (para.type !== nodes.paragraph) return false
  if (!$from.sameParent($to)) {
    const tr = state.tr.deleteSelection()
    let inner: Transaction | null = null
    if (!enterNewLine(stateAfter(state, tr), (t) => (inner = t))) return false
    if (dispatch && inner) dispatch(append(tr, inner).scrollIntoView())
    return true
  }
  if (para.content.size === 0 || (sel.empty && $from.parentOffset === 0)) return false
  const onEmptyLine = sel.empty && isBreak($from.nodeBefore) && (!$from.nodeAfter || isBreak($from.nodeAfter))
  if (!onEmptyLine || !splittable($from)) {
    if (dispatch) dispatch(state.tr.replaceSelectionWith(nodes.hard_break.create()).scrollIntoView())
    return true
  }
  if (dispatch) {
    // The empty lines around the cursor become a paragraph break and empty paragraphs, as the
    // markdown reads them (see splitAtBreaks), with one more for this Enter, and the cursor goes
    // to the line below its own. At the end of the paragraph, the empty lines were not written
    // to the file: only the cursor's stays, as an empty paragraph.
    const pos = $from.pos
    const from = runStart(state.doc, pos)
    const to = runEnd(state.doc, pos)
    const leading = from === $from.start() ? 1 : 0
    const trailing = to === $from.end()
    // Empty lines in the run, before a line follows it: those above the cursor's, and all.
    const above = pos - from - 1 + leading
    const lines = to - from - 1 + leading
    const tr = state.tr
    breaksToParagraphs(tr, from, to, trailing ? 0 : lines)
    const cursor = from + 2 + 2 * (trailing ? 0 : above)
    tr.setSelection(TextSelection.create(tr.doc, cursor))
    // An empty last line in a quote: out of it, as a second Enter does in Obsidian.
    const $in = tr.doc.resolve(cursor)
    if (trailing && $in.depth > 1 && $in.node(-1).type === nodes.blockquote && $in.after() === $in.end(-1)) {
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
    const at = splittable($cursor) && isBreak(tr.doc.nodeAt(lineStart - 1))
      ? splitAtBreaks(tr, runStart(tr.doc, lineStart - 1), lineStart, true)
      : lineStart
    tr.insert(at - 1, nodes.horizontal_rule.create())
    tr.setSelection(TextSelection.create(tr.doc, at + 1))
    dispatch(tr.scrollIntoView())
  }
  return true
}
