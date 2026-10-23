// The document model of the editor.
//
// Every construct the markdown parser can produce has a node or mark here, so that opening a
// note never drops content: constructs the editor cannot edit structurally (raw HTML, link
// reference definitions) are kept as atoms that carry their source verbatim.
//
// Content groups:
//   block        - anything that can stand at block level
//   inline       - anything inline, including hard_break
//   inline_flat  - inline nodes that keep a textblock on one source line (no hard_break);
//                  headings and table cells use this, because markdown cannot put a line break
//                  inside either.

import {Schema, type NodeSpec, type MarkSpec, type DOMOutputSpec, type Node} from 'prosemirror-model'
import {tableNodes} from 'prosemirror-tables'

/** Marks an atom node may carry. Code and links cannot wrap atoms in markdown. */
const ATOM_MARKS = 'strong em strike highlight'

const cellAlign = {
  default: null as string | null,
  getFromDOM(dom: HTMLElement): string | null {
    const a = dom.style.textAlign || dom.getAttribute('align')
    return a === 'left' || a === 'center' || a === 'right' ? a : null
  },
  setDOMAttr(value: unknown, attrs: Record<string, unknown>) {
    if (value) attrs.style = `text-align: ${value}`
  },
}

const tables = tableNodes({
  tableGroup: 'block',
  cellContent: 'inline_flat*',
  cellAttributes: {align: cellAlign},
})

