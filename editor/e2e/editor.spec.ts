import {expect, test} from '@playwright/test'
import {command, FILE_PAGE, flush, markdown, messages, open, typeComposed} from './helpers'

test.describe('markdown shortcuts on', () => {
  const cases: [string, string, string][] = [
    ['heading', '# Heading', '# Heading\n'],
    ['bold', 'some **bold** text', 'some **bold** text\n'],
    ['italic', 'an *em* word', 'an *em* word\n'],
    ['underscore italic', 'an _em_ word', 'an *em* word\n'],
    ['strike', 'a ~~gone~~ b', 'a ~~gone~~ b\n'],
    ['highlight', 'a ==mark== b', 'a ==mark== b\n'],
    ['code', 'run `ls -la` now', 'run `ls -la` now\n'],
    ['bullet list', '- one', '- one\n'],
    ['ordered list', '3. three', '3. three\n'],
    ['task', '[ ] todo', '- [ ] todo\n'],
    ['quote', '> said', '> said\n'],
    ['wikilink', 'see [[Other note]] now', 'see [[Other note]] now\n'],
    ['wikilink alias', 'see [[Other|that]] now', 'see [[Other|that]] now\n'],
    ['link', 'a [site](https://example.com) b', 'a [site](https://example.com) b\n'],
    ['snake_case stays', 'snake_case_name', 'snake_case_name\n'],
    ['lone star stays', '2 * 3 = 6', '2 * 3 = 6\n'],
  ]
  for (const [name, input, expected] of cases) {
    test(`typing: ${name}`, async ({page}) => {
      await open(page)
      await page.keyboard.type(input)
      expect(await markdown(page)).toBe(expected)
    })
    test(`composing: ${name}`, async ({page}) => {
      await open(page)
      await typeComposed(page, input)
      expect(await markdown(page)).toBe(expected)
    })
  }

  test('--- then Enter makes a horizontal rule', async ({page}) => {
    await open(page, 'above\n')
    await page.keyboard.press('Enter')
    await page.keyboard.type('---')
    await page.keyboard.press('Enter')
    await page.keyboard.type('below')
    expect(await markdown(page)).toBe('above\n\n---\n\nbelow\n')
  })

  test('``` then Enter makes a code block, where markdown stays literal', async ({page}) => {
    await open(page)
    await page.keyboard.type('```js')
    await page.keyboard.press('Enter')
    await page.keyboard.type('let **a** = 1')
    expect(await markdown(page)).toBe('```js\nlet **a** = 1\n```\n')
  })

  test('Backspace right after a shortcut undoes it', async ({page}) => {
    await open(page)
    await page.keyboard.type('**bold**')
    await page.keyboard.press('Backspace')
    expect(await markdown(page)).toBe('\\*\\*bold\\*\\*\n')
  })

  test('Enter in a list continues it; Enter on an empty item ends it', async ({page}) => {
    await open(page)
    await page.keyboard.type('- a')
    await page.keyboard.press('Enter')
    await page.keyboard.type('b')
    await page.keyboard.press('Enter')
    await page.keyboard.press('Enter')
    await page.keyboard.type('after')
    expect(await markdown(page)).toBe('- a\n- b\n\nafter\n')
  })

  test('Enter after a task item makes an unchecked task', async ({page}) => {
    await open(page, '- [x] done\n')
    await page.keyboard.press('Enter')
    await page.keyboard.type('next')
    expect(await markdown(page)).toBe('- [x] done\n- [ ] next\n')
  })

  test('typed [[ suggests notes, and picking one inserts the link', async ({page}) => {
    await open(page, '', {titles: ['Alpha project', 'Beta', 'Gamma']})
    await page.keyboard.type('see [[alp')
    const suggest = page.locator('.suggest')
    await expect(suggest).toBeVisible()
    await expect(suggest.locator('.item').first()).toHaveText('Alpha project')
    await suggest.locator('.item').first().click()
    await page.keyboard.type(' ok')
    expect(await markdown(page)).toBe('see [[Alpha project]] ok\n')
  })

  test('picking a title with # in it links by its link name and shows the title', async ({page}) => {
    await open(page, '', {titles: ['C# notes']})
    await page.keyboard.type('[[c')
    await page.locator('.suggest .item').first().click()
    expect(await markdown(page)).toBe('[[C notes|C# notes]]\n')
  })

  test('links to missing notes are marked', async ({page}) => {
    await open(page, '[[Plan]] [[plan.md#Goals]] [[Nope]]\n', {titles: ['Plan']})
    await expect(page.locator('.ProseMirror .missing')).toHaveText(['Nope'])
  })

  test('Enter after a link followed by --- keeps the line', async ({page}) => {
    await open(page, '[[Note]]---\n')
    await page.keyboard.press('Enter')
    await page.keyboard.type('x')
    expect(await markdown(page)).toBe('[[Note]]---\n\nx\n')
  })

  test('pasting markdown formats it', async ({page}) => {
    await open(page)
    await page.evaluate(() => {
      const dt = new DataTransfer()
      dt.setData('text/plain', '# Pasted\n\n- **x**\n')
      document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}))
    })
    expect(await markdown(page)).toBe('# Pasted\n\n- **x**\n')
  })
})

