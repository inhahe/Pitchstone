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

/** What androidEnterPlugin uses of prosemirror-view that is not public. */
interface ViewInternals {
  domObserver: {forceFlush(): void; flush(): void}
  input: {lastKeyCode: number | null; domChangeCount: number}
}

let internalsMissing = false

/** `view`'s internals, or null (said once on the console) if this prosemirror-view lacks them. */
function internals(view: EditorView): ViewInternals | null {
  const v = view as unknown as Partial<ViewInternals>
  if (
    typeof v.domObserver?.forceFlush === 'function' &&
    typeof v.domObserver.flush === 'function' &&
    typeof v.input?.domChangeCount === 'number' &&
    'lastKeyCode' in v.input
  ) {
    return v as ViewInternals
  }
  if (!internalsMissing) console.error("prosemirror-view internals changed: the keyboard's Enter is left to the browser")
  internalsMissing = true
  return null
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
          const pm = internals(view)
          if (!pm) return false
          pm.domObserver.forceFlush()
          pm.domObserver.flush()
          const enter = new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true, cancelable: true})
          if (!view.someProp('handleKeyDown', (f) => f(view, enter))) return false
          event.preventDefault()
          // What the browser's paragraph break would have told it: the key has been dealt with,
          // and the page has changed (so no fallback Backspace for a deletion just before).
          pm.input.lastKeyCode = null
          pm.input.domChangeCount++
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
