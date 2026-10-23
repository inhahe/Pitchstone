// Node views: list items with a tappable checkbox, and images that degrade to their alt text.

import type {Node} from 'prosemirror-model'
import type {EditorView, NodeView, ViewMutationRecord} from 'prosemirror-view'
import {schema} from '../schema'
import {toggleTaskAt} from './commands'

/**
 * A list item. Task items get a checkbox before their content; it toggles on tap, in reading
 * mode too, without moving the cursor or opening the keyboard.
 */
export class ListItemView implements NodeView {
  dom: HTMLElement
  contentDOM: HTMLElement
  private box: HTMLElement | null = null

  constructor(
    private node: Node,
    private view: EditorView,
    private getPos: () => number | undefined,
  ) {
    this.dom = document.createElement('li')
    if (node.attrs.checked === null) {
      this.contentDOM = this.dom
      return
    }
    this.dom.className = 'task'
    const box = document.createElement('span')
    box.className = 'checkbox'
    box.contentEditable = 'false'
    box.setAttribute('role', 'checkbox')
    box.addEventListener('mousedown', (e) => e.preventDefault())
    box.addEventListener('touchstart', (e) => e.stopPropagation(), {passive: true})
    box.addEventListener('click', (e) => {
      e.preventDefault()
      const pos = this.getPos()
      if (pos !== undefined) toggleTaskAt(pos)(this.view.state, this.view.dispatch)
    })
    this.box = box
    this.contentDOM = document.createElement('div')
    this.contentDOM.className = 'task-content'
    this.dom.append(box, this.contentDOM)
    this.sync()
  }

  private sync() {
    if (!this.box) return
    const checked = !!this.node.attrs.checked
    this.dom.dataset.checked = String(checked)
    this.box?.setAttribute('aria-checked', String(checked))
  }

  update(node: Node): boolean {
    if (node.type !== schema.nodes.list_item) return false
    if ((node.attrs.checked === null) !== (this.node.attrs.checked === null)) return false
    this.node = node
    this.sync()
    return true
  }

  stopEvent(event: Event): boolean {
    return !!this.box && event.target instanceof globalThis.Node && this.box.contains(event.target)
  }

  ignoreMutation(m: ViewMutationRecord): boolean {
    if (m.type === 'selection') return false
    return !!this.box && (m.target === this.box || this.box.contains(m.target) || (m.type === 'attributes' && m.target === this.dom))
  }
}

/** An image; when it cannot load, a box with its alt text stands in. */
export class ImageView implements NodeView {
  dom: HTMLElement

  constructor(node: Node) {
    const wrap = document.createElement('span')
    wrap.className = 'image'
    const img = document.createElement('img')
    img.src = node.attrs.src
    img.alt = node.attrs.alt
    if (node.attrs.title) img.title = node.attrs.title
    img.loading = 'lazy'
    img.addEventListener('error', () => {
      wrap.classList.add('broken')
      wrap.dataset.alt = node.attrs.alt || node.attrs.src
    })
    wrap.append(img)
    this.dom = wrap
  }
}