test.describe('markdown shortcuts off', () => {
  test('typed symbols stay literal and are escaped', async ({page}) => {
    await open(page, '', {shortcuts: false})
    const text = '# not **bold** - [ ] [[x]] `c` ==h== ~~s~~'
    await page.keyboard.type(text)
    const md = await markdown(page)
    expect(md).not.toBe(text + '\n')
    // Reading it back shows exactly what was typed.
    await open(page, md, {shortcuts: false})
    await expect(page.locator('.ProseMirror p')).toHaveText(text)
    await expect(page.locator('.ProseMirror strong, .ProseMirror h1, .ProseMirror code, .ProseMirror .wikilink')).toHaveCount(0)
  })

  test('line-start symbols stay literal', async ({page}) => {
    await open(page, '', {shortcuts: false})
    for (const line of ['- a', '1. b', '> c', '---', '```']) {
      await page.keyboard.type(line)
      await page.keyboard.press('Enter')
    }
    const md = await markdown(page)
    await open(page, md, {shortcuts: false})
    // The empty paragraph left after the last Enter is a trailing blank line, which is not kept.
    await expect(page.locator('.ProseMirror > p')).toHaveText(['- a', '1. b', '> c', '---', '```'])
  })

  test('the toolbar still formats', async ({page}) => {
    await open(page, '', {shortcuts: false})
    await command(page, 'bold')
    await page.keyboard.type('strong')
    await command(page, 'bold')
    await page.keyboard.type(' *plain*')
    expect(await markdown(page)).toBe('**strong** \\*plain\\*\n')
  })

  test('pasting markdown keeps it literal', async ({page}) => {
    await open(page, '', {shortcuts: false})
    await page.evaluate(() => {
      const dt = new DataTransfer()
      dt.setData('text/plain', '**x**')
      document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}))
    })
    expect(await markdown(page)).toBe('\\*\\*x\\*\\*\n')
  })
})

