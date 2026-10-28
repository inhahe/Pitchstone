import {describe, expect, it} from 'vitest'
import type {Node} from 'prosemirror-model'
import {type Command, EditorState, NodeSelection, TextSelection} from 'prosemirror-state'
import {enterCommand, headingCommand, horizontalRuleCommand, listCommand} from '../src/editor/commands'
import {enterNewLine, textblockHandler} from '../src/editor/lines'
import {enterShortcut} from '../src/editor/rules'
import {parseMarkdown} from '../src/markdown/parse'
import {serializeMarkdown} from '../src/markdown/serialize'
import {schema} from '../src/schema'

const {doc: docType, paragraph, hard_break, wikilink, blockquote} = schema.nodes
const bullet = listCommand(schema.nodes.bullet_list)

/** A position in `doc` right after (or before) the first `text`. */
function at(doc: Node, text: string, where: 'after' | 'before' = 'after'): number {
  let found = -1
  doc.descendants((node, pos) => {
    if (found < 0 && node.isText && node.text!.includes(text)) {
      found = pos + node.text!.indexOf(text) + (where === 'after' ? text.length : 0)
    }
    return found < 0
  })
  if (found < 0) throw new Error(`not in the note: ${text}`)
  return found
}

function state(doc: Node, anchor: number, head = anchor): EditorState {
  return EditorState.create({doc, selection: TextSelection.create(doc, anchor, head)})
}

/** The state after `cmd`, which must apply. */
function run(cmd: Command, s: EditorState): EditorState {
  let next = s
  expect(cmd(s, (tr) => (next = s.apply(tr)))).toBe(true)
  return next
}

const markdown = (s: EditorState) => serializeMarkdown(s.doc).markdown
const br = () => hard_break.create()
const text = (t: string) => schema.text(t)
const p = (...content: Node[]) => paragraph.create(null, content)

