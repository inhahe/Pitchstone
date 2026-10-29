// Small plugins: structural fix-ups after edits, the toolbar state report, and the keyboard's
// Enter on Android.

import {redoDepth, undoDepth} from 'prosemirror-history'
import type {Node} from 'prosemirror-model'
import {liftListItem, sinkListItem} from 'prosemirror-schema-list'
import {type EditorState, Plugin} from 'prosemirror-state'
import type {EditorView} from 'prosemirror-view'
import {isInTable} from 'prosemirror-tables'
import {schema} from '../schema'
import {post, type ToolbarState} from './bridge'
import {findAncestor} from './commands'

const {nodes, marks} = schema

/** Android, as prosemirror-view tells it (its `android`, which it does not export). */
const ANDROID = typeof navigator !== 'undefined' && /Android \d/.test(navigator.userAgent)

// What is used of prosemirror-view that is not public: its reading of the page (readPage), and
// its record of the last key and of changes on the page (androidEnterPlugin).
interface DomObserver {
  forceFlush(): void
  flush(): void
}
interface InputState {
  lastKeyCode: number | null
  domChangeCount: number
}

let internalsMissing = false

/** Null, said once on the console: this prosemirror-view lacks what is used of it. */
function missing(): null {
  if (!internalsMissing) {
    console.error("prosemirror-view internals changed: changes on the page may be read late, and the keyboard's Enter is left to the browser")
  }
  internalsMissing = true
  return null
}

function domObserver(view: EditorView): DomObserver | null {
  const o = (view as unknown as {domObserver?: Partial<DomObserver>}).domObserver
  return typeof o?.forceFlush === 'function' && typeof o.flush === 'function' ? (o as DomObserver) : missing()
}

function inputState(view: EditorView): InputState | null {
  const input = (view as unknown as {input?: Partial<InputState>}).input
  return typeof input?.domChangeCount === 'number' && 'lastKeyCode' in input ? (input as InputState) : missing()
}

/**
 * Reads into the editor's state the changes on the page it has not read yet (ProseMirror reads
 * some later: a deletion on Android 20 ms on, the end of a composition at once after). Returns
 * false if this prosemirror-view does not have what that takes.
 */
export function readPage(view: EditorView): boolean {
  const observer = domObserver(view)
  if (!observer) return false
  observer.forceFlush()
  observer.flush()
  return true
}

/** Whether endComposition is leaving the editor and coming back: not a blur or focus to act on. */
export let endingComposition = false

/**
 * Ends the keyboard's composition of the word being typed (the underlined word), before
 * something other than typing changes the text: a command, a picked suggestion. Until it ends,
 * the keyboard goes on writing that word through such a change: the marks set for what is
 * typed next (bold off, strikethrough on) are dropped at the next key, and a word replaced or
 * reformatted gets written again. Ended, the next key starts a new word, typed with the stored
 * marks. Leaving the editor and coming back is what makes the browser end a composition, with
 * the event that tells the keyboard (the keyboard stays up). Done whenever the editor has focus:
 * ProseMirror's own record of a composition ends after 5 s without input on Android, the
 * keyboard's does not. The page is read before and after, so that the cursor put back is where
 * the page has it.
 */
export function endComposition(view: EditorView): void {
  if (!view.hasFocus()) return
  readPage(view)
  endingComposition = true
  try {
    view.dom.blur()
    readPage(view)
    view.focus()
  } finally {
    endingComposition = false
  }
}

/**
 * The keyboard's Enter on Android, an `insertParagraph` input, taken from the browser: the
 * changes on the page so far are read first, then Enter goes to the editor's key handlers, as a
 * key press does elsewhere (a suggestion's pick, then the keymap). Left to the browser, its
 * paragraph break is read back later, and when the keyboard has just corrected the word before
 * it, together with that, as a change that is not an Enter (design.md).
 */
export function androidEnterPlugin(): Plugin {
  return new Plugin({
    props: {
      handleDOMEvents: {
        beforeinput(view, event) {
          if (!ANDROID || event.inputType !== 'insertParagraph' || !event.cancelable || view.composing) return false
          const input = inputState(view)
          if (!input || !readPage(view)) return false
          const enter = new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true, cancelable: true})
          if (!view.someProp('handleKeyDown', (f) => f(view, enter))) return false
          event.preventDefault()
          // What the browser's paragraph break would have told it: the key has been dealt with,
          // and the page has changed (so no fallback Backspace for a deletion just before).
          input.lastKeyCode = null
          input.domChangeCount++
          return true
        },
      },
    },
  })
}

/**
 * Keeps edits within what markdown can express structurally: a table's first row is its
 * header and only its first row; a task item starts with a paragraph (where its `[ ]` lives).
 */
export function structurePlugin(): Plugin {
  return new Plugin({
    appendTransaction(trs, _old, state) {
      if (!trs.some((t) => t.docChanged)) return null
      const tr = state.tr
      const fix = (node: Node, pos: number) => {
        if (node.type === nodes.table) {
          node.forEach((row, rowOffset, r) => {
            row.forEach((cell, cellOffset) => {
              const want = r === 0 ? nodes.table_header : nodes.table_cell
              if (cell.type !== want) {
                tr.setNodeMarkup(pos + 1 + rowOffset + 1 + cellOffset, want, cell.attrs)
              }
            })
          })
          return false
        }
        if (node.type === nodes.list_item && node.attrs.checked !== null && node.firstChild?.type !== nodes.paragraph) {
          tr.setNodeMarkup(pos, null, {...node.attrs, checked: null})
        }
        return !node.isTextblock
      }
      state.doc.descendants(fix)
      return tr.docChanged ? tr : null
    },
  })
}

export function toolbarState(state: EditorState): ToolbarState {
  const {$from, from, to, empty} = state.selection
  const active = (type: (typeof marks)[string]) =>
    empty ? !!type.isInSet(state.storedMarks || $from.marks()) : state.doc.rangeHasMark(from, to, type)
  const list = findAncestor($from, (n) => n.type === nodes.bullet_list || n.type === nodes.ordered_list)
  const item = findAncestor($from, (n) => n.type === nodes.list_item)
  const parent = $from.parent
  return {
    strong: active(marks.strong),
    em: active(marks.em),
    strike: active(marks.strike),
    highlight: active(marks.highlight),
    code: active(marks.code),
    link: active(marks.link),
    block: parent.type.name,
    headingLevel: parent.type === nodes.heading ? parent.attrs.level : 0,
    list: list ? (list.node.type === nodes.bullet_list ? 'bullet' : 'ordered') : null,
    task: !!item && item.node.attrs.checked !== null,
    quote: !!findAncestor($from, (n) => n.type === nodes.blockquote),
    table: isInTable(state),
    canUndo: undoDepth(state) > 0,
    canRedo: redoDepth(state) > 0,
    canIndent: sinkListItem(nodes.list_item)(state),
    canOutdent: liftListItem(nodes.list_item)(state),
  }
}

/** Reports the toolbar state to the app whenever it changes. */
export function toolbarPlugin(): Plugin {
  let last = ''
  return new Plugin({
    view() {
      return {
        update(view) {
          const json = JSON.stringify(toolbarState(view.state))
          if (json === last) return
          last = json
          post({type: 'state', state: JSON.parse(json)})
        },
      }
    },
  })
}
