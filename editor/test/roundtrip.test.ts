import {describe, expect, it} from 'vitest'
import {parseMarkdown} from '../src/markdown/parse'
import {serializeMarkdown} from '../src/markdown/serialize'
import {schema} from '../src/schema'

const {doc, paragraph, heading, hard_break, bullet_list, list_item, blockquote} = schema.nodes
const s = (text: string, ...markNames: string[]) => schema.text(text, markNames.map((m) => schema.marks[m].create()))
const p = (...content: ReturnType<typeof s>[]) => paragraph.create(null, content)

/** Markdown -> doc -> markdown must be stable, and the second parse must equal the first. */
function roundTrip(md: string): string {
  const d1 = parseMarkdown(md)
  const r = serializeMarkdown(d1)
  expect(r.exact, `not exact for ${JSON.stringify(md)} -> ${JSON.stringify(r.markdown)}`).toBe(true)
  const d2 = parseMarkdown(r.markdown)
  expect(d2.toJSON()).toEqual(r.doc.toJSON())
  const r2 = serializeMarkdown(d2)
  expect(r2.markdown).toBe(r.markdown)
  return r.markdown
}

describe('markdown round trip', () => {
  it('keeps simple notes byte-identical', () => {
    for (const md of [
      '# Title\n\nSome **bold** and *italic* text.\n',
      '- a\n- b\n  - c\n',
      '1. one\n2. two\n',
      '- [ ] todo\n- [x] done\n',
      '> quote\n>\n> more\n',
      '```js\nconst a = 1\n```\n',
      'a ==highlight== and ~~strike~~\n',
      'Link to [[Other note]] and [[Other|alias]] and ![[image.png]]\n',
      '| a | b |\n| --- | :-: |\n| 1 | 2 |\n',
      'Line one\nline two\n',
      '---\ntags: [a, b]\n---\n# Doc\n',
      'Text[^1]\n\n[^1]: The note.\n',
      '[ref]: https://example.com\n',
      '<div>\nraw\n</div>\n',
      'para\n\n\n\nafter spacers\n',
      '#tag and C:\\Users\\path and snake_case_name\n',
    ]) {
      expect(roundTrip(md)).toBe(md)
    }
  })

  it('keeps the checkbox of a task with no text and a nested list', () => {
    expect(roundTrip('- [ ]\n  - child\n')).toBe('- [ ]\n  - child\n')
    const nested = bullet_list.create(null, [list_item.create(null, [p(s('child'))])])
    const d = doc.create(null, [bullet_list.create(null, [list_item.create({checked: true}, [paragraph.create(), nested])])])
    expect(serializeMarkdown(d).markdown).toBe('- [x]\n  - child\n')
  })

  it('treats typed markdown symbols as literal text', () => {
    const literal = [
      '**not bold**',
      '*not em*',
      '# not a heading',
      '- not a list',
      '1. not a list',
      '> not a quote',
      '[not](a link)',
      '[[not a wikilink]]',
      '`not code`',
      '~~not struck~~',
      '==not highlighted==',
      '<b>not html</b>',
      '&amp; stays',
      '---',
      '```',
      '  leading spaces',
      'trailing spaces  ',
      '\\* backslash',
      '[ ] not a task',
      '| a | b |',
    ]
    for (const text of literal) {
      const d = doc.create(null, [p(s(text))])
      const r = serializeMarkdown(d)
      expect(r.exact, text).toBe(true)
      const back = parseMarkdown(r.markdown)
      expect(back.toJSON(), `${text} -> ${r.markdown}`).toEqual(d.toJSON())
    }
  })

  it('serializes rich content', () => {
    const d = doc.create(null, [
      heading.create({level: 2}, [s('Head')]),
      p(s('a'), s('b', 'strong'), s('c', 'em', 'strong'), hard_break.create(), s('x')),
      bullet_list.create(null, [list_item.create(null, [p(s('one'))]), list_item.create({checked: true}, [p(s('two'))])]),
      blockquote.create(null, [p(s('q'))]),
    ])
    const r = serializeMarkdown(d)
    expect(r.exact).toBe(true)
    expect(parseMarkdown(r.markdown).eq(r.doc)).toBe(true)
  })
})