describe('lines split off as the file reads them', () => {
  it('a selection that reaches only the start of a line leaves that line out', () => {
    const doc = parseMarkdown('one\ntwo\nthree\n')
    expect(markdown(run(bullet, state(doc, at(doc, 'one', 'before'), at(doc, 'two', 'before'))))).toBe('- one\n\ntwo\nthree\n')
  })

  it('a selection from a list item into lines keeps the item in it', () => {
    // The item's lines are not split: its end of the selection stays where it was.
    const doc = parseMarkdown('- milk\n  x\n\naa\nbb\n')
    const s = run(headingCommand(2), state(doc, at(doc, 'x', 'before'), at(doc, 'a')))
    expect(markdown(s)).toBe('- ## milk x\n\n## aa\n\nbb\n')
  })

  it('an empty line next to the line goes into the blank line between them', () => {
    const before = parseMarkdown('a\n\\\nx\n')
    expect(before.child(0).childCount).toBe(4)
    expect(markdown(run(bullet, state(before, at(before, 'x'))))).toBe('a\n\n- x\n')
    const after = parseMarkdown('x\n\\\nb\n')
    expect(markdown(run(bullet, state(after, at(after, 'x'))))).toBe('- x\n\nb\n')
    // A second empty line stays, an empty paragraph.
    const two = parseMarkdown('a\n\\\n\\\nx\n')
    const s = run(bullet, state(two, at(two, 'x')))
    expect(markdown(s)).toBe('a\n\n\n- x\n')
    expect(s.doc.child(0).childCount).toBe(1)
  })

  it('a selection that reaches only the start of the next paragraph leaves it out', () => {
    const doc = docType.create(null, [p(text('one')), p(text('two'), br(), text('three'))])
    const sel = () => state(doc, at(doc, 'one', 'before'), at(doc, 'two', 'before'))
    expect(markdown(run(headingCommand(1), sel()))).toBe('# one\n\ntwo\nthree\n')
    expect(markdown(run(horizontalRuleCommand, sel()))).toBe('one\n\n---\n\ntwo\nthree\n')
  })

  it("a selection over a paragraph's empty last line leaves no empty item", () => {
    const doc = docType.create(null, [p(text('one'), br()), p(text('two'))])
    expect(markdown(run(bullet, state(doc, at(doc, 'one', 'before'), at(doc, 'two'))))).toBe('- one\n- two\n')
  })

  it('a divider goes after the line the selection ends on, past an empty line', () => {
    const doc = parseMarkdown('one\ntwo\nthree\n')
    expect(markdown(run(horizontalRuleCommand, state(doc, at(doc, 'on'), at(doc, 'thr'))))).toBe('one\ntwo\nthree\n\n---\n')
    const gap = parseMarkdown('two\n\\\nthree\n')
    expect(markdown(run(horizontalRuleCommand, state(gap, at(gap, 'tw'))))).toBe('two\n\n---\n\nthree\n')
  })

  it('a divider on an empty line takes its place', () => {
    const doc = docType.create(null, [p(text('a'), br(), br(), br(), text('b'))])
    let s = run(horizontalRuleCommand, state(doc, at(doc, 'a') + 2))
    expect(markdown(s)).toBe('a\n\n---\n\nb\n')
    const last = docType.create(null, [p(text('a'), br())])
    s = run(horizontalRuleCommand, state(last, at(last, 'a') + 1))
    expect(markdown(s)).toBe('a\n\n---\n')
    expect(s.doc.child(0).childCount).toBe(1)
  })

  it('a divider after a selected last block has a paragraph after it for the cursor', () => {
    const doc = docType.create(null, [p(text('one')), schema.nodes.horizontal_rule.create()])
    const s = run(horizontalRuleCommand, EditorState.create({doc, selection: NodeSelection.create(doc, doc.content.size - 1)}))
    expect(s.doc.lastChild?.type).toBe(paragraph)
    expect(s.selection).toBeInstanceOf(TextSelection)
  })

  it('in a quote, extra empty lines stay as line breaks after the blank line', () => {
    const doc = docType.create(null, [blockquote.create(null, [p(text('x'), br(), br(), br(), text('b'))])])
    expect(markdown(state(doc, 0))).toBe('> x\n>\n> \\\n> b\n')
    expect(markdown(run(headingCommand(2), state(doc, at(doc, 'x'))))).toBe('> ## x\n>\n> \\\n> b\n')
  })

  it('the code block shortcut keeps links to notes, as text', () => {
    const doc = docType.create(null, [p(text('```js'), wikilink.create({target: 'Note'}), text(' is'))])
    // The rule's match, its last character (the space) still being typed.
    const match = '```js '.match(/```(js) $/)!
    const tr = textblockHandler(schema.nodes.code_block, (m) => ({language: m[1]}))(state(doc, 6), match, 1, 6)!
    expect(tr.doc.child(0).type).toBe(schema.nodes.code_block)
    expect(tr.doc.child(0).textContent).toBe('[[Note]] is')
  })
})

describe('Enter', () => {
  it('adds one newline to the file each time', () => {
    const doc = parseMarkdown('one\ntwo\n')
    let s = run(enterNewLine, state(doc, at(doc, 'one')))
    expect(markdown(s)).toBe('one\n\ntwo\n')
    s = run(enterNewLine, s)
    expect(markdown(s)).toBe('one\n\n\ntwo\n')
  })

  it('on an empty line after another leaves no empty line the file does not have', () => {
    const doc = docType.create(null, [p(text('a'), br(), br()), p(text('z'))])
    const s = run(enterNewLine, state(doc, at(doc, 'a') + 2))
    expect(markdown(s)).toBe('a\n\n\nz\n')
    expect(s.doc.child(0).childCount).toBe(1)
    expect(s.selection.$from.parent.content.size).toBe(0)
  })

  it('with a link to a note selected, breaks the line before it, and keeps it', () => {
    const doc = docType.create(null, [p(text('one'), wikilink.create({target: 'Note'}), text(' two'))])
    const s = run(enterCommand, EditorState.create({doc, selection: NodeSelection.create(doc, at(doc, 'one'))}))
    expect(markdown(s)).toBe('one\n[[Note]] two\n')
    expect(s.selection.$from.nodeAfter?.type).toBe(wikilink)
    // In a list, the next item starts with it.
    const item = parseMarkdown('- see[[Note]]\n')
    const inItem = run(enterCommand, EditorState.create({doc: item, selection: NodeSelection.create(item, at(item, 'see'))}))
    expect(markdown(inItem)).toBe('- see\n- [[Note]]\n')
  })

  it('over paragraphs replaces the selection with one newline', () => {
    const doc = parseMarkdown('onetwo\n\nthreefour\n')
    expect(markdown(run(enterCommand, state(doc, at(doc, 'one'), at(doc, 'three'))))).toBe('one\nfour\n')
  })

  it('over list items starts a new item, as in a list', () => {
    const doc = parseMarkdown('- milk\n- eggs\n')
    expect(markdown(run(enterCommand, state(doc, at(doc, 'mi'), at(doc, 'eg'))))).toBe('- mi\n- gs\n')
  })

  it('on an empty line in a quote keeps the new line, as a line break after the blank line', () => {
    const doc = docType.create(null, [blockquote.create(null, [p(text('a'), br(), br(), text('b'))])])
    expect(markdown(state(doc, 0))).toBe('> a\n>\n> b\n')
    const s = run(enterCommand, state(doc, at(doc, 'a') + 1))
    expect(markdown(s)).toBe('> a\n>\n> \\\n> b\n')
    expect(s.selection.$from.parentOffset).toBe(0)
    expect(s.selection.$from.nodeAfter?.type).toBe(hard_break)
  })

  it('after --- with lines below puts the cursor on a new line above them', () => {
    const doc = docType.create(null, [p(text('above'), br(), text('---'), br(), text('below'))])
    let s = run(enterShortcut, state(doc, at(doc, '---')))
    s = s.apply(s.tr.insertText('x'))
    expect(markdown(s)).toBe('above\n\n---\n\nx\nbelow\n')
    // An empty line under the --- stays.
    const gap = docType.create(null, [p(text('a'), br(), text('---'), br(), br(), text('below'))])
    s = run(enterShortcut, state(gap, at(gap, '---')))
    s = s.apply(s.tr.insertText('x'))
    expect(markdown(s)).toBe('a\n\n---\n\nx\n\nbelow\n')
  })
})
