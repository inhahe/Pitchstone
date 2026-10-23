// Markdown shortcuts: typing markdown syntax formats the text on the fly.
//
// Every rule checks `settings.markdownShortcuts`; when shortcuts are off, typed symbols stay
// literal characters, and the serializer escapes them so they never become formatting.
//
// A rule's handler gets the range [start, end) of the document that holds the match, except
// for text still being typed: when a key is pressed, the last character of the match is not in
// the document yet (it is `pending`); when an IME composition ends, the whole match is.

import {InputRule, inputRules, textblockTypeInputRule, wrappingInputRule} from 'prosemirror-inputrules'
import type {MarkType, Node} from 'prosemirror-model'
import {type EditorState, type Plugin, TextSelection, type Transaction} from 'prosemirror-state'
import {findWrapping} from 'prosemirror-transform'
import {schema} from '../schema'
import {settings} from './settings'

const {nodes, marks} = schema

/** Number of characters at the end of `match[0]` that are being typed, not yet in the doc. */
function pendingLength(match: RegExpMatchArray, start: number, end: number): number {
  return match[0].length - (end - start)
}

/**
 * A delimited mark: `regexp` must capture (1) anything before the opening delimiter that is
 * not part of the formatting, (2) the opening delimiter, (3) the inner text, (4) the closing one.
 */
function markRule(regexp: RegExp, type: MarkType, attrs?: (m: RegExpMatchArray) => Record<string, unknown>): InputRule {
  return new InputRule(
    regexp,
    (state, match, start, end) => {
      const [, prefix, open, inner, close] = match
      if (!inner) return null
      const openStart = start + prefix.length
      const innerStart = openStart + open.length
      const innerEnd = innerStart + inner.length
      const closeInDoc = close.length - pendingLength(match, start, end)
      const $start = state.doc.resolve(innerStart)
      if (!$start.parent.type.allowsMarkType(type)) return null
      const tr = state.tr
      if (closeInDoc > 0) tr.delete(innerEnd, innerEnd + closeInDoc)
      tr.delete(openStart, innerStart)
      tr.addMark(openStart, openStart + inner.length, type.create(attrs ? attrs(match) : null))
      tr.removeStoredMark(type)
      return tr
    },
    {inCodeMark: false},
  )
}

/** Replaces the match with an inline node built from it. */
function nodeRule(regexp: RegExp, build: (m: RegExpMatchArray) => Node | null, prefixGroup = 1): InputRule {
  return new InputRule(
    regexp,
    (state, match, start, end) => {
      const node = build(match)
      if (!node) return null
      const from = start + (match[prefixGroup]?.length ?? 0)
      const $from = state.doc.resolve(from)
      if (!$from.parent.canReplaceWith($from.index(), $from.index(), node.type)) return null
      const marks = $from.marks().filter((m) => node.type.allowsMarkType(m.type))
      return state.tr.replaceWith(from, end, node.mark(marks))
    },
    {inCodeMark: false},
  )
}

/** `[ ] ` or `[x] ` at the start of a paragraph: a task item. */
function taskRule(): InputRule {
  return new InputRule(/^\[([ xX]?)\][ \u00a0]$/, (state, match, start, end) => {
    const checked = match[1] === 'x' || match[1] === 'X'
    const $start = state.doc.resolve(start)
    const para = $start.parent
    if (para.type !== nodes.paragraph) return null
    const itemDepth = $start.depth - 1
    const item = $start.node(itemDepth)
    const tr = state.tr.delete(start, end)
    if (item && item.type === nodes.list_item && $start.index(itemDepth) === 0) {
      tr.setNodeMarkup($start.before(itemDepth), null, {...item.attrs, checked})
      return tr
    }
    // Not in a list yet: wrap the paragraph in a bullet list whose item is a task.
    const $para = tr.doc.resolve(tr.mapping.map(start))
    const range = $para.blockRange()
    if (!range) return null
    const wrapping = findWrapping(range, nodes.bullet_list)
    if (!wrapping) return null
    tr.wrap(range, wrapping)
    const $inner = tr.doc.resolve(tr.mapping.map(start))
    for (let d = $inner.depth; d > 0; d--) {
      if ($inner.node(d).type === nodes.list_item) {
        tr.setNodeMarkup($inner.before(d), null, {checked})
        break
      }
    }
    return tr
  })
}

/** "```lang " at the start of a paragraph: a code block. */
function codeBlockRule(): InputRule {
  return textblockTypeInputRule(/^```([^`\s]*)[ \u00a0]$/, nodes.code_block, (m) => ({language: m[1] || ''}))
}

