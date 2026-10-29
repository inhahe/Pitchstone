// The bubble shown while editing when the cursor is on a link, a wikilink, a bare URL or a tag:
// it offers to open it (tapping a link while editing only places the cursor), edit or unlink.

import {NodeSelection, Plugin, type EditorState} from 'prosemirror-state'
import type {EditorView} from 'prosemirror-view'
import {schema} from '../schema'
import {type Mode, post} from './bridge'
import {currentLink, linkRangeAt, removeLinkCommand, unlinkWikilinkCommand} from './commands'
import {TAG, URL_RE} from './decorations'
import {endComposition} from './plugins'

type Target =
  | {kind: 'link'; href: string; pos: number}
  | {kind: 'wikilink'; target: string; alias: string | null; pos: number}
  | {kind: 'url'; href: string; pos: number}
  | {kind: 'tag'; tag: string; pos: number}

/** What the selection is on: a link, wikilink, bare URL or tag; null for plain text. */
export function targetAt(state: EditorState): Target | null {
  const sel = state.selection
  if (sel instanceof NodeSelection && sel.node.type === schema.nodes.wikilink) {
    return {kind: 'wikilink', target: sel.node.attrs.target, alias: sel.node.attrs.alias, pos: sel.from}
  }
  if (!sel.empty) return null
  return targetAtCursor(state, sel.from)
}

function targetAtCursor(state: EditorState, pos: number): Target | null {
  const $pos = state.doc.resolve(pos)
  if (!$pos.parent.isTextblock || $pos.parent.type.spec.code) return null
  const link = linkRangeAt(state, pos)
  if (link) return {kind: 'link', href: link.mark.attrs.href, pos: link.from}
  // Bare URLs and tags: search the text around the cursor in this textblock.
  const text = $pos.parent.textBetween(0, $pos.parent.content.size, undefined, '￼')
  const offset = $pos.parentOffset
  const start = $pos.start()
  for (const m of text.matchAll(URL_RE)) {
    if (m.index! <= offset && offset <= m.index! + m[0].length) return {kind: 'url', href: m[0], pos: start + m.index!}
  }
  for (const m of text.matchAll(TAG)) {
    const from = m.index! + m[1].length
    if (from <= offset && offset <= from + m[2].length + 1) return {kind: 'tag', tag: m[2], pos: start + from}
  }
  return null
}

/** What a tap at document position `pos` lands on (used in reading mode). */
export function targetAtPos(state: EditorState, pos: number): Target | null {
  const node = state.doc.nodeAt(pos)
  if (node?.type === schema.nodes.wikilink) {
    return {kind: 'wikilink', target: node.attrs.target, alias: node.attrs.alias, pos}
  }
  return targetAtCursor(state, pos)
}

export function openTarget(t: Target): void {
  if (t.kind === 'link' || t.kind === 'url') post({type: 'openLink', href: t.href})
  else if (t.kind === 'wikilink') post({type: 'openNote', target: t.target})
  else post({type: 'openTag', tag: t.tag})
}

class Bubble {
  private el: HTMLElement
  private key = ''

  constructor(
    view: EditorView,
    private mode: () => Mode,
  ) {
    this.el = document.createElement('div')
    this.el.className = 'bubble'
    this.el.hidden = true
    // Keep the editor focused (and the keyboard open) when a button is pressed.
    this.el.addEventListener('mousedown', (e) => e.preventDefault())
    this.el.addEventListener('pointerdown', (e) => e.preventDefault())
    document.body.append(this.el)
    this.update(view)
  }

  update(view: EditorView) {
    const target = this.mode() === 'edit' && view.hasFocus() ? targetAt(view.state) : null
    if (!target) {
      this.el.hidden = true
      this.key = ''
      return
    }
    const key = JSON.stringify(target)
    if (key !== this.key) {
      this.key = key
      this.render(view, target)
    }
    this.el.hidden = false
    this.position(view, target.pos)
  }

  private button(label: string, action: () => void, cls = '') {
    const b = document.createElement('button')
    b.type = 'button'
    b.textContent = label
    if (cls) b.className = cls
    b.addEventListener('click', (e) => {
      e.preventDefault()
      action()
    })
    return b
  }

  private render(view: EditorView, t: Target) {
    this.el.replaceChildren()
    const label = document.createElement('span')
    label.className = 'bubble-label'
    if (t.kind === 'link' || t.kind === 'url') label.textContent = t.href || '(no address)'
    else if (t.kind === 'wikilink') label.textContent = t.target
    else label.textContent = '#' + t.tag
    this.el.append(label)
    if (t.kind === 'tag') {
      this.el.append(this.button('Notes with tag', () => openTarget(t), 'primary'))
      return
    }
    this.el.append(this.button('Open', () => openTarget(t), 'primary'))
    if (t.kind === 'link') {
      this.el.append(
        this.button('Edit', () => {
          endComposition(view)
          post({type: 'editLink', ...currentLink(view.state)})
        }),
        this.button('Unlink', () => {
          endComposition(view)
          removeLinkCommand(view.state, view.dispatch)
        }),
      )
    } else if (t.kind === 'wikilink') {
      this.el.append(
        this.button('Edit', () => post({type: 'editWikilink', target: t.target, alias: t.alias})),
        this.button('Unlink', () => {
          endComposition(view)
          unlinkWikilinkCommand(view.state, view.dispatch)
        }),
      )
    }
  }

  private position(view: EditorView, pos: number) {
    let coords
    try {
      coords = view.coordsAtPos(pos)
    } catch {
      return
    }
    const vv = window.visualViewport
    const viewportBottom = vv ? vv.offsetTop + vv.height : window.innerHeight
    const height = this.el.offsetHeight || 40
    const width = this.el.offsetWidth || 200
    let top = coords.bottom + 8
    if (top + height > viewportBottom - 8) top = coords.top - height - 8
    const left = Math.max(8, Math.min(coords.left - 12, window.innerWidth - width - 8))
    this.el.style.top = `${top + window.scrollY}px`
    this.el.style.left = `${left + window.scrollX}px`
  }

  destroy() {
    this.el.remove()
  }
}

export function bubblePlugin(mode: () => Mode): Plugin {
  return new Plugin({view: (view) => new Bubble(view, mode)})
}
