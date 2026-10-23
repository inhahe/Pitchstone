// Random documents for property tests of the markdown round trip.
//
// Documents are generated as ProseMirror JSON so that a failing case can be shrunk by deleting
// parts of the JSON and re-checking.

import {Node} from 'prosemirror-model'
import {schema} from '../../src/schema'

export type Json = {type: string; attrs?: Record<string, unknown>; content?: Json[]; text?: string; marks?: Json[]}

/** mulberry32: small, fast, seedable. */
export function rng(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const SPECIAL = ['*', '_', '~', '=', '[', ']', '(', ')', '#', '>', '-', '+', '!', '|', '\\', '`', '<', '&', ';', ':', '^', '.', '"', "'", '/', '$', '{', '}', '1', '0', ')']
const PLAIN = ['a', 'b', 'Z', 'x', '7', ' ', ' ', ' ', 'é', '中', '😀', ' ', '\t', 'http://e.com', 'amp;', '#x41;', 'tag', '\n', '\r']

export class Gen {
  constructor(private r: () => number) {}

  int(n: number): number {
    return Math.floor(this.r() * n)
  }
  pick<T>(xs: readonly T[]): T {
    return xs[this.int(xs.length)]
  }
  chance(p: number): boolean {
    return this.r() < p
  }

  text(max = 8): string {
    const n = 1 + this.int(max)
    let s = ''
    for (let i = 0; i < n; i++) s += this.chance(0.45) ? this.pick(SPECIAL) : this.pick(PLAIN)
    return s
  }

  href(): string {
    return this.pick([
      'https://example.com',
      'https://e.com/a_(b)',
      'http://x.y/a b',
      '/rel/path',
      'a(b',
      'mailto:a@b.c',
      '',
      'https://例え.jp/ä',
      'x&amp;y',
      'javascript:alert(1)',
      '<weird>',
      'https://e.com/*a*_b_',
      '#frag',
    ])
  }

  marks(allowLink: boolean, allowCode: boolean): Json[] {
    const ms: Json[] = []
    if (allowLink && this.chance(0.12)) {
      ms.push({type: 'link', attrs: {href: this.href(), title: this.chance(0.2) ? this.text(4) : null}})
    }
    for (const m of ['strong', 'em', 'strike', 'highlight']) if (this.chance(0.18)) ms.push({type: m})
    if (allowCode && this.chance(0.12)) ms.push({type: 'code'})
    return ms
  }

  inline(flat: boolean, footnotes: string[]): Json[] {
    const out: Json[] = []
    const n = 1 + this.int(6)
    for (let i = 0; i < n; i++) {
      const roll = this.r()
      if (roll < 0.7) {
        out.push({type: 'text', text: this.text(), marks: this.marks(true, true)})
      } else if (roll < 0.78 && !flat) {
        out.push({type: 'hard_break'})
      } else if (roll < 0.84) {
        const alias = this.chance(0.4) ? this.pick(['alias', 'a b', '', 'x|y', 'é']) : null
        out.push({
          type: 'wikilink',
          attrs: {target: this.pick(['Note', 'A note', 'x#head', 'ünï', 'a\\', 'n/p']), alias, embed: this.chance(0.2)},
          marks: this.marks(false, false),
        })
      } else if (roll < 0.88) {
        out.push({type: 'image', attrs: {src: this.href(), alt: this.text(4), title: this.chance(0.3) ? this.text(3) : null}, marks: this.marks(true, false)})
      } else if (roll < 0.92 && footnotes.length) {
        out.push({type: 'footnote_ref', attrs: {label: this.pick(footnotes)}, marks: this.marks(false, false)})
      } else if (roll < 0.96) {
        out.push({type: 'html_inline', attrs: {raw: this.pick(['<b>', '</b>', '<!-- c -->', '<span class="x">', '<br/>', '<a href="x  \ny">'])}, marks: this.marks(false, false)})
      } else {
        out.push({type: 'text', text: this.pick(['**', '*', '__', '_', '~~', '==', '`', '``', '[[', ']]', '[^1]', '\\', '<', '&copy;']), marks: this.marks(true, true)})
      }
    }
    return out
  }

  block(depth: number, footnotes: string[]): Json {
    const roll = this.r()
    const deep = depth < 3
    if (roll < 0.4) return {type: 'paragraph', content: this.inline(false, footnotes)}
    if (roll < 0.47) return {type: 'heading', attrs: {level: 1 + this.int(6)}, content: this.chance(0.9) ? this.inline(true, footnotes) : []}
    if (roll < 0.53) {
      const lines: string[] = []
      for (let i = this.int(4); i >= 0; i--) lines.push(this.pick(['', '```', '  ```', '~~~', '    x', '\tt', 'code', '# h', '> q', ' ', '`` x']))
      const text = lines.join('\n')
      return {type: 'code_block', attrs: {language: this.pick(['', 'js', 'a`b', 'py title="x"'])}, content: text ? [{type: 'text', text}] : []}
    }
    if (roll < 0.57) return {type: 'horizontal_rule'}
    if (roll < 0.64 && deep) return {type: 'blockquote', content: this.blocks(depth + 1, footnotes, 1 + this.int(3))}
    if (roll < 0.76 && deep) {
      const ordered = this.chance(0.4)
      const items: Json[] = []
      for (let i = this.int(3); i >= 0; i--) {
        items.push({
          type: 'list_item',
          attrs: {checked: this.chance(0.3) ? this.chance(0.5) : null},
          content: this.blocks(depth + 1, footnotes, 1 + this.int(2)),
        })
      }
      return ordered
        ? {type: 'ordered_list', attrs: {order: this.pick([1, 1, 0, 3, 10])}, content: items}
        : {type: 'bullet_list', content: items}
    }
    if (roll < 0.82) {
      const cols = 1 + this.int(3)
      const rows: Json[] = []
      for (let r = this.int(3); r >= 0; r--) {
        const cells: Json[] = []
        for (let c = 0; c < cols; c++) {
          cells.push({
            type: rows.length === 0 ? 'table_header' : 'table_cell',
            attrs: {align: this.pick([null, 'left', 'center', 'right'])},
            content: this.chance(0.85) ? this.inline(true, footnotes) : [],
          })
        }
        rows.push({type: 'table_row', content: cells})
      }
      return {type: 'table', content: rows}
    }
    if (roll < 0.86 && deep) {
      return {type: 'footnote_def', attrs: {label: this.pick(['1', '2', 'note'])}, content: this.blocks(depth + 1, footnotes, 1 + this.int(2))}
    }
    if (roll < 0.9) return {type: 'link_def', attrs: {raw: this.pick(['[foo]: /url', '[bar]: https://x.com "Title"', '[Note]: <a b>'])}}
    if (roll < 0.94) return {type: 'html_block', attrs: {raw: this.pick(['<div>\nx\n</div>', '<!-- comment -->', '<details>'])}}
    return {type: 'paragraph', content: []}
  }

  blocks(depth: number, footnotes: string[], n: number): Json[] {
    const out: Json[] = []
    for (let i = 0; i < n; i++) out.push(this.block(depth, footnotes))
    return out
  }

  doc(): Json {
    const footnotes = this.chance(0.3) ? ['1', 'note'] : []
    const content: Json[] = []
    if (this.chance(0.1)) content.push({type: 'frontmatter', content: this.chance(0.8) ? [{type: 'text', text: 'a: 1\nb: [x]'}] : []})
    content.push(...this.blocks(0, footnotes, 1 + this.int(5)))
    if (footnotes.length) content.push({type: 'footnote_def', attrs: {label: '1'}, content: [{type: 'paragraph', content: [{type: 'text', text: 'fn'}]}]})
    return {type: 'doc', content}
  }
}

/** Builds and validates a document; returns null if the JSON is not valid for the schema. */
export function build(json: Json): Node | null {
  try {
    const node = Node.fromJSON(schema, json)
    node.check()
    return node
  } catch {
    return null
  }
}

/** All JSON documents one deletion or simplification smaller than `json`. */
export function* shrinks(json: Json): Generator<Json> {
  if (json.content) {
    for (let i = 0; i < json.content.length; i++) {
      yield {...json, content: json.content.filter((_, j) => j !== i)}
    }
    for (let i = 0; i < json.content.length; i++) {
      for (const smaller of shrinks(json.content[i])) {
        yield {...json, content: json.content.map((c, j) => (j === i ? smaller : c))}
      }
    }
  }
  if (json.text && json.text.length > 1) {
    const chars = Array.from(json.text)
    for (let i = 0; i < chars.length; i++) yield {...json, text: chars.filter((_, j) => j !== i).join('')}
  }
  if (json.marks && json.marks.length) {
    for (let i = 0; i < json.marks.length; i++) yield {...json, marks: json.marks.filter((_, j) => j !== i)}
  }
}

/** Greedily shrinks a failing document to a minimal one that still fails. */
export function shrink(json: Json, fails: (doc: Node) => boolean): Json {
  let current = json
  let progress = true
  while (progress) {
    progress = false
    for (const candidate of shrinks(current)) {
      const node = build(candidate)
      if (node && fails(node)) {
        current = candidate
        progress = true
        break
      }
    }
  }
  return current
}