function headingRule(): InputRule {
  return textblockTypeInputRule(/^(#{1,6})[ \u00a0]$/, nodes.heading, (m) => ({level: m[1].length}))
}

/** Groups: (1) prefix, (2) the whole link, (3) `!` for an embed, (4) target, (5) alias. */
const wikilink = (m: RegExpMatchArray) => {
  const target = m[4]
  if (!target.trim()) return null
  return nodes.wikilink.create({target, alias: m[5] ?? null, embed: m[3] === '!'})
}

export function markdownRules(): InputRule[] {
  const rules = [
    // Block structure, at the start of a textblock.
    headingRule(),
    wrappingInputRule(/^\s*>[ \u00a0]$/, nodes.blockquote),
    wrappingInputRule(/^\s*([-+*])[ \u00a0]$/, nodes.bullet_list),
    wrappingInputRule(
      /^(\d{1,9})[.)][ \u00a0]$/,
      nodes.ordered_list,
      (m) => ({order: Number(m[1])}),
      (m, node) => node.childCount + node.attrs.order === Number(m[1]),
    ),
    taskRule(),
    codeBlockRule(),

    // Inline atoms.
    nodeRule(/(^|[^\\])((!?)\[\[([^[\]\n|]+)(?:\|([^[\]\n]*))?\]\])$/, wikilink),
    nodeRule(
      /(^|[^\\])(!\[([^\]]*)\]\(([^)\s]+)\))$/,
      (m) => nodes.image.create({src: m[4], alt: m[3], title: null}),
    ),

    // Delimited marks. Each requires the inner text not to start or end with whitespace, and
    // the opening delimiter not to be escaped or part of a longer run.
    markRule(/(^|[^*\\])(\*\*)([^*\s](?:[^*]*[^*\s])?)(\*\*)$/, marks.strong),
    markRule(/(^|[^_\w\\])(__)([^_\s](?:[^_]*[^_\s])?)(__)$/u, marks.strong),
    markRule(/(^|[^*\\])(\*)([^*\s](?:[^*]*[^*\s])?)(\*)$/, marks.em),
    markRule(/(^|[^_\w\\])(_)([^_\s](?:[^_]*[^_\s])?)(_)$/u, marks.em),
    markRule(/(^|[^~\\])(~~)([^~\s](?:[^~]*[^~\s])?)(~~)$/, marks.strike),
    markRule(/(^|[^=\\])(==)([^=\s](?:[^=]*[^=\s])?)(==)$/, marks.highlight),
    markRule(/(^|[^`\\])(`)([^`]+)(`)$/, marks.code),
    markRule(
      /(^|[^!\\])(\[)([^\]]+)(\]\(([^)\s]+)(?:\s+"([^"]*)")?\))$/,
      marks.link,
      (m) => ({href: m[5], title: m[6] ?? null}),
    ),
  ]
  return rules
}

/**
 * The input-rules plugin, inert while markdown shortcuts are off. Gating the plugin's entry
 * points (typed text, and the end of an IME composition) covers every rule at once.
 */
export function markdownInputRules(): Plugin {
  const plugin = inputRules({rules: markdownRules()})
  type Handler = (...args: unknown[]) => boolean
  const props = plugin.props as unknown as {
    handleTextInput: Handler
    handleDOMEvents: Record<string, Handler>
  }
  const onText = props.handleTextInput
  props.handleTextInput = (...args) => settings.markdownShortcuts && onText(...args)
  const onCompositionEnd = props.handleDOMEvents.compositionend
  props.handleDOMEvents = {
    ...props.handleDOMEvents,
    compositionend: (...args) => (settings.markdownShortcuts ? onCompositionEnd(...args) : false),
  }
  return plugin
}

/**
 * Enter on a paragraph that holds only `---` (or `***`, `___`) makes a horizontal rule, and on
 * "```lang" a code block. Returns false when it does not apply.
 */
export function enterShortcut(state: EditorState, dispatch?: (tr: Transaction) => void): boolean {
  if (!settings.markdownShortcuts) return false
  const {$cursor} = state.selection as TextSelection
  if (!$cursor) return false
  const para = $cursor.parent
  if (para.type !== nodes.paragraph || $cursor.parentOffset !== para.content.size) return false
  const text = para.textContent
  const start = $cursor.before()
  const end = $cursor.after()
  if (/^(?:---|\*\*\*|___)$/.test(text)) {
    if (dispatch) {
      const tr = state.tr.replaceWith(start, end, [nodes.horizontal_rule.create(), nodes.paragraph.create()])
      tr.setSelection(TextSelection.create(tr.doc, start + 2))
      dispatch(tr.scrollIntoView())
    }
    return true
  }
  const fence = /^```([^`\s]*)$/.exec(text)
  if (fence) {
    if (dispatch) {
      const tr = state.tr.replaceWith(start, end, nodes.code_block.create({language: fence[1]}))
      tr.setSelection(TextSelection.create(tr.doc, start + 1))
      dispatch(tr.scrollIntoView())
    }
    return true
  }
  return false
}
