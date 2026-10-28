// Small plugins: structural fix-ups after edits, the toolbar state report, and the keyboard's
// Enter on Android.

import {redoDepth, undoDepth} from 'prosemirror-history'
import type {Node} from 'prosemirror-model'
import {liftListItem, sinkListItem} from 'prosemirror-schema-list'
import {type Command, type EditorState, Plugin} from 'prosemirror-state'
import {isInTable} from 'prosemirror-tables'
import {schema} from '../schema'
import {post, type ToolbarState} from './bridge'
import {findAncestor} from './commands'

const {nodes, marks} = schema

/** Android, as prosemirror-view tells it (its `android`, which it does not export). */
const ANDROID = typeof navigator !== 'undefined' && /Android \d/.test(navigator.userAgent)

/**
 * The keyboard's Enter on Android. ProseMirror leaves it to the browser there (an
 * `insertParagraph` input) and reads the paragraph break back from the page as an Enter. But a
 * keyboard often changes the word before it first (autocorrect, a capital letter), and after a
 * deletion ProseMirror waits 20 ms before reading the page: then both changes are read at once,
 * the Enter is not recognised, and the browser's paragraph break stays. So it is taken here:
 * what the page holds so far is read first, then `enter` runs in place of the browser's.
 */
export function androidEnterPlugin(enter: Command): Plugin {
  return new Plugin({
    props: {
      handleDOMEvents: {
        beforeinput(view, event) {
          if (!ANDROID || (event as InputEvent).inputType !== 'insertParagraph' || view.composing) return false
          // Not public: what ProseMirror's own key handling does before running a command.
          ;(view as unknown as {domObserver: {forceFlush(): void}}).domObserver.forceFlush()
          if (!enter(view.state, view.dispatch, view)) return false
          event.preventDefault()
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