test.describe('toolbar commands', () => {
  test('heading, lists, quote, code block toggle on and off', async ({page}) => {
    await open(page, 'text\n')
    await command(page, 'heading', {level: 2})
    expect(await markdown(page)).toBe('## text\n')
    await command(page, 'heading', {level: 2})
    expect(await markdown(page)).toBe('text\n')
    await command(page, 'bulletList')
    expect(await markdown(page)).toBe('- text\n')
    await command(page, 'orderedList')
    expect(await markdown(page)).toBe('1. text\n')
    await command(page, 'taskList')
    expect(await markdown(page)).toBe('- [ ] text\n')
    await command(page, 'taskList')
    expect(await markdown(page)).toBe('text\n')
    await command(page, 'blockquote')
    expect(await markdown(page)).toBe('> text\n')
    await command(page, 'blockquote')
    await command(page, 'codeBlock')
    expect(await markdown(page)).toBe('```\ntext\n```\n')
    await command(page, 'codeBlock')
    expect(await markdown(page)).toBe('text\n')
  })

  test('a code block keeps links to notes and inline HTML, as markdown', async ({page}) => {
    await open(page, 'See [[Plan|the plan]] and <b>x</b>\n')
    await command(page, 'codeBlock')
    expect(await markdown(page)).toBe('```\nSee [[Plan|the plan]] and <b>x</b>\n```\n')
  })

  test('code blocks made across a list keep links too', async ({page}) => {
    await open(page, 'a [[X]]\n\n- b [[Y]]\n')
    await page.keyboard.press('Control+a')
    await command(page, 'codeBlock')
    expect(await markdown(page)).toBe('```\na [[X]]\n```\n\n- ```\n  b [[Y]]\n  ```\n')
  })

  test('code blocks made across a table leave the links in its cells alone', async ({page}) => {
    await open(page, 'a [[X]]\n\n| [[Plan]] | ![i](x.png) |\n| --- | --- |\n| c | d |\n')
    await page.keyboard.press('Control+a')
    await command(page, 'codeBlock')
    expect(await markdown(page)).toBe('```\na [[X]]\n```\n\n| [[Plan]] | ![i](x.png) |\n| --- | --- |\n| c | d |\n')
  })

  test('find marks the right text after characters whose lower case is longer', async ({page}) => {
    await open(page, 'İİ ab Ab\n')
    await page.evaluate(() => window.pitchstone.find('ab'))
    await expect(page.locator('.ProseMirror .find-match')).toHaveText(['ab', 'Ab'])
  })

  test('indent and outdent list items', async ({page}) => {
    await open(page, '- a\n- b\n')
    await command(page, 'indent')
    expect(await markdown(page)).toBe('- a\n  - b\n')
    await command(page, 'outdent')
    expect(await markdown(page)).toBe('- a\n- b\n')
  })

  test('setLink links the selection', async ({page}) => {
    await open(page, 'word\n')
    await page.keyboard.press('Control+a')
    await command(page, 'setLink', {text: 'word', href: 'https://example.com'})
    expect(await markdown(page)).toBe('[word](https://example.com)\n')
  })

  test('insertTable and add a row', async ({page}) => {
    await open(page)
    await command(page, 'insertTable', {rows: 2, cols: 2})
    await page.keyboard.type('h1')
    await page.keyboard.press('Tab')
    await page.keyboard.type('h2')
    await page.keyboard.press('Tab')
    await page.keyboard.type('c1')
    expect(await markdown(page)).toBe('| h1 | h2 |\n| --- | --- |\n| c1 |  |\n')
  })

  test('undo and redo', async ({page}) => {
    await open(page)
    await page.keyboard.type('hello')
    await command(page, 'undo')
    expect(await markdown(page)).toBe('')
    await command(page, 'redo')
    expect(await markdown(page)).toBe('hello\n')
  })

  test('toolbar state follows the cursor', async ({page}) => {
    await open(page, '**bold** plain\n')
    // Moves the cursor into "bold" the way a tap does. (Arrow keys right after loading are
    // sometimes ignored by the emulated mobile browser.)
    await page.evaluate(() => getSelection()!.collapse(document.querySelector('.ProseMirror strong')!.firstChild, 1))
    // The editor reads the moved DOM selection when the browser reports it, a little later.
    const strong = async () => {
      const states = (await messages(page)).filter((m) => m.type === 'state')
      return (states.at(-1)?.state as {strong: boolean} | undefined)?.strong
    }
    await expect.poll(strong).toBe(true)
  })
})

test.describe('saving', () => {
  test('loading a note is not an edit', async ({page}) => {
    await open(page, '# Untouched *note*\n\nWith odd   spacing and a [ref][1].\n\n[1]: https://x.com\n')
    const f = await flush(page)
    expect(f.dirty).toBe(false)
  })

  test('edits are reported as markdown after a pause, made in the worker', async ({page}) => {
    await open(page)
    await page.keyboard.type('autosaved')
    await expect.poll(async () => (await messages(page)).filter((m) => m.type === 'changed').at(-1)?.markdown).toBe('autosaved\n')
    expect(page.workers()).toHaveLength(1)
    expect((await flush(page)).dirty).toBe(false)
  })

  test('without a worker (a file:// page), edits are reported all the same', async ({page}) => {
    await open(page, '', {url: FILE_PAGE})
    await page.keyboard.type('autosaved')
    await expect.poll(async () => (await messages(page)).filter((m) => m.type === 'changed').at(-1)?.markdown).toBe('autosaved\n')
    expect(page.workers()).toHaveLength(0)
  })

  test('a version made in the worker never follows a newer one', async ({page}) => {
    await open(page, Array.from({length: 300}, (_, i) => `Paragraph **${i}** with [[Link ${i}]].`).join('\n\n') + '\n')
    // As soon as autosave hands the note to the worker, and before the worker can answer (a
    // microtask runs before any message): edit, flush (the newest version reaches the app),
    // and edit again.
    const flushing = page.evaluate(
      () =>
        new Promise<{flushed: string; before: number}>((resolve) => {
          const post = Worker.prototype.postMessage
          Worker.prototype.postMessage = function (this: Worker, ...args: Parameters<Worker['postMessage']>) {
            Worker.prototype.postMessage = post
            post.apply(this, args)
            queueMicrotask(() => {
              window.pitchstone.command('insertWikilink', JSON.stringify({target: 'Newest'}))
              const flushed = window.pitchstone.flush()
              const before = (window.__pitchstoneMessages ?? []).filter((m) => m.type === 'changed').length
              window.pitchstone.command('insertWikilink', JSON.stringify({target: 'After'}))
              resolve({flushed, before})
            })
          } as Worker['postMessage']
        }),
    )
    await page.keyboard.type('x')
    const {flushed, before} = await flushing
    expect(JSON.parse(flushed).markdown).toContain('[[Newest]]')
    const changed = async () => (await messages(page)).filter((m) => m.type === 'changed').map((m) => m.markdown as string)
    await expect.poll(async () => (await changed()).at(-1)).toContain('[[After]]')
    // Nothing older than the flushed version came after it.
    for (const md of (await changed()).slice(before)) expect(md).toContain('[[Newest]]')
  })

  test('a slow worker still saves while typing goes on in bursts', async ({page}) => {
    await open(page, 'start\n')
    // As if the worker took 1.2 s per note: each request reaches it that much later.
    await page.evaluate(() => {
      const post = Worker.prototype.postMessage
      Worker.prototype.postMessage = function (this: Worker, ...args: Parameters<Worker['postMessage']>) {
        setTimeout(() => post.apply(this, args), 1200)
      } as Worker['postMessage']
    })
    // Pauses just over the autosave delay, for 4 s.
    for (let i = 0; i < 8; i++) {
      await page.keyboard.type('x')
      await page.waitForTimeout(500)
    }
    expect((await messages(page)).filter((m) => m.type === 'changed').length).toBeGreaterThanOrEqual(2)
  })

  test('edits made while the worker runs stay pending, and are reported after', async ({page}) => {
    await open(page, Array.from({length: 300}, (_, i) => `Paragraph ${i}.`).join('\n\n') + '\n')
    await page.keyboard.type('a')
    await page.evaluate(async () => {
      await new Promise((resolve) => setTimeout(resolve, 450))
      window.pitchstone.command('insertWikilink', JSON.stringify({target: 'Later'}))
    })
    await expect.poll(async () => (await messages(page)).filter((m) => m.type === 'changed').at(-1)?.markdown).toContain('a[[Later]]')
    expect((await flush(page)).dirty).toBe(false)
  })

  test('title edits are reported', async ({page}) => {
    await open(page, 'body\n')
    await page.locator('#title').fill('New title')
    const f = await flush(page)
    expect(f.dirty).toBe(true)
    expect(f.title).toBe('New title')
  })
})