const nodes: Record<string, NodeSpec> = {
  doc: {content: 'frontmatter? block+'},

  /** YAML front matter ("properties"), only allowed as the first child of the doc. */
  frontmatter: {
    content: 'text*',
    marks: '',
    code: true,
    defining: true,
    isolating: true,
    parseDOM: [{tag: 'pre.frontmatter', preserveWhitespace: 'full'}],
    toDOM: (): DOMOutputSpec => ['pre', {class: 'frontmatter', spellcheck: 'false'}, ['code', 0]],
  },

  paragraph: {
    content: 'inline*',
    group: 'block',
    parseDOM: [{tag: 'p'}],
    toDOM: (): DOMOutputSpec => ['p', 0],
  },

  heading: {
    attrs: {level: {default: 1}},
    content: 'inline_flat*',
    group: 'block',
    defining: true,
    parseDOM: [1, 2, 3, 4, 5, 6].map((level) => ({tag: `h${level}`, attrs: {level}})),
    toDOM: (node): DOMOutputSpec => [`h${node.attrs.level}`, 0],
  },

  blockquote: {
    content: 'block+',
    group: 'block',
    defining: true,
    parseDOM: [{tag: 'blockquote'}],
    toDOM: (): DOMOutputSpec => ['blockquote', 0],
  },

  horizontal_rule: {
    group: 'block',
    parseDOM: [{tag: 'hr'}],
    toDOM: (): DOMOutputSpec => ['hr'],
  },

  /** `language` holds the whole info string of the fence, verbatim. */
  code_block: {
    attrs: {language: {default: ''}},
    content: 'text*',
    marks: '',
    group: 'block',
    code: true,
    defining: true,
    parseDOM: [
      {
        tag: 'pre',
        preserveWhitespace: 'full',
        getAttrs: (dom: HTMLElement) => {
          const code = dom.querySelector('code')
          const cls = (code?.className || dom.className || '').match(/(?:^|\s)language-(\S+)/)
          return {language: dom.getAttribute('data-language') || (cls ? cls[1] : '')}
        },
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'pre',
      {'data-language': node.attrs.language || null, spellcheck: 'false'},
      ['code', 0],
    ],
  },

  bullet_list: {
    content: 'list_item+',
    group: 'block',
    parseDOM: [{tag: 'ul'}],
    toDOM: (): DOMOutputSpec => ['ul', 0],
  },

  ordered_list: {
    attrs: {order: {default: 1}},
    content: 'list_item+',
    group: 'block',
    parseDOM: [
      {
        tag: 'ol',
        getAttrs: (dom: HTMLElement) => ({
          order: dom.hasAttribute('start') ? Number(dom.getAttribute('start')) || 1 : 1,
        }),
      },
    ],
    toDOM: (node): DOMOutputSpec =>
      node.attrs.order === 1 ? ['ol', 0] : ['ol', {start: node.attrs.order}, 0],
  },

  /**
   * `checked` is null for an ordinary item, and true/false for a task-list item. A task item's
   * first child must be a paragraph (that is where the `[ ]` marker lives in markdown).
   */
  list_item: {
    attrs: {checked: {default: null}},
    content: 'block+',
    defining: true,
    parseDOM: [
      {
        tag: 'li',
        getAttrs: (dom: HTMLElement) => {
          const box = dom.querySelector(':scope > input[type=checkbox]') as HTMLInputElement | null
          if (dom.hasAttribute('data-checked'))
            return {checked: dom.getAttribute('data-checked') === 'true'}
          return {checked: box ? box.checked : null}
        },
      },
    ],
    toDOM: (node): DOMOutputSpec =>
      node.attrs.checked === null
        ? ['li', 0]
        : ['li', {class: 'task', 'data-checked': String(node.attrs.checked)}, 0],
  },

  /** A footnote definition: `[^label]: content`. */
  footnote_def: {
    attrs: {label: {default: '1'}},
    content: 'block+',
    group: 'block',
    defining: true,
    parseDOM: [
      {
        tag: 'div.footnote-def',
        getAttrs: (dom: HTMLElement) => ({label: dom.getAttribute('data-label') || '1'}),
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'div',
      {class: 'footnote-def', 'data-label': node.attrs.label},
      ['div', {class: 'footnote-content'}, 0],
    ],
  },

  /** A link reference definition (`[label]: url "title"`), kept verbatim. */
  link_def: {
    attrs: {raw: {default: ''}},
    group: 'block',
    atom: true,
    selectable: true,
    parseDOM: [
      {tag: 'div.link-def', getAttrs: (dom: HTMLElement) => ({raw: dom.getAttribute('data-raw') || ''})},
    ],
    toDOM: (node): DOMOutputSpec => [
      'div',
      {class: 'link-def', 'data-raw': node.attrs.raw, contenteditable: 'false'},
      node.attrs.raw,
    ],
  },

  /** A block of raw HTML from an imported note, kept verbatim and shown as source. */
  html_block: {
    attrs: {raw: {default: ''}},
    group: 'block',
    atom: true,
    selectable: true,
    parseDOM: [
      {
        tag: 'div.html-block',
        getAttrs: (dom: HTMLElement) => ({raw: dom.getAttribute('data-raw') || ''}),
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'div',
      {class: 'html-block', 'data-raw': node.attrs.raw, contenteditable: 'false'},
      node.attrs.raw,
    ],
  },

  ...tables,

  text: {group: 'inline inline_flat'},

  hard_break: {
    inline: true,
    group: 'inline',
    selectable: false,
    marks: '',
    parseDOM: [{tag: 'br'}],
    toDOM: (): DOMOutputSpec => ['br'],
  },

  image: {
    inline: true,
    attrs: {src: {default: ''}, alt: {default: ''}, title: {default: null}},
    group: 'inline inline_flat',
    draggable: true,
    marks: ATOM_MARKS + ' link',
    parseDOM: [
      {
        tag: 'img[src]',
        getAttrs: (dom: HTMLElement) => ({
          src: dom.getAttribute('src') || '',
          alt: dom.getAttribute('alt') || '',
          title: dom.getAttribute('title'),
        }),
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'img',
      {src: node.attrs.src, alt: node.attrs.alt, title: node.attrs.title, loading: 'lazy'},
    ],
  },

  /**
   * An internal link to another note: `[[target]]`, `[[target|alias]]`, or, with `embed`,
   * `![[target]]`. `target` may carry a `#heading` or `#^block` suffix, as in Obsidian.
   */
  wikilink: {
    inline: true,
    atom: true,
    attrs: {target: {default: ''}, alias: {default: null}, embed: {default: false}},
    group: 'inline inline_flat',
    marks: ATOM_MARKS,
    selectable: true,
    parseDOM: [
      {
        tag: 'span.wikilink',
        getAttrs: (dom: HTMLElement) => ({
          target: dom.getAttribute('data-target') || '',
          alias: dom.getAttribute('data-alias'),
          embed: dom.getAttribute('data-embed') === 'true',
        }),
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'span',
      {
        class: node.attrs.embed ? 'wikilink embed' : 'wikilink',
        'data-target': node.attrs.target,
        'data-alias': node.attrs.alias,
        'data-embed': node.attrs.embed ? 'true' : null,
      },
      wikilinkLabel(node),
    ],
  },

  footnote_ref: {
    inline: true,
    atom: true,
    attrs: {label: {default: '1'}},
    group: 'inline inline_flat',
    marks: ATOM_MARKS,
    selectable: true,
    parseDOM: [
      {
        tag: 'sup.footnote-ref',
        getAttrs: (dom: HTMLElement) => ({label: dom.getAttribute('data-label') || '1'}),
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'sup',
      {class: 'footnote-ref', 'data-label': node.attrs.label},
      node.attrs.label,
    ],
  },

  /** An inline HTML tag or comment from an imported note, kept verbatim and shown as source. */
  html_inline: {
    inline: true,
    atom: true,
    attrs: {raw: {default: ''}},
    group: 'inline inline_flat',
    marks: ATOM_MARKS,
    selectable: true,
    parseDOM: [
      {
        tag: 'span.html-inline',
        getAttrs: (dom: HTMLElement) => ({raw: dom.getAttribute('data-raw') || ''}),
      },
    ],
    toDOM: (node): DOMOutputSpec => [
      'span',
      {class: 'html-inline', 'data-raw': node.attrs.raw},
      node.attrs.raw,
    ],
  },
}

const marks: Record<string, MarkSpec> = {
  link: {
    attrs: {href: {default: ''}, title: {default: null}},
    inclusive: false,
    parseDOM: [
      {
        tag: 'a[href]',
        getAttrs: (dom: HTMLElement) => ({
          href: dom.getAttribute('href') || '',
          title: dom.getAttribute('title'),
        }),
      },
    ],
    toDOM: (mark): DOMOutputSpec => [
      'a',
      {href: mark.attrs.href, title: mark.attrs.title, rel: 'noopener noreferrer nofollow'},
      0,
    ],
  },
  em: {
    parseDOM: [
      {tag: 'i'},
      {tag: 'em'},
      {style: 'font-style=italic'},
      {style: 'font-style=normal', clearMark: (m) => m.type.name === 'em'},
    ],
    toDOM: (): DOMOutputSpec => ['em', 0],
  },
  strong: {
    parseDOM: [
      {tag: 'strong'},
      // Google Docs wraps pasted content in <b style="font-weight: normal">.
      {tag: 'b', getAttrs: (dom: HTMLElement) => dom.style.fontWeight !== 'normal' && null},
      {style: 'font-weight=400', clearMark: (m) => m.type.name === 'strong'},
      {
        style: 'font-weight',
        getAttrs: (value: string) => /^(bold(er)?|[5-9]\d{2,})$/.test(value) && null,
      },
    ],
    toDOM: (): DOMOutputSpec => ['strong', 0],
  },
  strike: {
    parseDOM: [{tag: 's'}, {tag: 'del'}, {tag: 'strike'}, {style: 'text-decoration=line-through'}],
    toDOM: (): DOMOutputSpec => ['s', 0],
  },
  highlight: {
    parseDOM: [{tag: 'mark'}],
    toDOM: (): DOMOutputSpec => ['mark', 0],
  },
  code: {
    parseDOM: [{tag: 'code'}],
    toDOM: (): DOMOutputSpec => ['code', 0],
  },
}

export const schema = new Schema({nodes, marks})

/** The text a wikilink shows: its alias, else its target. */
export function wikilinkLabel(node: Node): string {
  return node.attrs.alias || node.attrs.target
}

/** Marks that markdown expresses with delimiter runs (`**`, `*`, `~~`, `==`). */
export const DELIMITER_MARKS = ['strong', 'em', 'strike', 'highlight'] as const
