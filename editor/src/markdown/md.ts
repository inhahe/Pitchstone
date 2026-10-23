// The configured markdown-it instance that defines the app's markdown dialect.
//
// Dialect: CommonMark, plus GFM tables, strikethrough (~~) and task lists, plus Obsidian's
// highlights (==), wikilinks ([[target|alias]], ![[embed]]) and footnotes ([^label]).
// A single newline inside a paragraph is a line break (`breaks: true`), as in Obsidian.
// Raw HTML is recognised so that it can be preserved verbatim, but it is never rendered.
// Bare URLs are not turned into links (no linkify): they stay text, and the editor decorates them.

import markdownIt, {type MarkdownIt, type StateBlock, type StateCore, type StateInline} from 'markdown-it'
import footnote from 'markdown-it-footnote'
import mark from 'markdown-it-mark'

type BlockRule = (state: StateBlock, startLine: number, endLine: number, silent: boolean) => boolean

/** Matches a GFM task-list marker at the start of a list item's first paragraph. */
export const TASK_MARKER = /^\[([ xX])\](?=[ \t\n]|$)/

/**
 * Parses `[[target]]`, `[[target|alias]]` and `![[target]]`. The inner text may not contain
 * brackets or line breaks. A backslash right before the `|` is dropped from the target, so that
 * the table-safe spelling `[[a\|b]]` means the same as `[[a|b]]`.
 */
function wikilinkRule(state: StateInline, silent: boolean): boolean {
  const src = state.src
  let pos = state.pos
  let embed = false
  if (src.charCodeAt(pos) === 0x21 /* ! */) {
    embed = true
    pos++
  }
  if (src.charCodeAt(pos) !== 0x5b || src.charCodeAt(pos + 1) !== 0x5b) return false
  const innerStart = pos + 2
  const close = src.indexOf(']]', innerStart)
  if (close < 0 || close + 2 > state.posMax) return false
  const inner = src.slice(innerStart, close)
  if (/[[\]\n]/.test(inner)) return false
  const bar = inner.indexOf('|')
  let target = bar < 0 ? inner : inner.slice(0, bar)
  const alias = bar < 0 ? null : inner.slice(bar + 1)
  if (bar >= 0 && target.endsWith('\\')) target = target.slice(0, -1)
  if (!target.trim()) return false
  if (!silent) {
    const token = state.push('wikilink', '', 0)
    token.meta = {target, alias, embed}
    token.content = src.slice(state.pos, close + 2)
  }
  state.pos = close + 2
  return true
}

/**
 * Moves a GFM task marker (`[ ]`, `[x]`) from the start of a list item's first paragraph onto
 * the item, as the `checked` meta. Runs on the raw inline source, so an escaped `\[ ]` is not a
 * marker.
 */
function taskListRule(state: StateCore): void {
  const tokens = state.tokens
  for (let i = 2; i < tokens.length; i++) {
    const inline = tokens[i]
    if (inline.type !== 'inline') continue
    if (tokens[i - 1].type !== 'paragraph_open' || tokens[i - 2].type !== 'list_item_open') continue
    const m = TASK_MARKER.exec(inline.content)
    if (!m) continue
    tokens[i - 2].meta = {...(tokens[i - 2].meta || {}), checked: m[1] !== ' '}
    inline.content = inline.content.slice(m[0].length).replace(/^[ \t]+/, '')
  }
}

/**
 * Wraps the reference-definition rule so that each definition also leaves a `link_def` token
 * carrying its source. markdown-it otherwise consumes definitions without a trace, and an
 * unused definition (a cited source, say) would vanish from the note the first time it is saved.
 */
function preserveDefinitions(md: MarkdownIt): void {
  const rules = (md.block.ruler as unknown as {__rules__: {name: string; fn: BlockRule}[]}).__rules__
  const original = rules.find((r) => r.name === 'reference')?.fn
  if (!original) throw new Error('markdown-it has no reference rule')
  const wrapped: BlockRule = (state, startLine, endLine, silent) => {
    if (!original(state, startLine, endLine, silent)) return false
    if (!silent) {
      const token = state.push('link_def', '', 0)
      token.content = state.getLines(startLine, state.line, state.blkIndent, false).replace(/\n$/, '')
      token.map = [startLine, state.line]
    }
    return true
  }
  md.block.ruler.at('reference', wrapped, {alt: ['paragraph', 'reference']})
}

/**
 * Wraps the footnote-definition rule so its opening token records the source lines it spans;
 * the plugin leaves `map` unset, and the parser needs it to count blank lines between blocks.
 */
function mapFootnoteDefinitions(md: MarkdownIt): void {
  const rules = (md.block.ruler as unknown as {__rules__: {name: string; fn: BlockRule}[]}).__rules__
  const original = rules.find((r) => r.name === 'footnote_def')?.fn
  if (!original) throw new Error('markdown-it-footnote has no footnote_def rule')
  const wrapped: BlockRule = (state, startLine, endLine, silent) => {
    const first = state.tokens.length
    if (!original(state, startLine, endLine, silent)) return false
    const open = state.tokens[first]
    if (!silent && open?.type === 'footnote_reference_open') open.map = [startLine, state.line]
    return true
  }
  md.block.ruler.at('footnote_def', wrapped, {alt: ['paragraph', 'reference']})
}

export function createMarkdownIt(): MarkdownIt {
  const md = markdownIt('commonmark', {html: true, breaks: true, linkify: false, typographer: false})
  md.enable(['table', 'strikethrough'])
  md.use(mark)
  md.use(footnote)
  // Footnote definitions stay where they are written (no reordering to the end of the doc),
  // and the inline ^[...] form is not supported.
  md.core.ruler.disable('footnote_tail')
  md.inline.ruler.disable('footnote_inline')
  mapFootnoteDefinitions(md)
  md.inline.ruler.before('link', 'wikilink', wikilinkRule)
  md.core.ruler.after('block', 'task_lists', taskListRule)
  preserveDefinitions(md)
  return md
}

export const md = createMarkdownIt()

/** markdown-it's notion of whitespace, used for emphasis flanking. */
export const isWhiteSpace: (code: number) => boolean = md.utils.isWhiteSpace
/** markdown-it's notion of punctuation (Unicode P and S categories), used for flanking. */
export const isPunctChar: (ch: string) => boolean = md.utils.isPunctChar
/** The ASCII punctuation characters a backslash can escape. */
export const isMdAsciiPunct: (code: number) => boolean = md.utils.isMdAsciiPunct
