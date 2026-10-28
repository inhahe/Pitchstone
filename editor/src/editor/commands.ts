// Editing commands behind the toolbar and the keymap.

import {chainCommands, exitCode, lift, setBlockType, wrapIn} from 'prosemirror-commands'
import {redo, undo} from 'prosemirror-history'
import {Fragment, type Mark, type Node, type NodeType, type ResolvedPos} from 'prosemirror-model'
import {liftListItem, sinkListItem, splitListItem, wrapInList} from 'prosemirror-schema-list'
import {type Command, type EditorState, NodeSelection, TextSelection, type Transaction} from 'prosemirror-state'
import {
  addColumnAfter,
  addRowAfter,
  deleteColumn,
  deleteRow,
  deleteTable,
  goToNextCell,
  isInTable,
  selectedRect,
  TableMap,
} from 'prosemirror-tables'
import {atomMarkdown} from '../markdown/serialize'
import {append, flattenBreaks, onLines, splitAfterLine} from './lines'
import {schema} from '../schema'

const {nodes, marks} = schema

export {undo, redo}

/** The innermost ancestor of `$pos` matching `pred`, with its position. */
export function findAncestor($pos: ResolvedPos, pred: (n: Node) => boolean): {node: Node; pos: number; depth: number} | null {
  for (let d = $pos.depth; d > 0; d--) {
    const node = $pos.node(d)
    if (pred(node)) return {node, pos: $pos.before(d), depth: d}
  }
  return null
}

const isList = (n: Node) => n.type === nodes.bullet_list || n.type === nodes.ordered_list

/**
 * Level 0 makes a paragraph; the current heading level toggles back to a paragraph. Of a
 * paragraph's lines, only those selected become headings.
 */
export function headingCommand(level: number): Command {
  return onLines((state, dispatch) => {
    const {$from} = state.selection
    const current = $from.parent
    if (level === 0 || (current.type === nodes.heading && current.attrs.level === level)) {
      return setBlockType(nodes.paragraph)(state, dispatch)
    }
    if (!setBlockType(nodes.heading, {level})(state)) return false
    if (dispatch) {
      const tr = state.tr
      // The whole textblocks: a heading holds no line break (setBlockType would drop them).
      flattenBreaks(tr, state.selection.$from.start(), state.selection.$to.end())
      const mapped = tr.mapping
      const from = mapped.map(state.selection.from)
      const to = mapped.map(state.selection.to)
      tr.setBlockType(from, to, nodes.heading, {level})
      dispatch(tr.scrollIntoView())
    }
    return true
  })
}

/** Whether the list item around the selection is a task item. */
function inTaskItem(state: EditorState): boolean {
  const item = findAncestor(state.selection.$from, (n) => n.type === nodes.list_item)
  return !!item && item.node.attrs.checked !== null
}

/** Sets the task state of every list item directly inside `list` (at `listPos`). */
function setItemsChecked(tr: Transaction, listPos: number, task: boolean): void {
  const list = tr.doc.nodeAt(listPos)
  if (!list) return
  list.forEach((item, offset) => {
    const pos = listPos + 1 + offset
    const checked = task ? (item.attrs.checked ?? false) : null
    if (item.firstChild?.type !== nodes.paragraph && task) return
    if (checked !== item.attrs.checked) tr.setNodeMarkup(pos, null, {...item.attrs, checked})
  })
}

/** Lifts the selection out of every list around it. */
function liftOutOfLists(state: EditorState, dispatch?: (tr: Transaction) => void): boolean {
  let current = state
  let lifted = false
  const trs: Transaction[] = []
  for (let i = 0; i < 12 && findAncestor(current.selection.$from, (n) => n.type === nodes.list_item); i++) {
    let next: Transaction | null = null
    if (!liftListItem(nodes.list_item)(current, (tr) => (next = tr))) break
    trs.push(next!)
    current = current.apply(next!)
    lifted = true
  }
  if (lifted && dispatch) {
    const tr = state.tr
    for (const t of trs) append(tr, t)
    dispatch(tr.scrollIntoView())
  }
  return lifted
}

/**
 * Toggles a list of `type` (as a task list when `task`): turns it off when the selection is
 * already in such a list, converts a list of another kind, or wraps the selection in a new list,
 * an item a line.
 */
