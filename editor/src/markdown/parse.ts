// Markdown -> document.
//
// Blank lines between top-level blocks beyond the one that separates them become empty
// paragraphs ("spacers"), and the serializer writes them back as blank lines, so vertical
// spacing a user typed survives a round trip. Inside containers (lists, quotes) markdown cannot
// hold extra blank lines, so there are no spacers there.

import type {Token} from 'markdown-it'
import {Mark, type Node, type NodeType, type Attrs} from 'prosemirror-model'
import {schema} from '../schema'
import {md} from './md'

/** YAML front matter at the very start of a note. Group 1 is its body. */
export const FRONTMATTER = /^---[ \t]*\n(?:([\s\S]*?)\n)??---[ \t]*(?:\n|$)/

export class MarkdownParseError extends Error {}

/** The env markdown-it fills in while parsing (link references, footnote labels). */
export type ParseEnv = Record<string, unknown>

export function normalizeNewlines(source: string): string {
  return source.replace(/\r\n?/g, '\n')
}

/** Parses a whole note. Throws MarkdownParseError if markdown-it emits a token it can't map. */
export function parseMarkdown(source: string): Node {
  let src = normalizeNewlines(source)
  const top: Node[] = []
  const fm = FRONTMATTER.exec(src)
  if (fm) {
    const body = fm[1] ?? ''
    top.push(schema.nodes.frontmatter.create(null, body ? schema.text(body) : null))
    src = src.slice(fm[0].length)
  }
  const tokens = md.parse(src, {})
  const blocks = convertBlocks(tokens)
  const lines = src.split('\n')
  const isBlank = (line: number) => /^[ \t]*$/.test(lines[line] ?? '')

  let prevEnd = -1
  for (const b of blocks) {
    let end = b.end
    while (end > b.start + 1 && isBlank(end - 1)) end--
    const spacers = prevEnd < 0 ? b.start : Math.max(0, b.start - prevEnd - 1)
    for (let i = 0; i < spacers; i++) top.push(schema.nodes.paragraph.create())
    top.push(b.node)
    prevEnd = end
  }
  // Blank lines at the end of a note are not kept: they are invisible, and an empty paragraph
  // left after a table or code block (to type below it) should not grow the file.
  if (!top.some((n) => n.type !== schema.nodes.frontmatter)) top.push(schema.nodes.paragraph.create())
  return schema.nodes.doc.create(null, top)
}

interface TopBlock {
  node: Node
  start: number
  end: number
}

interface Frame {
  type: NodeType
  attrs: Attrs | null
  content: Node[]
  map: [number, number] | null
}

function make(type: NodeType, attrs: Attrs | null, content: Node[]): Node {
  const node = type.createAndFill(attrs, content)
  if (!node) throw new MarkdownParseError(`Invalid content for ${type.name}`)
  return node
}

const ALIGN = /text-align:\s*(left|center|right)/

function attr(token: Token, name: string): string | null {
  const v = token.attrGet(name)
  return v === null ? null : String(v)
}

/** The plugin-defined metadata of a token (wikilink target, footnote label, task state). */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function meta(token: Token): any {
  return token.meta ?? {}
}

function cellAlign(token: Token): string | null {
  const m = ALIGN.exec(attr(token, 'style') || '')
  return m ? m[1] : null
}

