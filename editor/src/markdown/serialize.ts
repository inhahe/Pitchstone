// Document -> markdown.
//
// Contract: parseMarkdown(serializeMarkdown(doc).markdown) equals serializeMarkdown(doc).doc,
// which is `doc` after normalizeDoc() and, rarely, after shrinking a bold/italic range off a
// punctuation character that markdown cannot place a delimiter next to. `exact` reports
// whether the contract held for this call; tests hold it for millions of generated documents.
//
// Escaping is minimal-first: each textblock is written with as few backslashes as the
// heuristics allow, parsed back with the real parser, and rewritten with full escaping only if
// it does not come back identical. Characters a user typed are therefore always literal, and
// notes stay readable in other markdown apps.

import {Fragment, Mark, type Node, type NodeType} from 'prosemirror-model'
import {schema, DELIMITER_MARKS} from '../schema'
import {md, isWhiteSpace, isMdAsciiPunct} from './md'
import {convertInline, parseMarkdown, MarkdownParseError, type ParseEnv} from './parse'

export interface SerializeResult {
  markdown: string
  /** The document `markdown` represents: the input after normalization. */
  doc: Node
  /** Whether parsing `markdown` back gives exactly `doc`. False means a serializer bug. */
  exact: boolean
}

const nodes = schema.nodes
const marks = schema.marks

// ---------------------------------------------------------------------------------------------
// Normalization: the lossless-in-appearance changes markdown forces on a document.
// ---------------------------------------------------------------------------------------------

interface NormContext {
  footnoteLabels: Set<string>
  /** The labels, as one string: what the normalization of a paragraph depends on besides it. */
  labels: string
}

const FOOTNOTE_LABEL = /^[^\s\]]+$/

/** Returns `doc` with everything markdown cannot represent rewritten to what it will read back as. */
export function normalizeDoc(doc: Node): Node {
  const footnoteLabels = new Set<string>()
  doc.descendants((n) => {
    if (n.type === nodes.footnote_def) footnoteLabels.add(sanitizeFootnoteLabel(n.attrs.label))
    return !n.isTextblock
  })
  // Labels hold no whitespace (sanitizeFootnoteLabel), so a space separates them.
  return normalizeBlock(doc, {footnoteLabels, labels: [...footnoteLabels].sort().join(' ')})
}

function sanitizeFootnoteLabel(label: string): string {
  return String(label).replace(/[\s\]]/g, '') || '1'
}

function cleanText(text: string): string {
  return text.replace(/\0/g, '�').replace(/\r\n?/g, '\n')
}

function isEmptyParagraph(n: Node): boolean {
  return n.type === nodes.paragraph && n.content.size === 0
}

function normalizeBlock(node: Node, ctx: NormContext): Node {
  const t = node.type
  if (t === nodes.code_block || t === nodes.frontmatter) {
    const text = cleanText(node.textContent)
    const attrs =
      t === nodes.code_block
        ? {language: String(node.attrs.language ?? '').replace(/[\r\n]+/g, ' ').trim()}
        : null
    const fresh = t.create(attrs, text ? schema.text(text) : null)
    return fresh.eq(node) ? node : fresh
  }
  if (t === nodes.paragraph || t === nodes.heading || t === nodes.table_cell || t === nodes.table_header) {
    const content = normalizeInline(node, ctx)
    let attrs = node.attrs
    if (t === nodes.heading) attrs = {level: Math.min(6, Math.max(1, Math.floor(node.attrs.level) || 1))}
    const fresh = t.create(attrs, content)
    return fresh.eq(node) ? node : fresh
  }
  if (t === nodes.table) return normalizeTable(node, ctx)
  if (node.isLeaf) return node

  let children: Node[] = []
  // A list item's paragraphs keep their empty lines as line breaks: a blank line there would
  // make the whole list loose.
  const lines = t !== nodes.list_item
  node.forEach((child) => {
    if (lines && child.type === nodes.paragraph) children.push(...paragraphsOfLines(child, ctx, t === nodes.doc))
    else children.push(normalizeBlock(child, ctx))
  })
  let attrs = node.attrs

  if (t === nodes.doc) {
    // Trailing empty paragraphs are not written (see parseMarkdown); at least one block stays.
    while (children.length > 1 && isEmptyParagraph(children[children.length - 1])) children.pop()
    const body = children.filter((c) => c.type !== nodes.frontmatter)
    if (body.every(isEmptyParagraph)) {
      children = children.filter((c) => c.type === nodes.frontmatter)
      children.push(nodes.paragraph.create())
    }
  } else if (t === nodes.blockquote || t === nodes.list_item || t === nodes.footnote_def) {
    const nonEmpty = children.filter((c) => !isEmptyParagraph(c))
    // The checkbox is written on the item's first paragraph, which a task keeps even when it
    // has no text (`- [ ]` over a nested list).
    const task = t === nodes.list_item && typeof node.attrs.checked === 'boolean'
    if (task && nonEmpty.length && nonEmpty[0].type !== nodes.paragraph) nonEmpty.unshift(nodes.paragraph.create())
    children = nonEmpty.length ? nonEmpty : [nodes.paragraph.create()]
  }
  if (t === nodes.list_item) {
    const checked = node.attrs.checked
    attrs = {checked: children[0].type === nodes.paragraph && typeof checked === 'boolean' ? checked : null}
  } else if (t === nodes.ordered_list) {
    const order = Math.floor(Number(node.attrs.order))
    attrs = {order: Number.isFinite(order) ? Math.min(999999999, Math.max(0, order)) : 1}
  } else if (t === nodes.footnote_def) {
    attrs = {label: sanitizeFootnoteLabel(node.attrs.label)}
  }
  const fresh = t.create(attrs, children, node.marks)
  return fresh.eq(node) ? node : fresh
}

export const isBreak = (node: Node | null | undefined): boolean => node?.type === nodes.hard_break

/** How markdown reads a run of line breaks in a paragraph (see readRun). */
export interface RunReading {
  /** Whether the paragraph ends there: a blank line in the file. */
  split: boolean
  /** How many of the breaks, from the run's start, go; the rest stay, `\` lines at the start of the lines after. */
  cut: number
  /** Empty paragraphs (spacers) after the paragraph break. */
  empty: number
}

/**
 * How markdown reads a run of `k` line breaks in a paragraph (k - 1 empty lines between lines):
 * an empty line is a blank line, which ends the paragraph. Between top-level blocks (`spacers`),
 * each further empty line is a spacer, and so is each one a paragraph starts with (`leading`).
 * Inside a quote or a footnote markdown holds no extra blank lines, so those stay line breaks
 * (`\` lines). Empty lines at a paragraph's end (`trailing`) are not written: that is where the
 * cursor is about to type. The editor splits lines by the same reading (lines.ts).
 */