export function listCommand(type: NodeType, task = false): Command {
  return onLines((state, dispatch) => {
    const list = findAncestor(state.selection.$from, isList)
    if (list) {
      const sameKind = list.node.type === type && inTaskItem(state) === task
      if (sameKind) return liftOutOfLists(state, dispatch)
      if (dispatch) {
        const tr = state.tr
        if (list.node.type !== type) tr.setNodeMarkup(list.pos, type, type === nodes.ordered_list ? {order: 1} : null)
        setItemsChecked(tr, list.pos, task)
        dispatch(tr.scrollIntoView())
      }
      return true
    }
    let wrapped: Transaction | null = null
    if (!wrapInList(type)(state, (tr) => (wrapped = tr))) return false
    if (dispatch) {
      const tr = wrapped! as Transaction
      if (task) {
        const inner = findAncestor(tr.selection.$from, isList)
        if (inner) setItemsChecked(tr, inner.pos, true)
      }
      dispatch(tr.scrollIntoView())
    }
    return true
  })
}

export const indentCommand: Command = chainCommands(goToNextCell(1), sinkListItem(nodes.list_item))
export const outdentCommand: Command = chainCommands(goToNextCell(-1), liftListItem(nodes.list_item))

export const blockquoteCommand: Command = onLines((state, dispatch) => {
  if (findAncestor(state.selection.$from, (n) => n.type === nodes.blockquote)) return lift(state, dispatch)
  return wrapIn(nodes.blockquote)(state, dispatch)
})

/** Whether `node` is an inline atom with markdown of its own (not text, not a line break). */
const isMarkdownAtom = (node: Node) => node.isInline && node.isAtom && !node.isText && node.type !== nodes.hard_break

/**
 * Replaces the inline atoms (links to notes, images, footnote references, inline HTML) of the
 * textblocks touching `from`..`to` that are about to become code blocks with their markdown,
 * as text: a code block holds only text, and the atoms would otherwise vanish. Textblocks
 * that cannot become one (table cells) keep theirs, as setBlockType leaves them be.
 */
