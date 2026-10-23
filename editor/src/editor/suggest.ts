// Note suggestions while typing a wikilink: after `[[`, a list of matching note titles; picking
// one inserts the link. Only with markdown shortcuts on, as `[[` is otherwise literal text.

import {Plugin, PluginKey, type EditorState} from 'prosemirror-state'
import type {EditorView} from 'prosemirror-view'
import {schema} from '../schema'
import type {Mode} from './bridge'
import {linkKey, linkName} from './links'
import {settings} from './settings'

const OPEN = /(?:^|[^\\!])\[\[([^[\]\n|#]*)$/

interface Query {
  text: string
  /** Document range of `[[` plus the query. */
  from: number
  to: number
}

export const suggestKey = new PluginKey<{dismissedAt: number}>('pitchstone-suggest')

function queryAt(state: EditorState): Query | null {
  const sel = state.selection
  if (!sel.empty || !settings.markdownShortcuts) return null
  const $pos = sel.$from
  if ($pos.parent.type.spec.code || !$pos.parent.isTextblock) return null
  if ($pos.marks().some((m) => m.type === schema.marks.code)) return null
  const before = $pos.parent.textBetween(Math.max(0, $pos.parentOffset - 200), $pos.parentOffset, undefined, '￼')
  const m = OPEN.exec(before)
  if (!m) return null
  return {text: m[1], from: sel.from - m[1].length - 2, to: sel.from}
}

/** Titles matching `query`: prefix matches first, then shorter titles; at most `limit`. */
export function rankTitles(titles: readonly string[], query: string, limit = 8): string[] {
  const q = query.trim().toLowerCase()
  const scored: {t: string; score: number}[] = []
  for (const t of titles) {
    const lower = t.toLowerCase()
    const i = q ? lower.indexOf(q) : 0
    if (i < 0) continue
    scored.push({t, score: (i === 0 ? 0 : 1000) + t.length})
  }
  scored.sort((a, b) => a.score - b.score || a.t.localeCompare(b.t))
  return scored.slice(0, limit).map((s) => s.t)
}

class Suggestions {
  private el: HTMLElement
  private items: string[] = []
  private selected = 0
  private query: Query | null = null

  constructor(
    private view: EditorView,
    private titles: () => readonly string[],
    private mode: () => Mode,
  ) {
    this.el = document.createElement('div')
    this.el.className = 'suggest'
    this.el.hidden = true
    this.el.addEventListener('mousedown', (e) => e.preventDefault())
    this.el.addEventListener('pointerdown', (e) => e.preventDefault())
    document.body.append(this.el)
  }

  update(view: EditorView) {
    this.view = view
    const q = this.mode() === 'edit' ? queryAt(view.state) : null
    const dismissedAt = suggestKey.getState(view.state)?.dismissedAt ?? -1
    if (!q || q.from === dismissedAt) {
      this.hide()
      return
    }
    this.query = q
    const matches = rankTitles(this.titles(), q.text)
    const exact = matches.some((t) => linkKey(t) === linkKey(q.text))
    this.items = q.text.trim() && !exact ? [...matches, '\u0000' + q.text.trim()] : matches
    if (!this.items.length) {
      this.hide()
      return
    }
    this.selected = Math.min(this.selected, this.items.length - 1)
    this.render()
    this.position()
  }

  get visible() {
    return !this.el.hidden
  }

  private hide() {
    this.el.hidden = true
    this.query = null
    this.selected = 0
  }

  private render() {
    this.el.replaceChildren(
      ...this.items.map((item, i) => {
        const row = document.createElement('div')
        row.className = i === this.selected ? 'item selected' : 'item'
        if (item.startsWith('\u0000')) {
          row.classList.add('create')
          row.textContent = `Link to new note “${item.slice(1)}”`
        } else {
          row.textContent = item
        }
        row.addEventListener('click', (e) => {
          e.preventDefault()
          this.pick(i)
        })
        return row
      }),
    )
  }

  private position() {
    const q = this.query!
    let coords
    try {
      coords = this.view.coordsAtPos(q.from)
    } catch {
      return
    }
    const vv = window.visualViewport
    const viewportBottom = vv ? vv.offsetTop + vv.height : window.innerHeight
    this.el.hidden = false
    const height = this.el.offsetHeight
    let top = coords.bottom + 6
    if (top + height > viewportBottom - 8) top = Math.max(8, coords.top - height - 6)
    const width = this.el.offsetWidth
    const left = Math.max(8, Math.min(coords.left, window.innerWidth - width - 8))
    this.el.style.top = `${top + window.scrollY}px`
    this.el.style.left = `${left + window.scrollX}px`
  }

  pick(i: number) {
    const q = this.query
    if (!q) return
    const item = this.items[i]
    let target: string
    let alias: string | null = null
    if (item.startsWith('\u0000')) {
      target = item.slice(1)
    } else {
      // A title with `#` or `|` in it cannot be written as is; the app finds the note by its
      // link name, and the link keeps showing the real title where it can.
      target = linkName(item)
      if (target !== item && !/[[\]|\n\r]/.test(item)) alias = item
    }
    const node = schema.nodes.wikilink.create({target, alias, embed: false})
    const tr = this.view.state.tr.replaceWith(q.from, q.to, node)
    this.view.dispatch(tr.scrollIntoView())
    this.view.focus()
  }

  /** Hardware-keyboard navigation of the list. */
  handleKey(event: KeyboardEvent): boolean {
    if (!this.visible) return false
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const n = this.items.length
      this.selected = (this.selected + (event.key === 'ArrowDown' ? 1 : n - 1)) % n
      this.render()
      return true
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      this.pick(this.selected)
      return true
    }
    if (event.key === 'Escape') {
      const q = this.query
      if (q) this.view.dispatch(this.view.state.tr.setMeta(suggestKey, {dismissedAt: q.from}))
      return true
    }
    return false
  }

  destroy() {
    this.el.remove()
  }
}

export function suggestPlugin(titles: () => readonly string[], mode: () => Mode): Plugin {
  let instance: Suggestions | null = null
  return new Plugin({
    key: suggestKey,
    state: {
      init: () => ({dismissedAt: -1}),
      apply(tr, prev) {
        const meta = tr.getMeta(suggestKey)
        if (meta) return meta
        return tr.docChanged ? {dismissedAt: tr.mapping.map(prev.dismissedAt)} : prev
      },
    },
    view(view) {
      instance = new Suggestions(view, titles, mode)
      return instance
    },
    props: {
      handleKeyDown(_view, event) {
        return instance ? instance.handleKey(event) : false
      },
    },
  })
}