export function readRun(k: number, leading: boolean, trailing: boolean, spacers: boolean): RunReading {
  if (trailing) return {split: false, cut: k, empty: 0}
  if (leading) return spacers ? {split: true, cut: k, empty: k - 1} : {split: false, cut: 0, empty: 0}
  if (k === 1) return {split: false, cut: 0, empty: 0}
  return spacers ? {split: true, cut: k, empty: k - 2} : {split: true, cut: 2, empty: 0}
}

/** Split paragraphs from paragraphsOfLines, kept so that their renderings are cached across saves. */
const linesCache = new WeakMap<Node, {key: string; out: Node[]}>()

/**
 * A paragraph whose empty lines are written as what they are in markdown (readRun): blank lines
 * that end it, spacers between top-level blocks (`spacers`), and `\` lines where markdown can
 * hold no more. normalizeInline has dropped the line breaks at its end.
 */
function paragraphsOfLines(para: Node, ctx: NormContext, spacers: boolean): Node[] {
  const content = normalizeInline(para, ctx)
  if (!content.some((n, i) => isBreak(n) && (i === 0 || isBreak(content[i - 1])))) {
    const fresh = nodes.paragraph.create(para.attrs, content)
    return [fresh.eq(para) ? para : fresh]
  }
  const key = `${spacers} ${ctx.labels}`
  const cached = linesCache.get(para)
  if (cached?.key === key) return cached.out
  const out: Node[] = []
  // The rest of normalizeInline holds within a line; but a line that now starts a paragraph is
  // checked as one.
  const close = (line: Node[]) => {
    demoteLineStartHtml(line)
    out.push(nodes.paragraph.create(para.attrs, line))
  }
  let line: Node[] = []
  let run: Node[] = []
  for (const n of content) {
    if (isBreak(n)) {
      run.push(n)
      continue
    }
    if (run.length) {
      const reading = readRun(run.length, !line.length, false, spacers)
      if (!reading.split) {
        line.push(...run)
      } else {
        if (line.length) close(line)
        else out.push(nodes.paragraph.create())
        for (let i = 0; i < reading.empty; i++) out.push(nodes.paragraph.create())
        line = run.slice(reading.cut)
      }
      run = []
    }
    line.push(n)
  }
  close(line)
  const result = out.length === 1 && out[0].eq(para) ? [para] : out
  linesCache.set(para, {key, out: result})
  return result
}

/** First row is the header, every row is as wide as the widest, alignment is per column. */
function normalizeTable(table: Node, ctx: NormContext): Node {
  const rows: Node[][] = []
  table.forEach((row) => {
    const cells: Node[] = []
    row.forEach((cell) => cells.push(normalizeBlock(cell, ctx)))
    rows.push(cells)
  })
  const width = Math.max(1, ...rows.map((r) => r.length))
  const aligns: (string | null)[] = []
  for (let c = 0; c < width; c++) aligns.push(rows[0]?.[c]?.attrs.align ?? null)
  const outRows = rows.map((cells, r) => {
    const type = r === 0 ? nodes.table_header : nodes.table_cell
    const out: Node[] = []
    for (let c = 0; c < width; c++) {
      const content = cells[c] ? cells[c].content : Fragment.empty
      out.push(type.create({colspan: 1, rowspan: 1, colwidth: null, align: aligns[c]}, content))
    }
    return nodes.table_row.create(null, out)
  })
  if (!outRows.length) outRows.push(nodes.table_row.create(null, [nodes.table_header.create()]))
  const fresh = nodes.table.create(table.attrs, outRows)
  return fresh.eq(table) ? table : fresh
}

/** One code point of text, or one inline atom, with its marks. */
interface Unit {
  ch: string | null
  node: Node | null
  marks: readonly Mark[]
}

function toUnits(content: Iterable<Node>): Unit[] {
  const units: Unit[] = []
  for (const n of content) {
    if (n.isText) for (const ch of n.text!) units.push({ch, node: null, marks: n.marks})
    else units.push({ch: null, node: n, marks: n.marks})
  }
  return units
}

function fromUnits(units: Unit[]): Node[] {
  const out: Node[] = []
  let text = ''
  let textMarks: readonly Mark[] | null = null
  const flush = () => {
    if (text) out.push(schema.text(text, textMarks!))
    text = ''
    textMarks = null
  }
  for (const u of units) {
    if (u.ch !== null) {
      if (textMarks && !Mark.sameSet(textMarks, u.marks)) flush()
      textMarks = u.marks
      text += u.ch
    } else {
      flush()
      out.push(u.node!.mark(u.node!.type.allowedMarks(u.marks as Mark[])))
    }
  }
  flush()
  return out
}

function isWs(ch: string): boolean {
  return isWhiteSpace(ch.codePointAt(0)!)
}

/** A link or image title; markdown reads an empty title back as no title. */
function normalizeTitle(title: unknown): string | null {
  if (title == null) return null
  const t = cleanText(String(title)).replace(/\n/g, ' ')
  return t === '' ? null : t
}

/**
 * The href markdown-it will produce for `href`, or null if it rejects the URL (javascript:
 * and the like), in which case the link syntax reads back as literal text.
 */
function normalizeHref(href: unknown): string | null {
  const s = String(href ?? '')
  const normalized = md.normalizeLink(s)
  return md.validateLink(normalized) ? normalized : null
}