function atomsToText(tr: Transaction, from: number, to: number): void {
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

/**
 * Toggles a code block. Several selected paragraphs become one code block, one line each;
 * leaving a code block turns its lines back into one paragraph with line breaks. Formatting
 * is dropped; links to notes, images and inline HTML stay, as their markdown.
 */
export const codeBlockCommand: Command = onLines((state, dispatch) => {
  const {$from, $to} = state.selection
  if ($from.parent.type === nodes.code_block) {
    if (dispatch) {
      const block = $from.parent
      const pos = $from.before()
      const content: Node[] = []
      block.textContent.split('\n').forEach((line, i) => {
        if (i > 0) content.push(nodes.hard_break.create())
        if (line) content.push(schema.text(line))
      })
      const tr = state.tr.replaceWith(pos, pos + block.nodeSize, nodes.paragraph.create(null, content))
      tr.setSelection(TextSelection.create(tr.doc, Math.min(pos + 1 + $from.parentOffset, tr.doc.content.size)))
      dispatch(tr.scrollIntoView())
    }
    return true
  }
  const range = $from.blockRange($to)
  if (!range) return false
  const lines: string[] = []
  let ok = true
  for (let i = range.startIndex; i < range.endIndex; i++) {
    const child = range.parent.child(i)
    if (!child.isTextblock) {
      ok = false
      break
    }
    let line = ''
    child.forEach((n) => {
      line += n.isText ? n.text : n.type === nodes.hard_break ? '\n' : isMarkdownAtom(n) ? atomMarkdown(n) : n.textContent
    })
    lines.push(line)
  }
  if (!ok || !range.parent.canReplaceWith(range.startIndex, range.endIndex, nodes.code_block)) {
    // Each selected textblock becomes a code block of its own.
    if (!setBlockType(nodes.code_block)(state)) return false
    if (dispatch) {
      const tr = state.tr
      const {from, to} = state.selection
      atomsToText(tr, from, to)
      tr.setBlockType(tr.mapping.map(from, -1), tr.mapping.map(to), nodes.code_block)
      dispatch(tr.scrollIntoView())
    }
    return true
  }
  if (dispatch) {
    const text = lines.join('\n')
    const block = nodes.code_block.create(null, text ? schema.text(text) : null)
    const tr = state.tr.replaceWith(range.start, range.end, block)
    tr.setSelection(TextSelection.create(tr.doc, range.start + 1 + text.length))
    dispatch(tr.scrollIntoView())
  }
  return true
})

/**
 * A horizontal rule after the line the selection ends on (not one it only reaches the start of,
 * as in onLines): in a paragraph at the top level, the lines after it go below the rule;
 * elsewhere (a quote, a list) the rule goes after that whole block.
 */
export const horizontalRuleCommand: Command = (state, dispatch) => {
  const sel = state.selection
  const $end = state.doc.resolve(!sel.empty && sel.$to.nodeBefore?.type === nodes.hard_break ? sel.to - 1 : sel.to)
  if (dispatch) {
    const tr = state.tr
    const between = $end.depth === 1 ? splitAfterLine(tr, $end.pos) : null
    const after = between ?? ($end.depth === 0 ? $end.pos : tr.mapping.map($end.after(1)))
    const atEnd = between === null && $end.index(0) === state.doc.childCount - 1
    const insert = atEnd ? [nodes.horizontal_rule.create(), nodes.paragraph.create()] : [nodes.horizontal_rule.create()]
    tr.insert(after, insert)
    const target = Math.min(after + 2, tr.doc.content.size)
    tr.setSelection(TextSelection.near(tr.doc.resolve(target)))
    dispatch(tr.scrollIntoView())
  }
  return true
}

/** The extent of the link mark around `pos` (the link the cursor is in), if any. */
export function linkRangeAt(state: EditorState, pos: number): {from: number; to: number; mark: Mark} | null {
  const $pos = state.doc.resolve(pos)
  const parent = $pos.parent
  const start = $pos.start()
  let found: Mark | undefined
  const before = $pos.nodeBefore
  const after = $pos.nodeAfter
  found = (after && marks.link.isInSet(after.marks)) || (before && marks.link.isInSet(before.marks)) || undefined
  if (!found) return null
  // Walk the textblock's children and find the contiguous run containing pos with this mark.
  let from = -1
  let to = -1
  let offset = 0
  let runStart = -1
  for (let i = 0; i < parent.childCount; i++) {
    const child = parent.child(i)
    const has = found.isInSet(child.marks)
    if (has && runStart < 0) runStart = start + offset
    offset += child.nodeSize
    if (!has || i === parent.childCount - 1) {
      const runEnd = has ? start + offset : start + offset - child.nodeSize
      if (runStart >= 0 && runStart <= pos && pos <= runEnd) {
        from = runStart
        to = runEnd
        break
      }
      if (!has) runStart = -1
    }
  }
  return from < 0 ? null : {from, to, mark: found}
}

export interface LinkInfo {
  text: string
  href: string
  title: string | null
}

/** The link the selection is in, or the selected text for a new link. */
export function currentLink(state: EditorState): LinkInfo & {existing: boolean} {
  const {from, to, empty} = state.selection
  const range = linkRangeAt(state, from)
  if (range) {
    return {
      text: state.doc.textBetween(range.from, range.to, ' ', '￼'),
      href: range.mark.attrs.href,
      title: range.mark.attrs.title,
      existing: true,
    }
  }
  return {text: empty ? '' : state.doc.textBetween(from, to, ' ', ''), href: '', title: null, existing: false}
}

/** Creates or updates the link at the selection; an empty href removes it. */
export function setLinkCommand(info: LinkInfo): Command {
  return (state, dispatch) => {
    const href = info.href.trim()
    const range = linkRangeAt(state, state.selection.from)
    const tr = state.tr
    const linkMark = href ? marks.link.create({href, title: info.title || null}) : null
    if (range) {
      const oldText = state.doc.textBetween(range.from, range.to, ' ', '￼')
      let to = range.to
      if (info.text && info.text !== oldText) {
        const keep = state.doc.resolve(range.from).marksAcross(state.doc.resolve(range.to)) ?? []
        tr.replaceWith(range.from, range.to, schema.text(info.text, keep.filter((m) => m.type !== marks.link)))
        to = range.from + info.text.length
      }
      tr.removeMark(range.from, to, marks.link)
      if (linkMark) tr.addMark(range.from, to, linkMark)
    } else {
      if (!linkMark) return false
      const {from, to, empty} = state.selection
      const text = info.text || (empty ? href : '')
      if (empty || (text && text !== state.doc.textBetween(from, to, ' ', ''))) {
        const node = schema.text(text, [...state.selection.$from.marks().filter((m) => m.type !== marks.link), linkMark])
        tr.replaceSelectionWith(node, false)
      } else {
        tr.addMark(from, to, linkMark)
      }
      tr.removeStoredMark(marks.link)
    }
    if (dispatch) dispatch(tr.scrollIntoView())
    return true
  }
}

export const removeLinkCommand: Command = (state, dispatch) => {
  const range = linkRangeAt(state, state.selection.from)
  if (!range) return false
  if (dispatch) dispatch(state.tr.removeMark(range.from, range.to, marks.link))
  return true
}

/** Inserts a wikilink, or replaces the selected one. */
export function insertWikilinkCommand(target: string, alias: string | null, embed = false): Command {
  return (state, dispatch) => {
    const clean = target.replace(/[[\]|\n]/g, '').trim()
    if (!clean) return false
    const node = nodes.wikilink.create({
      target: clean,
      alias: alias ? alias.replace(/[[\]\n]/g, '') : null,
      embed,
    })
    if (dispatch) dispatch(state.tr.replaceSelectionWith(node, true).scrollIntoView())
    return true
  }
}

/** Removes the selected wikilink but keeps its text. */
export const unlinkWikilinkCommand: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof NodeSelection) || sel.node.type !== nodes.wikilink) return false
  if (dispatch) {
    const text = sel.node.attrs.alias || sel.node.attrs.target
    dispatch(state.tr.replaceSelectionWith(schema.text(text, sel.node.marks), false))
  }
  return true
}

