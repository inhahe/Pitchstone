// Node views: list items with a tappable checkbox, images that degrade to their alt text, and
// note links, which show the picture when they embed an image (`![[pic.png]]`).

import {DOMSerializer, type Node} from 'prosemirror-model'
import type {EditorView, NodeView, ViewMutationRecord} from 'prosemirror-view'
import {schema} from '../schema'
import {attachmentUrl, decodeSrc, embedSize, isAttachmentSrc, isImageName} from './attachments'
import {toggleTaskAt} from './commands'
import {wikilinkNote} from './links'

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

/**
 * A picture; when it cannot load, a box with its name stands in. A `src` that is a path, not an
 * address, names a stored attachment (attachments.ts).
 */
function picture(src: string, alt: string, fallback: string): {wrap: HTMLElement; img: HTMLImageElement} {
  const wrap = document.createElement('span')
  wrap.className = 'image'
  const img = document.createElement('img')
  img.src = src
  img.alt = alt
  img.loading = 'lazy'
  img.addEventListener('error', () => {
    wrap.classList.add('broken')
    wrap.dataset.alt = fallback
  })
  wrap.append(img)
  return {wrap, img}
}

/** A markdown image, `![alt](src "title")`. */
export class ImageView implements NodeView {
  dom: HTMLElement

  constructor(node: Node) {
    const src: string = node.attrs.src
    const {wrap, img} = picture(isAttachmentSrc(src) ? attachmentUrl(decodeSrc(src)) : src, node.attrs.alt, node.attrs.alt || src)
    if (node.attrs.title) img.title = node.attrs.title
    this.dom = wrap
  }
}

/** A note link: as the schema draws it, or, embedding an image, the picture (sized `|300`). */
export class WikilinkView implements NodeView {
  dom: HTMLElement

  constructor(node: Node) {
    const name = wikilinkNote(node.attrs.target)
    if (node.attrs.embed && isImageName(name)) {
      const {wrap, img} = picture(attachmentUrl(name), name, name)
      wrap.classList.add('embed-image')
      const size = embedSize(node.attrs.alias)
      if (size) {
        img.width = size.width
        if (size.height) img.height = size.height
      }
      this.dom = wrap
    } else {
      this.dom = DOMSerializer.renderSpec(document, node.type.spec.toDOM!(node)).dom as HTMLElement
    }
  }
}