function normalizeInline(block: Node, ctx: NormContext): Node[] {
  const allowBreak = block.type.contentMatch.matchType(nodes.hard_break) !== null
  const expanded: Node[] = []
  block.forEach((n) => {
    let ms = n.marks.map((m) => {
      if (m.type !== marks.link) return m
      const href = normalizeHref(m.attrs.href)
      if (href === null) return null
      const title = normalizeTitle(m.attrs.title)
      return href === m.attrs.href && title === m.attrs.title ? m : marks.link.create({href, title})
    }).filter((m): m is Mark => m !== null)
    if (n.isText) {
      const text = n.text!.replace(/\0/g, '�')
      if (!marks.code.isInSet(ms)) {
        // A line feed inside text (from an entity like &#10;) is written back as an entity.
        expanded.push(schema.text(text, ms))
        return
      }
      // Code spans read line breaks back as spaces, so a break inside code becomes a real one.
      text.split(/\r\n?|\n/).forEach((part, i) => {
        if (i > 0) expanded.push(allowBreak ? nodes.hard_break.create() : schema.text(' ', ms))
        if (part) expanded.push(schema.text(part, ms))
      })
      return
    }
    if (n.type === nodes.hard_break) {
      expanded.push(allowBreak ? nodes.hard_break.create() : schema.text(' '))
      return
    }
    ms = ms.filter((m) => n.type.allowsMarkType(m.type))
    if (n.type === nodes.html_inline && !allowBreak && String(n.attrs.raw).includes('\n')) {
      // Headings and table cells are one line; a tag spanning lines can only stay as text.
      expanded.push(schema.text(String(n.attrs.raw), ms))
      return
    }
    if (n.type === nodes.wikilink) {
      let target = String(n.attrs.target ?? '').replace(/[[\]\r\n|]/g, '')
      const alias = n.attrs.alias == null ? null : String(n.attrs.alias).replace(/[[\]\r\n]/g, '')
      if (alias !== null) target = target.replace(/\\+$/, '')
      if (!target.trim()) {
        const label = alias || String(n.attrs.target ?? '')
        if (label) expanded.push(schema.text(label, ms))
        return
      }
      expanded.push(nodes.wikilink.create({target, alias, embed: !!n.attrs.embed}, null, ms))
      return
    }
    if (n.type === nodes.footnote_ref) {
      const label = String(n.attrs.label ?? '')
      if (!FOOTNOTE_LABEL.test(label) || !ctx.footnoteLabels.has(label)) {
        expanded.push(schema.text(`[^${label}]`, ms))
        return
      }
      expanded.push(n.mark(ms))
      return
    }
    if (n.type === nodes.image) {
      const src = normalizeHref(n.attrs.src)
      const alt = cleanText(String(n.attrs.alt ?? '')).replace(/\n/g, ' ')
      const title = normalizeTitle(n.attrs.title)
      if (src === null) {
        if (alt) expanded.push(schema.text(alt, ms))
        return
      }
      expanded.push(nodes.image.create({src, alt, title}, null, ms))
      return
    }
    expanded.push(n.mark(ms))
  })
  while (expanded.length && expanded[expanded.length - 1].type === nodes.hard_break) expanded.pop()
  if (allowBreak) demoteLineStartHtml(expanded)

  // Delimiter marks cannot start or end with whitespace in markdown: move it outside.
  const units = toUnits(expanded)
  for (const name of DELIMITER_MARKS) {
    const type = schema.marks[name]
    forEachRun(units, type, (from, to) => {
      const trimmable = (u: Unit) => u.ch !== null && isWs(u.ch) && !marks.code.isInSet(u.marks)
      while (from < to && trimmable(units[from])) {
        units[from] = {...units[from], marks: type.removeFromSet(units[from].marks)}
        from++
      }
      while (to > from && trimmable(units[to - 1])) {
        units[to - 1] = {...units[to - 1], marks: type.removeFromSet(units[to - 1].marks)}
        to--
      }
    })
  }
  return fromUnits(units)
}

/**
 * Inline HTML that starts a paragraph line would be read back as the start of an HTML block,
 * which swallows the rest of the paragraph. Only editing can put it there (the parser never
 * produces it), and it is shown as source anyway, so it becomes literal text.
 */
function demoteLineStartHtml(items: Node[]): void {
  let lineStart = 0
  for (let i = 0; i <= items.length; i++) {
    if (i < items.length && items[i].type !== nodes.hard_break) continue
    const first = items[lineStart]
    if (first && first.type === nodes.html_inline) {
      let line = ''
      for (let j = lineStart; j < i; j++) {
        const n = items[j]
        line += n.isText ? n.text : n.type === nodes.html_inline ? n.attrs.raw : 'x'
      }
      const probe = lineStart === 0 ? line : 'x\n' + line
      if (md.parse(probe, {}).some((t) => t.type === 'html_block')) {
        items[lineStart] = schema.text(first.attrs.raw, first.marks)
      }
    }
    lineStart = i + 1
  }
}

/** Calls `f(from, to)` for each maximal run of units carrying a mark of `type`. */
function forEachRun(units: Unit[], type: Mark['type'], f: (from: number, to: number) => void) {
  let i = 0
  while (i < units.length) {
    if (!type.isInSet(units[i].marks)) {
      i++
      continue
    }
    let j = i
    while (j < units.length && type.isInSet(units[j].marks)) j++
    f(i, j)
    i = j
  }
}

// ---------------------------------------------------------------------------------------------
// Inline rendering
// ---------------------------------------------------------------------------------------------

type Level = 0 | 1

interface InlineOptions {
  level: Level
  /** The delimiter for emphasis: `*` normally, `_` as a fallback. */
  emChar: '*' | '_'
  inTable: boolean
}