export function insertTableCommand(rows = 3, cols = 3): Command {
  return (state, dispatch) => {
    const {$from} = state.selection
    if (isInTable(state)) return false
    const header = Array.from({length: cols}, () => nodes.table_header.create())
    const body = Array.from({length: rows - 1}, () =>
      nodes.table_row.create(null, Array.from({length: cols}, () => nodes.table_cell.create())),
    )
    const table = nodes.table.create(null, [nodes.table_row.create(null, header), ...body])
    if (dispatch) {
      const emptyPara = $from.parent.type === nodes.paragraph && $from.parent.content.size === 0
      const tr = state.tr
      let insertAt: number
      if (emptyPara && $from.depth >= 1) {
        insertAt = $from.before()
        tr.replaceWith(insertAt, $from.after(), table)
      } else {
        insertAt = $from.after(Math.max(1, $from.depth))
        tr.insert(insertAt, table)
      }
      const after = insertAt + table.nodeSize
      if (tr.doc.resolve(after).nodeAfter == null) tr.insert(after, nodes.paragraph.create())
      tr.setSelection(TextSelection.create(tr.doc, insertAt + 3))
      dispatch(tr.scrollIntoView())
    }
    return true
  }
}

export const tableCommands: Record<string, Command> = {
  addRowAfter,
  addColumnAfter,
  deleteRow,
  deleteColumn,
  deleteTable,
}

/** Sets the alignment of the columns the selection touches. */
export function alignColumnCommand(align: 'left' | 'center' | 'right' | null): Command {
  return (state, dispatch) => {
    if (!isInTable(state)) return false
    if (dispatch) {
      const rect = selectedRect(state)
      const tr = state.tr
      const map = rect.map
      for (let row = 0; row < map.height; row++) {
        for (let col = rect.left; col < rect.right; col++) {
          const pos = rect.tableStart + map.map[row * map.width + col]
          const cell = tr.doc.nodeAt(pos)
          if (cell && cell.attrs.align !== align) tr.setNodeMarkup(pos, null, {...cell.attrs, align})
        }
      }
      dispatch(tr)
    }
    return true
  }
}