/** Converts block tokens into top-level nodes, each with its source line range. */
function convertBlocks(tokens: Token[]): TopBlock[] {
  const nodes = schema.nodes
  const result: TopBlock[] = []
  const stack: Frame[] = []

  const add = (node: Node, map: [number, number] | null) => {
    if (stack.length) stack[stack.length - 1].content.push(node)
    else {
      if (!map) throw new MarkdownParseError(`Top-level ${node.type.name} without a source map`)
      result.push({node, start: map[0], end: map[1]})
    }
  }
  const open = (type: NodeType, token: Token, attrs: Attrs | null = null) => {
    stack.push({type, attrs, content: [], map: token.map as [number, number] | null})
  }
  const close = (type: NodeType) => {
    const frame = stack.pop()
    if (!frame || frame.type !== type) throw new MarkdownParseError(`Unbalanced ${type.name}`)
    add(make(frame.type, frame.attrs, frame.content), frame.map)
  }
  const inlineParent = () => stack[stack.length - 1]?.type

  for (const t of tokens) {
    switch (t.type) {
      case 'paragraph_open':
        open(nodes.paragraph, t)
        break
      case 'paragraph_close':
        close(nodes.paragraph)
        break
      case 'heading_open':
        open(nodes.heading, t, {level: Number(t.tag.slice(1))})
        break
      case 'heading_close':
        close(nodes.heading)
        break
      case 'blockquote_open':
        open(nodes.blockquote, t)
        break
      case 'blockquote_close':
        close(nodes.blockquote)
        break
      case 'bullet_list_open':
        open(nodes.bullet_list, t)
        break
      case 'bullet_list_close':
        close(nodes.bullet_list)
        break
      case 'ordered_list_open':
        open(nodes.ordered_list, t, {order: Number(attr(t, 'start') ?? 1)})
        break
      case 'ordered_list_close':
        close(nodes.ordered_list)
        break
      case 'list_item_open': {
        const checked = typeof meta(t).checked === 'boolean' ? meta(t).checked : null
        open(nodes.list_item, t, {checked})
        break
      }
      case 'list_item_close':
        close(nodes.list_item)
        break
      case 'footnote_reference_open':
        open(nodes.footnote_def, t, {label: meta(t).label})
        break
      case 'footnote_reference_close':
        close(nodes.footnote_def)
        break
      case 'table_open':
        open(nodes.table, t)
        break
      case 'table_close':
        close(nodes.table)
        break
      case 'tr_open':
        open(nodes.table_row, t)
        break
      case 'tr_close':
        close(nodes.table_row)
        break
      case 'th_open':
        open(nodes.table_header, t, {align: cellAlign(t)})
        break
      case 'th_close':
        close(nodes.table_header)
        break
      case 'td_open':
        open(nodes.table_cell, t, {align: cellAlign(t)})
        break
      case 'td_close':
        close(nodes.table_cell)
        break
      case 'thead_open':
      case 'thead_close':
      case 'tbody_open':
      case 'tbody_close':
        break
      case 'inline': {
        const parent = inlineParent()
        if (!parent) throw new MarkdownParseError('Inline content outside a block')
        stack[stack.length - 1].content.push(...convertInline(t.children || [], parent))
        break
      }
      case 'fence':
      case 'code_block': {
        const text = t.content.endsWith('\n') ? t.content.slice(0, -1) : t.content
        const language = t.type === 'fence' ? t.info.trim() : ''
        add(nodes.code_block.create({language}, text ? schema.text(text) : null), t.map as [number, number])
        break
      }
      case 'hr':
        add(nodes.horizontal_rule.create(), t.map as [number, number])
        break
      case 'html_block':
        add(nodes.html_block.create({raw: t.content.replace(/\n+$/, '')}), t.map as [number, number])
        break
      case 'link_def':
        add(nodes.link_def.create({raw: t.content}), t.map as [number, number])
        break
      default:
        throw new MarkdownParseError(`Unsupported block token ${t.type}`)
    }
  }
  if (stack.length) throw new MarkdownParseError('Unclosed block')
  return result
}

/** Plain text of inline tokens, as markdown-it renders an image's alt text. */
function inlineText(tokens: Token[]): string {
  let out = ''
  for (const t of tokens) {
    if (t.type === 'text' || t.type === 'code_inline') out += t.content
    else if (t.type === 'image') out += inlineText(t.children || [])
    else if (t.type === 'softbreak' || t.type === 'hardbreak') out += '\n'
    else if (t.type === 'wikilink') out += meta(t).alias ?? meta(t).target
  }
  return out
}

/**
 * Converts the children of an `inline` token into inline nodes for a parent of type `parent`.
 * Exported for the serializer, which verifies its output by parsing it back.
 */
export function convertInline(children: Token[], parent: NodeType): Node[] {
  const nodes = schema.nodes
  const marks = schema.marks
  const out: Node[] = []
  let active: readonly Mark[] = Mark.none
  const allowBreak = parent.contentMatch.matchType(nodes.hard_break) !== null
  const atom = (type: NodeType, attrs: Attrs) =>
    out.push(type.create(attrs, null, active.filter((m) => type.allowsMarkType(m.type))))

  for (const t of children) {
    switch (t.type) {
      case 'text':
        if (t.content) out.push(schema.text(t.content, active))
        break
      case 'softbreak':
      case 'hardbreak':
        if (allowBreak) out.push(nodes.hard_break.create())
        else out.push(schema.text(' ', active))
        break
      case 'strong_open':
        active = marks.strong.create().addToSet(active)
        break
      case 'strong_close':
        active = marks.strong.removeFromSet(active)
        break
      case 'em_open':
        active = marks.em.create().addToSet(active)
        break
      case 'em_close':
        active = marks.em.removeFromSet(active)
        break
      case 's_open':
        active = marks.strike.create().addToSet(active)
        break
      case 's_close':
        active = marks.strike.removeFromSet(active)
        break
      case 'mark_open':
        active = marks.highlight.create().addToSet(active)
        break
      case 'mark_close':
        active = marks.highlight.removeFromSet(active)
        break
      case 'link_open':
        active = marks.link
          .create({href: attr(t, 'href') ?? '', title: attr(t, 'title')})
          .addToSet(active)
        break
      case 'link_close':
        active = marks.link.removeFromSet(active)
        break
      case 'code_inline':
        if (t.content) out.push(schema.text(t.content, marks.code.create().addToSet(active)))
        break
      case 'image':
        atom(nodes.image, {
          src: attr(t, 'src') ?? '',
          alt: inlineText(t.children || []),
          title: attr(t, 'title'),
        })
        break
      case 'wikilink':
        atom(nodes.wikilink, {target: meta(t).target, alias: meta(t).alias, embed: meta(t).embed})
        break
      case 'footnote_ref':
        atom(nodes.footnote_ref, {label: meta(t).label})
        break
      case 'html_inline':
        atom(nodes.html_inline, {raw: t.content})
        break
      default:
        throw new MarkdownParseError(`Unsupported inline token ${t.type}`)
    }
  }
  return out
}