const ENTITY_LIKE = /^&(?:#[0-9]{1,7};|#[xX][0-9a-fA-F]{1,6};|[A-Za-z][A-Za-z0-9]{1,31};)/
const ALNUM = /[\p{L}\p{N}]/u

interface BlockFlags {
  star: boolean
  underscore: boolean
  tilde: boolean
  equals: boolean
  backtick: boolean
  bracket: boolean
}

function blockFlags(items: readonly Node[], level: Level): BlockFlags {
  if (level === 1) return {star: true, underscore: true, tilde: true, equals: true, backtick: true, bracket: true}
  let text = ''
  const has = new Set<string>()
  for (const n of items) {
    if (n.isText) text += n.text
    else text += '￼'
    for (const m of n.marks) has.add(m.type.name)
    if (!n.isText && n.type !== nodes.hard_break) has.add('atom')
  }
  const count = (ch: string) => text.split(ch).length - 1
  const open = text.indexOf('[')
  return {
    star: has.has('strong') || has.has('em') || count('*') >= 2,
    underscore: has.has('strong') || has.has('em') || count('_') >= 2,
    tilde: has.has('strike') || text.includes('~~'),
    equals: has.has('highlight') || text.includes('=='),
    backtick: has.has('code') || count('`') >= 2,
    bracket: has.has('link') || has.has('atom') || (open >= 0 && text.indexOf(']', open) > open),
  }
}

/** Escapes one text node's content. `prev`/`next` are the neighbouring text characters, if any. */
function escapeText(text: string, flags: BlockFlags, opts: InlineOptions, inLink: boolean): string {
  const chars = Array.from(text)
  let out = ''
  for (let i = 0; i < chars.length; i++) {
    const ch = chars[i]
    const prev = i > 0 ? chars[i - 1] : null
    const next = i + 1 < chars.length ? chars[i + 1] : null
    let esc = false
    switch (ch) {
      case '\\':
        esc = opts.level === 1 || next === null || isMdAsciiPunct(next.charCodeAt(0))
        break
      // Between spaces a delimiter can neither open nor close, but the neighbours may be
      // written as entities (which are punctuation), so full escaping does not rely on it.
      case '*':
        esc = flags.star && (opts.level === 1 || !(prev !== null && next !== null && isWs(prev) && isWs(next)))
        break
      case '_':
        esc =
          flags.underscore &&
          !(prev !== null && next !== null && ALNUM.test(prev) && ALNUM.test(next)) &&
          (opts.level === 1 || !(prev !== null && next !== null && isWs(prev) && isWs(next)))
        break
      case '~':
        esc = flags.tilde && (opts.level === 1 || prev === '~' || next === '~' || prev === null || next === null)
        break
      case '=':
        esc = flags.equals && (prev === '=' || next === '=' || prev === null || next === null)
        break
      case '`':
        esc = flags.backtick
        break
      case '[':
        esc = flags.bracket
        break
      case ']':
        esc = opts.level === 1 || inLink
        break
      case '<':
        esc = opts.level === 1 || next === null || /[A-Za-z/!?]/.test(next)
        break
      case '&':
        esc = ENTITY_LIKE.test(chars.slice(i, i + 40).join('')) || (opts.level === 1 && next === null)
        break
      case '\n':
      case '\r':
        out += entity(ch)
        continue
    }
    out += esc ? '\\' + ch : ch
  }
  return out
}

function codeSpan(text: string): string {
  let longest = 0
  for (const m of text.matchAll(/`+/g)) longest = Math.max(longest, m[0].length)
  const fence = '`'.repeat(longest + 1)
  const allSpaces = /^ +$/.test(text)
  const pad =
    text.startsWith('`') || text.endsWith('`') || (!allSpaces && text.startsWith(' ') && text.endsWith(' '))
  return pad ? `${fence} ${text} ${fence}` : fence + text + fence
}

function balancedParens(s: string): boolean {
  let depth = 0
  for (const ch of s) {
    if (ch === '(') depth++
    else if (ch === ')' && --depth < 0) return false
  }
  return depth === 0
}

/** Escapes `&` where it would start an entity reference. */
function escapeEntities(s: string): string {
  return s.replace(/&(?=#[0-9]{1,7};|#[xX][0-9a-fA-F]{1,6};|[A-Za-z][A-Za-z0-9]{1,31};)/g, '\\&')
}

/** A link destination plus optional title, as written between the parentheses. */
function destination(href: string, title: string | null): string {
  let dest: string
  if (href === '') dest = title == null ? '' : '<>'
  else if (/^[^\s<>\\]*$/.test(href) && balancedParens(href)) dest = escapeEntities(href)
  else dest = '<' + escapeEntities(href.replace(/[\\<>]/g, '\\$&')) + '>'
  if (title != null) dest += (dest ? ' ' : '') + '"' + title.replace(/[\\"&]/g, '\\$&') + '"'
  return dest
}

/** The markdown of an inline atom: an image, a wikilink, a footnote reference or inline HTML. */
export function atomMarkdown(node: Node): string {
  const a = node.attrs
  if (node.type === nodes.image) return '![' + escapeAlt(a.alt) + '](' + destination(a.src, a.title) + ')'
  if (node.type === nodes.wikilink) return (a.embed ? '!' : '') + '[[' + a.target + (a.alias != null ? '|' + a.alias : '') + ']]'
  if (node.type === nodes.footnote_ref) return '[^' + a.label + ']'
  if (node.type === nodes.html_inline) return String(a.raw)
  throw new Error(`Not an inline atom: ${node.type.name}`)
}

/** Alt text of an image: everything is escaped, since formatting would be flattened anyway. */
function escapeAlt(alt: string): string {
  return alt.replace(/[\\`*_~=[\]<&!]/g, '\\$&')
}

const MARK_OPEN: Record<string, (m: Mark, o: InlineOptions) => string> = {
  strong: () => '**',
  em: (_m, o) => o.emChar,
  strike: () => '~~',
  highlight: () => '==',
  link: () => '[',
}
const MARK_CLOSE: Record<string, (m: Mark, o: InlineOptions) => string> = {
  strong: () => '**',
  em: (_m, o) => o.emChar,
  strike: () => '~~',
  highlight: () => '==',
  link: (m) => '](' + destination(m.attrs.href, m.attrs.title) + ')',
}

/** Renders inline content to markdown. Hard breaks appear as "\n"; lines are fixed up after. */
function renderInline(items: readonly Node[], opts: InlineOptions): string {
  const flags = blockFlags(items, opts.level)
  let out = ''
  let active: Mark[] = []
  let lineHasContent = false

  const extent = (mark: Mark, from: number) => {
    let i = from
    while (i < items.length && mark.isInSet(items[i].marks)) i++
    return i - from
  }
  const closeTo = (keep: number) => {
    while (active.length > keep) {
      const m = active.pop()!
      out += MARK_CLOSE[m.type.name](m, opts)
    }
  }
  /** Before syntax starting with `[`: a literal `!` right before it would make an image. */
  const guardBang = () => {
    if (!out.endsWith('!')) return
    let slashes = 0
    while (out[out.length - 2 - slashes] === '\\') slashes++
    if (slashes % 2 === 0) out = out.slice(0, -1) + '\\!'
  }

  items.forEach((item, index) => {
    if (item.type === nodes.hard_break) {
      closeTo(0)
      out += lineHasContent ? '\n' : '\\\n'
      lineHasContent = false
      return
    }
    const wanted = item.marks.filter((m) => m.type !== marks.code)
    let keep = 0
    while (keep < active.length && active[keep].isInSet(wanted)) keep++
    // Marks overlap: when a mark that must open now outlasts open marks, close those and reopen
    // them inside it, so the mark that ends sooner is the one split in two.
    const longestNew = Math.max(0, ...wanted.filter((m) => !m.isInSet(active.slice(0, keep))).map((m) => extent(m, index)))
    while (keep > 0 && extent(active[keep - 1], index) < longestNew) keep--
    closeTo(keep)
    const opening = wanted
      .filter((m) => !m.isInSet(active))
      .sort((a, b) => extent(b, index) - extent(a, index) || (a.type === marks.link ? -1 : b.type === marks.link ? 1 : 0))
    for (const m of opening) {
      if (m.type === marks.link) guardBang()
      out += MARK_OPEN[m.type.name](m, opts)
      active.push(m)
    }
    const inLink = active.some((m) => m.type === marks.link)
    if (item.isText) {
      if (marks.code.isInSet(item.marks)) {
        out += codeSpan(item.text!)
      } else {
        let text = escapeText(item.text!, flags, opts, inLink)
        // `[^1](` would read as a link whose text is "^1".
        if (items[index - 1]?.type === nodes.footnote_ref && text.startsWith('(')) text = '\\' + text
        out += text
      }
    } else if (item.type === nodes.image) {
      out += atomMarkdown(item)
    } else if (item.type === nodes.wikilink) {
      if (!item.attrs.embed) guardBang()
      out += atomMarkdown(item)
    } else if (item.type === nodes.footnote_ref) {
      guardBang()
      out += atomMarkdown(item)
    } else if (item.type === nodes.html_inline) {
      // A tag can span lines; RAW_NEWLINE keeps those apart from the paragraph's own breaks.
      out += atomMarkdown(item).replace(/\n/g, RAW_NEWLINE)
    } else {
      throw new Error(`Cannot serialize inline node ${item.type.name}`)
    }
    lineHasContent = true
  })
  closeTo(0)
  if (opts.inTable) out = out.replace(/\|/g, '\\|')
  return out
}

function entity(ch: string): string {
  return `&#${ch.codePointAt(0)};`
}

const THEMATIC = /^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$/
const SETEXT = /^ {0,3}(?:=+|-+)[ \t]*$/
const ATX = /^ {0,3}#{1,6}(?:[ \t]|$)/
const QUOTE = /^ {0,3}>/
const BULLET = /^ {0,3}[-+*](?:[ \t]|$)/
const ORDERED = /^( {0,3}\d{1,9})([.)])(?:[ \t]|$)/
/** A fence opening: a backtick fence's info string cannot contain backticks. */
const FENCE = /^ {0,3}(?:`{3,}[^`]*$|~{3,})/
const DELIM_ROW = /^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/
const FOOTNOTE_DEF_START = /^( {0,3}\[\^[^\]\s]+\]):/

/** Escapes what would otherwise start a block at the beginning of a paragraph line. */
function escapeLineStart(line: string, first: boolean): string {
  if (line === '') return line
  if (/^\s/.test(line)) {
    // Leading whitespace is stripped by the parser; an entity keeps it.
    const ch = Array.from(line)[0]
    return entity(ch) + line.slice(ch.length)
  }
  const insertAt = (i: number) => line.slice(0, i) + '\\' + line.slice(i)
  if (THEMATIC.test(line) || SETEXT.test(line)) return insertAt(line.search(/\S/))
  if (ATX.test(line) || QUOTE.test(line) || BULLET.test(line) || FENCE.test(line)) return insertAt(line.search(/\S/))
  const ord = ORDERED.exec(line)
  if (ord) return insertAt(ord[1].length)
  const fn = FOOTNOTE_DEF_START.exec(line)
  if (fn) return insertAt(fn[1].length)
  if (!first && line.includes('|') && DELIM_ROW.test(line)) return insertAt(line.indexOf('-'))
  return line
}

/** Encodes whitespace the parser would strip at the end of a line. */
function escapeLineEnd(line: string, lastLine: boolean): string {
  const chars = Array.from(line)
  const last = chars[chars.length - 1]
  if (last === undefined) return line
  if (lastLine ? /\s/.test(last) : last === ' ' || last === '\t') {
    return line.slice(0, line.length - last.length) + entity(last)
  }
  return line
}

/**
 * Marks a line feed inside raw inline HTML in rendered output. Text never contains NUL (it is
 * normalized to U+FFFD, as the parser does), so the marker cannot collide with content.
 */
const RAW_NEWLINE = '\0'

/**
 * Splits rendered paragraph content into lines and protects each line's start and end. A line
 * break inside raw HTML is not a paragraph line break: the HTML around it is left untouched.
 */
function paragraphLines(rendered: string): string[] {
  const lines = rendered.split('\n')
  return lines.flatMap((line, i) => {
    const parts = line.split(RAW_NEWLINE)
    const last = parts.length - 1
    parts[last] = escapeLineEnd(parts[last], i === lines.length - 1)
    parts[0] = escapeLineStart(parts[0], i === 0)
    return parts
  })
}

/** Heading or table-cell content: one line, trimmed by the parser at both ends. */
function flatLine(rendered: string, heading: boolean): string {
  let l = rendered.replace(/\0/g, '\n')
  if (/^\s/.test(l)) {
    const ch = Array.from(l)[0]
    l = entity(ch) + l.slice(ch.length)
  }
  l = escapeLineEnd(l, true)
  if (heading) {
    // A trailing run of # after a space is a closing sequence; so is a heading of only #.
    const m = /(^|[ \t])(#+)$/.exec(l)
    if (m) l = l.slice(0, l.length - m[2].length) + '\\' + m[2]
  }
  return l
}

// ---------------------------------------------------------------------------------------------
// Textblock serialization with verification
// ---------------------------------------------------------------------------------------------

interface DocContext {
  /**
   * The env holding the document's link and footnote definitions, as JSON: parsing writes into
   * its env, so every verification parses with a fresh copy. (JSON rather than structuredClone,
   * which WebView before Chrome 98 lacks; the env holds only strings, numbers and plain objects.)
   */
  envJson: string
  /** Identifies the env, so cached renderings are reused only under the same definitions. */
  signature: string
}

interface TextblockResult {
  text: string
  node: Node
  exact: boolean
}

const cache = new WeakMap<Node, Map<string, TextblockResult>>()

/**
 * Whether `text`, as written for a textblock of type `parent`, reads back as `expected`.
 * Paragraphs and headings are parsed as whole blocks, so that block-level readings (a
 * definition, a setext underline, a table) are caught too; table cells are parsed inline.
 */
function textblockMatches(text: string, parent: NodeType, expected: Fragment, ctx: DocContext, level?: number): boolean {
  try {
    let children
    if (parent === nodes.table_cell || parent === nodes.table_header) {
      // The table rule unescapes \| before inline parsing; mirror it.
      children = md.parseInline(unescapePipes(text), JSON.parse(ctx.envJson))[0]?.children ?? []
    } else {
      const src = parent === nodes.heading ? '#'.repeat(level ?? 1) + ' ' + text : text
      const tokens = md.parse(src, JSON.parse(ctx.envJson))
      const open = parent === nodes.heading ? 'heading_open' : 'paragraph_open'
      if (tokens.length !== 3 || tokens[0].type !== open || tokens[1].type !== 'inline') return false
      children = tokens[1].children ?? []
    }
    return Fragment.fromArray(convertInline(children, parent)).eq(expected)
  } catch (e) {
    if (e instanceof MarkdownParseError) return false
    throw e
  }
}

function unescapePipes(s: string): string {
  let out = ''
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && s[i + 1] === '|') continue
    out += s[i]
  }
  return out
}

/**
 * Removes delimiter marks from the edge units where markdown cannot place their delimiters:
 * a delimiter between a letter and punctuation can neither open nor close. The model is
 * pessimistic about syntax stacked at a boundary (another delimiter, a link bracket or a
 * backtick counts as punctuation on the inside), so it may shrink more than needed; it is only
 * used after rendering the unshrunk content has failed.
 */
function shrinkMarks(content: Fragment): Fragment {
  const units = toUnits(iterFragment(content))
  const outputEdge = (u: Unit | undefined, atEnd: boolean): string => {
    if (!u) return ' '
    if (u.node) return u.node.type === nodes.hard_break ? '\n' : atEnd ? ']' : '['
    if (marks.code.isInSet(u.marks)) return '`'
    return u.ch!
  }
  const punct = (ch: string) => isMdAsciiPunct(ch.charCodeAt(0)) || md.utils.isPunctChar(ch)
  const syntaxMarks = [...DELIMITER_MARKS.map((n) => schema.marks[n]), marks.link, marks.code]
  const markAt = (i: number, type: Mark['type']) => (units[i] ? type.isInSet(units[i].marks) : undefined)
  const sameMark = (a: Mark | undefined, b: Mark | undefined) => (a && b ? a.eq(b) : a === b)
  /** Whether a mark other than `except` starts at unit i (so its syntax is emitted there). */
  const otherStarts = (i: number, except: Mark['type']) =>
    syntaxMarks.some((t) => t !== except && markAt(i, t) && !sameMark(markAt(i, t), markAt(i - 1, t)))
  const otherEnds = (i: number, except: Mark['type']) =>
    syntaxMarks.some((t) => t !== except && markAt(i, t) && !sameMark(markAt(i, t), markAt(i + 1, t)))
  const trimmable = (u: Unit) => u.ch !== null && isWs(u.ch) && !marks.code.isInSet(u.marks)
  let changed = true
  while (changed) {
    changed = false
    for (const name of DELIMITER_MARKS) {
      const type = schema.marks[name]
      forEachRun(units, type, (from, to) => {
        if (trimmable(units[from]) || trimmable(units[to - 1])) {
          const i = trimmable(units[from]) ? from : to - 1
          units[i] = {...units[i], marks: type.removeFromSet(units[i].marks)}
          changed = true
          return
        }
        const before = outputEdge(units[from - 1], true)
        const first = otherStarts(from, type) ? '*' : outputEdge(units[from], false)
        if (punct(first) && !isWs(before) && !punct(before)) {
          units[from] = {...units[from], marks: type.removeFromSet(units[from].marks)}
          changed = true
          return
        }
        const last = otherEnds(to - 1, type) ? '*' : outputEdge(units[to - 1], true)
        const after = outputEdge(units[to], false)
        if (punct(last) && !isWs(after) && !punct(after)) {
          units[to - 1] = {...units[to - 1], marks: type.removeFromSet(units[to - 1].marks)}
          changed = true
        }
      })
    }
  }
  return Fragment.fromArray(fromUnits(units))
}

function iterFragment(f: Fragment): Node[] {
  const out: Node[] = []
  f.forEach((n) => out.push(n))
  return out
}

interface Attempt {
  level: Level
  emChar: '*' | '_'
  shrink: boolean
}

/**
 * Cleaner renderings first. Shrunk content gets the same sequence as unshrunk content, so that
 * serializing a result again picks the same rendering: the output is stable.
 */
const ATTEMPTS: Attempt[] = [
  {level: 0, emChar: '*', shrink: false},
  {level: 1, emChar: '*', shrink: false},
  {level: 1, emChar: '_', shrink: false},
  {level: 0, emChar: '*', shrink: true},
  {level: 1, emChar: '*', shrink: true},
  {level: 1, emChar: '_', shrink: true},
]

class TextblockRenderer {
  readonly inTable: boolean

  constructor(
    readonly node: Node,
    readonly ctx: DocContext,
    readonly minLevel: Level,
  ) {
    this.inTable = node.type === nodes.table_cell || node.type === nodes.table_header
  }

  /** Inline rendering plus the line fix-ups of the block type: the text exactly as written. */
  finish(content: Fragment, a: Attempt): string {
    const rendered = renderInline(iterFragment(content), {level: a.level, emChar: a.emChar, inTable: this.inTable})
    return this.node.type === nodes.paragraph
      ? paragraphLines(rendered).join('\n')
      : flatLine(rendered, this.node.type === nodes.heading)
  }

  /** The first attempt whose output parses back to `content` (or its shrunk form). */
  tryRender(content: Fragment): TextblockResult | null {
    let shrunk: Fragment | null = null
    for (const a of ATTEMPTS) {
      if (a.level < this.minLevel) continue
      const c = a.shrink ? (shrunk ??= shrinkMarks(content)) : content
      const text = this.finish(c, a)
      if (textblockMatches(text, this.node.type, c, this.ctx, this.node.attrs.level)) {
        const node = c.eq(this.node.content) ? this.node : this.node.type.create(this.node.attrs, c)
        return {text, node, exact: true}
      }
    }
    return null
  }

  render(): TextblockResult {
    return this.tryRender(this.node.content) ?? this.dropFormattingUntilExact()
  }

  /**
   * Last resort for formatting markdown cannot express (pathological overlaps of several marks
   * at punctuation): removes formatting runs one at a time until the content round-trips, and
   * if no single removal is enough, removes the first and tries again. Plain text with full
   * escaping always round-trips, so this terminates.
   */
  dropFormattingUntilExact(): TextblockResult {
    let units = toUnits(iterFragment(shrinkMarks(this.node.content)))
    const without = (us: Unit[], run: {type: Mark['type']; from: number; to: number}) =>
      us.map((u, i) => (i >= run.from && i < run.to ? {...u, marks: run.type.removeFromSet(u.marks)} : u))
    for (;;) {
      // Delimiter marks first, shortest first; code and links only if nothing else helps
      // (a code span holding `]:` as a link's whole text reads as a definition, for one).
      const runs: {type: Mark['type']; from: number; to: number}[] = []
      for (const name of DELIMITER_MARKS) {
        forEachRun(units, schema.marks[name], (from, to) => runs.push({type: schema.marks[name], from, to}))
      }
      runs.sort((a, b) => a.to - a.from - (b.to - b.from))
      const last: typeof runs = []
      for (const type of [marks.code, marks.link]) forEachRun(units, type, (from, to) => last.push({type, from, to}))
      runs.push(...last)
      if (!runs.length) {
        const content = Fragment.fromArray(fromUnits(units))
        const a: Attempt = {level: 1, emChar: '*', shrink: false}
        const text = this.finish(content, a)
        const exact = textblockMatches(text, this.node.type, content, this.ctx, this.node.attrs.level)
        return {text, node: this.node.type.create(this.node.attrs, content), exact}
      }
      for (const run of runs) {
        const r = this.tryRender(Fragment.fromArray(fromUnits(without(units, run))))
        if (r) return r
      }
      units = without(units, runs[0])
    }
  }
}

/**
 * Renders a textblock's content, trying cleaner renderings first and keeping the first one that
 * parses back to the same content. The returned node differs from `node` only if formatting
 * markdown cannot express had to be shrunk or dropped.
 */
function renderTextblock(node: Node, ctx: DocContext, minLevel: Level): TextblockResult {
  const key = `${ctx.signature}\u0000${minLevel}`
  let perNode = cache.get(node)
  const hit = perNode?.get(key)
  if (hit) return hit
  const result = new TextblockRenderer(node, ctx, minLevel).render()
  if (!perNode) cache.set(node, (perNode = new Map()))
  perNode.set(key, result)
  return result
}

// ---------------------------------------------------------------------------------------------
// Block serialization
// ---------------------------------------------------------------------------------------------

interface BlockOut {
  lines: string[]
  node: Node
  exact: boolean
}

interface BlockOptions {
  ctx: DocContext
  minLevel: Level
  /** Set when the block's first line follows a list marker using this character. */
  listMarker?: string
}

function prefixLines(lines: string[], first: string, rest: string): string[] {
  return lines.map((l, i) => {
    if (i === 0) return l === '' ? first.trimEnd() : first + l
    return l === '' ? (rest.trim() ? rest.trimEnd() : '') : rest + l
  })
}

function fenceFor(text: string, language: string): {open: string; close: string} {
  const char = language.includes('`') ? '~' : '`'
  let longest = 0
  for (const line of text.split('\n')) {
    const m = new RegExp(`^ {0,3}(\\${char}+)`).exec(line)
    if (m) longest = Math.max(longest, m[1].length)
  }
  const fence = char.repeat(Math.max(3, longest + 1))
  return {open: fence + language, close: fence}
}

/**
 * Whether a list can be written tight (no blank lines) without changing its structure: each
 * item must be a paragraph followed only by lists, and the list right after the paragraph must
 * be able to interrupt it (ordered lists only when starting at 1, and never with an empty item).
 */
function canBeTight(list: Node): boolean {
  let ok = true
  list.forEach((item) => {
    item.forEach((child, _offset, index) => {
      if (index === 0) {
        if (child.type !== nodes.paragraph) ok = false
        return
      }
      if (!(child.type === nodes.bullet_list || (child.type === nodes.ordered_list && child.attrs.order === 1))) {
        ok = false
        return
      }
      const first = child.firstChild!
      if (index === 1 && first.attrs.checked === null && isEmptyParagraph(first.firstChild!)) ok = false
    })
  })
  return ok
}

const BULLETS = ['-', '*', '+']

/**
 * The marker character for `child` if it is a list: a list directly after a sibling list of
 * the same kind would merge with it, so it gets a different bullet (or `)` instead of `.`).
 */
function listMarkerAfter(prev: {node: Node; marker: string} | null, child: Node): string {
  const ordered = child.type === nodes.ordered_list
  if (!prev || prev.node.type !== child.type || !(ordered || child.type === nodes.bullet_list)) {
    return ordered ? '.' : '-'
  }
  return ordered ? (prev.marker === '.' ? ')' : '.') : BULLETS[(BULLETS.indexOf(prev.marker) + 1) % BULLETS.length]
}

function serializeChildren(
  parent: Node,
  opts: BlockOptions,
  separator: string[] = [''],
): {lines: string[]; children: Node[]; exact: boolean} {
  const lines: string[] = []
  const children: Node[] = []
  let exact = true
  let prev: {node: Node; marker: string} | null = null
  parent.forEach((child, _offset, index) => {
    const childOpts: BlockOptions = {ctx: opts.ctx, minLevel: opts.minLevel}
    if (index === 0 && opts.listMarker) childOpts.listMarker = opts.listMarker
    let marker = listMarkerAfter(prev, child)
    // A list opening a list item shares its first line with the item's marker; the same bullet
    // three times ("- - -") would read as a thematic break.
    if (index === 0 && child.type === nodes.bullet_list && marker === opts.listMarker) {
      marker = BULLETS[(BULLETS.indexOf(marker) + 1) % BULLETS.length]
    }
    const out = serializeBlock(child, childOpts, marker)
    if (index > 0) lines.push(...separator)
    lines.push(...out.lines)
    children.push(out.node)
    exact &&= out.exact
    prev = {node: child, marker}
  })
  return {lines, children, exact}
}

function serializeBlock(node: Node, opts: BlockOptions, listMarker = '-'): BlockOut {
  const t = node.type
  const {ctx} = opts

  if (t === nodes.paragraph) {
    if (node.content.size === 0) return {lines: [''], node, exact: true}
    const r = renderTextblock(node, ctx, opts.minLevel)
    return {lines: r.text.split('\n'), node: r.node, exact: r.exact}
  }
  if (t === nodes.heading) {
    const hashes = '#'.repeat(node.attrs.level)
    if (node.content.size === 0) return {lines: [hashes], node, exact: true}
    const r = renderTextblock(node, ctx, opts.minLevel)
    return {lines: [hashes + ' ' + r.text], node: r.node, exact: r.exact}
  }
  if (t === nodes.code_block) {
    const text = node.textContent
    const {open, close} = fenceFor(text, node.attrs.language)
    const body = text === '' ? [] : text.split('\n')
    return {lines: [open, ...body, close], node, exact: true}
  }
  if (t === nodes.horizontal_rule) {
    const lines = [opts.listMarker === '-' ? '***' : '---']
    return {lines, node, exact: true}
  }
  if (t === nodes.blockquote) {
    const inner = serializeChildren(node, opts)
    const lines = inner.lines.map((l) => (l === '' ? '>' : '> ' + l))
    return {lines, node: t.create(node.attrs, inner.children), exact: inner.exact}
  }
  if (t === nodes.bullet_list || t === nodes.ordered_list) {
    const tight = canBeTight(node)
    const lines: string[] = []
    const items: Node[] = []
    let exact = true
    let number = t === nodes.ordered_list ? node.attrs.order : 0
    node.forEach((item, _o, index) => {
      const marker = t === nodes.ordered_list ? `${number++}${listMarker}` : listMarker
      const indent = ' '.repeat(marker.length + 1)
      const task = item.attrs.checked === null ? '' : item.attrs.checked ? '[x] ' : '[ ] '
      const inner = serializeChildren(item, {...opts, listMarker: t === nodes.bullet_list ? listMarker : '.'}, tight ? [] : [''])
      let itemLines = inner.lines
      if (task) itemLines = [itemLines[0] === '' ? task.trimEnd() : task + itemLines[0], ...itemLines.slice(1)]
      if (index > 0 && !tight) lines.push('')
      lines.push(...prefixLines(itemLines, marker + ' ', indent))
      items.push(item.type.create(item.attrs, inner.children))
      exact &&= inner.exact
    })
    return {lines, node: t.create(node.attrs, items), exact}
  }
  if (t === nodes.footnote_def) {
    const inner = serializeChildren(node, opts)
    const head = `[^${node.attrs.label}]:`
    // The footnote rule places a first line's content at a virtual column, which nested
    // containers cannot be indented against; anything but a paragraph starts on the next line.
    const lines =
      node.firstChild!.type === nodes.paragraph
        ? prefixLines(inner.lines, head + ' ', '    ')
        : [head, ...inner.lines.map((l) => (l === '' ? '' : '    ' + l))]
    return {lines, node: t.create(node.attrs, inner.children), exact: inner.exact}
  }
  if (t === nodes.link_def || t === nodes.html_block) {
    return {lines: String(node.attrs.raw).split('\n'), node, exact: true}
  }
  if (t === nodes.table) {
    const rows: Node[] = []
    const lines: string[] = []
    let exact = true
    node.forEach((row, _o, r) => {
      const cells: Node[] = []
      const texts: string[] = []
      row.forEach((cell) => {
        const res = cell.content.size ? renderTextblock(cell, ctx, opts.minLevel) : {text: '', node: cell, exact: true}
        texts.push(res.text)
        cells.push(res.node)
        exact &&= res.exact
      })
      lines.push('| ' + texts.join(' | ') + ' |')
      if (r === 0) {
        const delims: string[] = []
        row.forEach((cell) => {
          const a = cell.attrs.align
          delims.push(a === 'left' ? ':--' : a === 'center' ? ':-:' : a === 'right' ? '--:' : '---')
        })
        lines.push('| ' + delims.join(' | ') + ' |')
      }
      rows.push(row.type.create(row.attrs, cells))
    })
    return {lines, node: t.create(node.attrs, rows), exact}
  }
  throw new Error(`Cannot serialize block node ${t.name}`)
}

// ---------------------------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------------------------

function buildContext(doc: Node): DocContext {
  const defs: string[] = []
  const footnotes: string[] = []
  doc.descendants((n) => {
    if (n.type === nodes.link_def) defs.push(n.attrs.raw)
    if (n.type === nodes.footnote_def) footnotes.push(n.attrs.label)
    return !n.isTextblock
  })
  const env: ParseEnv = {}
  if (defs.length) md.parse(defs.join('\n\n'), env)
  if (footnotes.length) {
    const refs: Record<string, number> = {}
    for (const label of footnotes) refs[':' + label] = -1
    env.footnotes = {refs}
  }
  return {envJson: JSON.stringify(env), signature: JSON.stringify([defs, footnotes])}
}

function renderDoc(doc: Node, ctx: DocContext, escalated: Set<number>): {markdown: string; doc: Node; exact: boolean} {
  let out = ''
  const children: Node[] = []
  let exact = true
  let pendingSpacers = 0
  let wroteBlock = false
  let firstLine = true
  let prev: {node: Node; marker: string} | null = null
  doc.forEach((child, _offset, index) => {
    if (child.type === nodes.frontmatter) {
      out += '---\n' + (child.textContent ? child.textContent + '\n' : '') + '---\n'
      children.push(child)
      // firstLine stays true: a `---` rule right after the front matter is easily misread too.
      return
    }
    if (isEmptyParagraph(child)) {
      pendingSpacers++
      children.push(child)
      return
    }
    const opts: BlockOptions = {ctx, minLevel: escalated.has(index) ? 1 : 0}
    const marker = listMarkerAfter(prev, child)
    let block = serializeBlock(child, opts, marker)
    prev = {node: child, marker}
    // A thematic break on the first line would read as the start of front matter.
    if (firstLine && pendingSpacers === 0 && child.type === nodes.horizontal_rule) block = {...block, lines: ['***']}
    if (wroteBlock) out += '\n'
    out += '\n'.repeat(pendingSpacers) + block.lines.join('\n') + '\n'
    pendingSpacers = 0
    wroteBlock = true
    firstLine = false
    children.push(block.node)
    exact &&= block.exact
  })
  if (wroteBlock) out += '\n'.repeat(pendingSpacers)
  return {markdown: out, doc: doc.type.create(doc.attrs, children), exact}
}

/** Indices of top-level children that differ between two docs (by aligning both ends). */
function differingRange(a: Node, b: Node): [number, number] {
  let start = 0
  while (start < a.childCount && start < b.childCount && a.child(start).eq(b.child(start))) start++
  let endA = a.childCount
  let endB = b.childCount
  while (endA > start && endB > start && a.child(endA - 1).eq(b.child(endB - 1))) {
    endA--
    endB--
  }
  return [start, Math.max(endA, start + 1)]
}

export function serializeMarkdown(input: Node): SerializeResult {
  const doc = normalizeDoc(input)
  const ctx = buildContext(doc)
  const escalated = new Set<number>()
  let result = renderDoc(doc, ctx, escalated)
  for (let attempt = 0; attempt < 4; attempt++) {
    let back: Node
    try {
      back = parseMarkdown(result.markdown)
    } catch (e) {
      if (!(e instanceof MarkdownParseError)) throw e
      return {markdown: result.markdown, doc: result.doc, exact: false}
    }
    if (back.eq(result.doc)) return {markdown: result.markdown, doc: result.doc, exact: result.exact}
    const [from, to] = differingRange(result.doc, back)
    let grew = false
    for (let i = from; i < to; i++) {
      if (!escalated.has(i)) {
        escalated.add(i)
        grew = true
      }
    }
    if (!grew) {
      if (escalated.size === doc.childCount) break
      for (let i = 0; i < doc.childCount; i++) escalated.add(i)
    }
    result = renderDoc(doc, ctx, escalated)
  }
  return {markdown: result.markdown, doc: result.doc, exact: false}
}