/** Enter in a table cell moves to the cell below, adding a row at the bottom. */
export const enterInTable: Command = (state, dispatch) => {
  if (!isInTable(state) || !(state.selection instanceof TextSelection)) return false
  const rect = selectedRect(state)
  const map: TableMap = rect.map
  if (dispatch) {
    let tr = state.tr
    let tableStart = rect.tableStart
    let table = rect.table
    let tableMap = map
    if (rect.bottom >= map.height) {
      const next = state.apply(state.tr)
      let added: Transaction | null = null
      addRowAfter(next, (t) => (added = t))
      if (!added) return false
      tr = added
      table = tr.doc.nodeAt(tableStart - 1)!
      tableMap = TableMap.get(table)
      tableStart = rect.tableStart
    }
    const cellPos = tableStart + tableMap.map[rect.bottom * tableMap.width + rect.left]
    tr.setSelection(TextSelection.create(tr.doc, cellPos + 1))
    dispatch(tr.scrollIntoView())
  }
  return true
}

/** Splits a list item; a new item after a task item is an unchecked task. */
export const splitItemCommand: Command = (state, dispatch) => {
  const item = findAncestor(state.selection.$from, (n) => n.type === nodes.list_item)
  const attrs = item && item.node.attrs.checked !== null ? {checked: false} : undefined
  return splitListItem(nodes.list_item, attrs)(state, dispatch)
}

export const hardBreakCommand: Command = chainCommands(exitCode, (state, dispatch) => {
  const {$from} = state.selection
  if (!$from.parent.type.contentMatch.matchType(nodes.hard_break) && !$from.parent.canReplaceWith($from.index(), $from.index(), nodes.hard_break)) {
    return false
  }
  if (dispatch) dispatch(state.tr.replaceSelectionWith(nodes.hard_break.create()).scrollIntoView())
  return true
})

/**
 * Backspace at the start of a block undoes its block formatting first: a task item loses its
 * checkbox, then a list item leaves the list, a heading becomes a paragraph, a quote is lifted.
 */
export const backspaceAtStart: Command = (state, dispatch) => {
  const sel = state.selection
  if (!(sel instanceof TextSelection) || !sel.empty || sel.$from.parentOffset > 0) return false
  const $from = sel.$from
  const parent = $from.parent
  if (parent.type === nodes.heading) return setBlockType(nodes.paragraph)(state, dispatch)
  if (parent.type === nodes.code_block && parent.content.size === 0) return setBlockType(nodes.paragraph)(state, dispatch)
  if ($from.depth >= 2 && $from.index($from.depth - 1) === 0) {
    const container = $from.node($from.depth - 1)
    if (container.type === nodes.list_item) {
      if (container.attrs.checked !== null) {
        if (dispatch) dispatch(state.tr.setNodeMarkup($from.before($from.depth - 1), null, {...container.attrs, checked: null}))
        return true
      }
      return liftListItem(nodes.list_item)(state, dispatch)
    }
    if (container.type === nodes.blockquote) return lift(state, dispatch)
  }
  return false
}

/** Removes all inline formatting from the selection. */
export const clearFormattingCommand: Command = (state, dispatch) => {
  const {from, to, empty} = state.selection
  if (dispatch) {
    const tr = state.tr
    if (empty) tr.setStoredMarks([])
    else for (const type of Object.values(marks)) tr.removeMark(from, to, type)
    dispatch(tr)
  }
  return true
}

/** Inserts a footnote reference and a definition at the end of the note, and moves there. */
export const insertFootnoteCommand: Command = (state, dispatch) => {
  const used = new Set<string>()
  state.doc.descendants((n) => {
    if (n.type === nodes.footnote_def || n.type === nodes.footnote_ref) used.add(String(n.attrs.label))
  })
  let n = 1
  while (used.has(String(n))) n++
  const label = String(n)
  if (dispatch) {
    const tr = state.tr.replaceSelectionWith(nodes.footnote_ref.create({label}), true)
    const end = tr.doc.content.size
    tr.insert(end, nodes.footnote_def.create({label}, nodes.paragraph.create()))
    tr.setSelection(TextSelection.create(tr.doc, end + 2))
    dispatch(tr.scrollIntoView())
  }
  return true
}

/** Toggles the checkbox of the task item at `pos` (the list item's position). */
export function toggleTaskAt(pos: number): Command {
  return (state, dispatch) => {
    const item = state.doc.nodeAt(pos)
    if (!item || item.type !== nodes.list_item || item.attrs.checked === null) return false
    if (dispatch) dispatch(state.tr.setNodeMarkup(pos, null, {...item.attrs, checked: !item.attrs.checked}))
    return true
  }
}

export type {Fragment}