test.describe('modes', () => {
  test('source mode edits raw markdown', async ({page}) => {
    await open(page, '# A\n')
    await page.evaluate(() => window.pitchstone.setMode('source'))
    const source = page.locator('#source')
    await expect(source).toBeVisible()
    await expect(source).toHaveValue('# A\n')
    await source.fill('# B\n\n**x**\n')
    expect((await flush(page)).markdown).toBe('# B\n\n**x**\n')
    await page.evaluate(() => window.pitchstone.setMode('edit'))
    await expect(page.locator('.ProseMirror h1')).toHaveText('B')
    await expect(page.locator('.ProseMirror strong')).toHaveText('x')
  })

  test('reading mode opens links and toggles tasks', async ({page}) => {
    await open(page, '[site](https://example.com) and [[Note]]\n\n- [ ] task\n', {mode: 'read'})
    await page.locator('.ProseMirror a').click()
    await page.locator('.ProseMirror .wikilink').click()
    await page.locator('.ProseMirror .checkbox').click()
    const msgs = await messages(page)
    expect(msgs).toContainEqual({type: 'openLink', href: 'https://example.com'})
    expect(msgs).toContainEqual({type: 'openNote', target: 'Note'})
    expect(await markdown(page)).toBe('[site](https://example.com) and [[Note]]\n\n- [x] task\n')
  })

  test('editing mode shows a bubble to open a link', async ({page}) => {
    await open(page, 'go to [site](https://example.com)\n')
    await page.locator('.ProseMirror a').click()
    const bubble = page.locator('.bubble')
    await expect(bubble).toBeVisible()
    await bubble.getByText('Open').click()
    expect(await messages(page)).toContainEqual({type: 'openLink', href: 'https://example.com'})
  })
})

test.describe('links into a note', () => {
  test('scroll to a heading or a block', async ({page}) => {
    const filler = Array.from({length: 80}, (_, i) => `Paragraph ${i}.`).join('\n\n')
    await open(page, `${filler}\n\n## Far: away\n\nA block ^blk-1\n\n${filler}\n`)
    const scroll = (anchor: string) => page.evaluate((a) => window.pitchstone.scrollToAnchor(a), anchor)
    const top = (selector: string) => page.evaluate((s) => document.querySelector(s)!.getBoundingClientRect().top, selector)
    expect(await scroll('Top#Far away')).toBe(true)
    expect(Math.abs(await top('.ProseMirror h2'))).toBeLessThan(5)
    expect(await scroll('^BLK-1')).toBe(true)
    expect(Math.abs(await top('.ProseMirror h2 + p'))).toBeLessThan(5)
    expect(await scroll('Nowhere')).toBe(false)
    expect(await scroll('^')).toBe(false)
  })
})
