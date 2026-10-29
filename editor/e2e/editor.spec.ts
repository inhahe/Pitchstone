import {expect, type Page, test} from '@playwright/test'
import {command, compose, FILE_PAGE, flush, markdown, messages, open, PNG, servePictures, typeComposed} from './helpers'

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

  test("the keyboard's Enter picks the highlighted suggestion", async ({page}) => {
    await open(page, '', {titles: ['Alpha project', 'Beta', 'Gamma']})
    await page.keyboard.type('see [[alp')
    await expect(page.locator('.suggest .item').first()).toHaveText('Alpha project')
    await page.keyboard.press('Enter')
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

  test('a link to an attachment is marked only if it is not stored', async ({page}) => {
    await open(page, '[[Report.PDF]] [[files/talk.m4a]] [[gone.pdf]]\n', {attachments: ['report.pdf', 'talk.m4a']})
    await expect(page.locator('.ProseMirror .missing')).toHaveText(['gone.pdf'])
  })

  test('Enter after a link followed by --- keeps the line', async ({page}) => {
    await open(page, '[[Note]]---\n')
    await page.keyboard.press('Enter')
    await page.keyboard.type('x')
    expect(await markdown(page)).toBe('[[Note]]---\nx\n')
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
    // Lines of one paragraph; the line break left after the last Enter, at its end, is not kept.
    await expect(page.locator('.ProseMirror > p')).toHaveCount(1)
    expect(await page.locator('.ProseMirror > p').evaluate((p) => (p as HTMLElement).innerText)).toBe('- a\n1. b\n> c\n---\n```')
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
  // A soft keyboard holds the word being typed as a composition (underlined) until a space, and
  // goes on writing that word, past whatever a command did, unless the composition ends: then
  // the next key starts a new word, typed with what the command set. So a button pressed in the
  // middle of a word must end its composition, with the compositionend that tells the keyboard.

  /** Runs `act` while a word is being composed; whether the composition ended meanwhile. */
  async function compositionEnds(page: Page, act: () => Promise<unknown>): Promise<boolean> {
    await page.evaluate(() => {
      const w = window as unknown as {ended: boolean}
      w.ended = false
      document.querySelector('.ProseMirror')!.addEventListener('compositionend', () => (w.ended = true), {once: true})
    })
    await act()
    return page.evaluate(() => (window as unknown as {ended: boolean}).ended)
  }

  test('formatting turned on while a word is being typed applies to what is typed next', async ({page}) => {
    await open(page)
    await command(page, 'italic')
    await compose(page, 'word')
    expect(await compositionEnds(page, async () => {
      await command(page, 'italic')
      await command(page, 'strike')
    })).toBe(true)
    await compose(page, 'next', true)
    expect(await markdown(page)).toBe('*word*~~next~~\n')
  })

  test('italics turned off while a word is being typed stop', async ({page}) => {
    await open(page)
    await command(page, 'italic')
    await compose(page, 'word')
    expect(await compositionEnds(page, () => command(page, 'italic'))).toBe(true)
    await compose(page, 'plain', true)
    expect(await markdown(page)).toBe('*word*plain\n')
  })

  test('a command in the middle of a word being typed keeps the word', async ({page}) => {
    await open(page)
    await compose(page, 'word')
    expect(await compositionEnds(page, () => command(page, 'heading', {level: 1}))).toBe(true)
    await compose(page, 'more', true)
    expect(await markdown(page)).toBe('# wordmore\n')
  })

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

test.describe('pictures stored with notes', () => {
  test('embeds and image paths show the stored picture, sized, or a box when missing', async ({page}) => {
    await servePictures(page, ['pic.png', 'my pic.png'])
    await open(page, '![[Pic.png]] and ![[pic.png|120]] and ![[gone.png]] and ![x](assets/my%20pic.png)\n')
    const embeds = page.locator('.ProseMirror .embed-image img')
    await expect(embeds).toHaveCount(3)
    await expect.poll(() => embeds.nth(0).evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1)
    await expect(embeds.nth(1)).toHaveAttribute('width', '120')
    await expect(page.locator('.ProseMirror .embed-image.broken')).toHaveAttribute('data-alt', 'gone.png')
    const markdownImage = page.locator('.ProseMirror .image:not(.embed-image) img')
    await expect(markdownImage).toHaveAttribute('src', '/attachments/my%20pic.png')
    await expect.poll(() => markdownImage.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1)
    // Pictures are files, not notes: none is marked as a missing note.
    await expect(page.locator('.ProseMirror .missing')).toHaveCount(0)
    expect((await flush(page)).dirty).toBe(false)
  })

  test('a pasted picture goes to the app, not into the text', async ({page}) => {
    await open(page, 'text\n')
    await page.evaluate((b64) => {
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
      const dt = new DataTransfer()
      dt.items.add(new File([bytes], 'image.png', {type: 'image/png'}))
      document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}))
    }, PNG.toString('base64'))
    await expect.poll(async () => (await messages(page)).find((m) => m.type === 'pasteImage')).toMatchObject({
      type: 'pasteImage',
      mime: 'image/png',
      name: 'image.png',
      data: PNG.toString('base64'),
    })
    // Nothing went into the text: the app inserts the picture once it has stored it.
    expect((await flush(page)).dirty).toBe(false)
  })

  test('a pasted picture too large to store is not read, and the app is told', async ({page}) => {
    await open(page, 'text\n')
    await page.evaluate(() => {
      const dt = new DataTransfer()
      dt.items.add(new File([new Uint8Array(25_000_001)], 'huge.png', {type: 'image/png'}))
      document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true}))
    })
    await expect.poll(async () => (await messages(page)).find((m) => m.type === 'warning')).toMatchObject({
      code: 'imageTooLarge',
      message: 'huge.png is larger than 25 MB, the most a pasted picture can be.',
    })
    expect((await messages(page)).some((m) => m.type === 'pasteImage')).toBe(false)
  })

  test("the app's answer embeds the picture where the cursor is", async ({page}) => {
    await servePictures(page, ['Pasted image 1.png'])
    await open(page, 'before\n')
    await command(page, 'insertWikilink', {target: 'Pasted image 1.png', embed: true})
    expect(await markdown(page)).toBe('before![[Pasted image 1.png]]\n')
    await expect.poll(() => page.locator('.ProseMirror .embed-image img').evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1)
  })
})

test.describe('Enter starts a new line, as in Obsidian', () => {
  /**
   * Puts the caret right after (or before) the first `text` in the note, as a tap does, and waits
   * until the editor has taken it up.
   *
   * Just after the editor gains focus, ProseMirror puts its own selection back over one the page
   * sets without a tap or a click: at the start of the document for 200 ms, anywhere until its
   * focus timer has run. So the caret is placed again until it stays.
   */
  async function caret(page: Page, text: string, where: 'after' | 'before' = 'after') {
    for (let attempt = 1; ; attempt++) {
      const stayed = await page.evaluate(
        ([t, w]) =>
          new Promise<boolean>((resolve, reject) => {
            const editor = document.querySelector('.ProseMirror')!
            // A point as the text and line breaks before it: the editor may write the same
            // point another way (in the paragraph rather than its text, say).
            const place = (node: Node, offset: number) => {
              const r = document.createRange()
              r.setStart(editor, 0)
              r.setEnd(node, offset)
              return `${r.toString().length}:${r.cloneContents().querySelectorAll('br').length}`
            }
            const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT)
            for (let n = walker.nextNode(); n; n = walker.nextNode()) {
              const i = n.textContent!.indexOf(t)
              if (i < 0) continue
              const offset = w === 'after' ? i + t.length : i
              const target = place(n, offset)
              const sel = getSelection()!
              const there = () => sel.rangeCount > 0 && sel.isCollapsed && place(sel.anchorNode!, sel.anchorOffset) === target
              // There already: no change to report.
              if (there()) return resolve(true)
              // The report of this change, not one still pending from before, and after the
              // editor's own listener, which was there first.
              const asked = performance.now()
              const reported = (e: Event) => {
                if (e.timeStamp < asked) return
                document.removeEventListener('selectionchange', reported)
                setTimeout(() => resolve(there()), 0)
              }
              document.addEventListener('selectionchange', reported)
              setTimeout(() => reject(new Error('the caret move was never reported')), 2000)
              sel.collapse(n, offset)
              return
            }
            reject(new Error('not in the note: ' + t))
          }),
        [text, where] as const,
      )
      if (stayed) return
      if (attempt === 5) throw new Error(`the caret would not stay ${where} ${text}`)
    }
  }

  /** The caret's top, in pixels. */
  const caretTop = (page: Page) => page.evaluate(() => getSelection()!.getRangeAt(0).getBoundingClientRect().top)

  test('Enter moves the cursor down one line, in the same paragraph', async ({page}) => {
    await open(page)
    await page.keyboard.type('one')
    const before = await caretTop(page)
    await page.keyboard.press('Enter')
    await page.keyboard.type('two')
    const moved = (await caretTop(page)) - before
    const lineHeight = await page.locator('.ProseMirror p').evaluate((p) => parseFloat(getComputedStyle(p).lineHeight))
    expect(Math.abs(moved - lineHeight)).toBeLessThan(2)
    expect(await markdown(page)).toBe('one\ntwo\n')
    await expect(page.locator('.ProseMirror > p')).toHaveCount(1)
  })

  test('Enter on an empty line makes a blank line: a new paragraph', async ({page}) => {
    await open(page)
    await page.keyboard.type('one')
    await page.keyboard.press('Enter')
    await page.keyboard.press('Enter')
    await page.keyboard.type('two')
    expect(await markdown(page)).toBe('one\n\ntwo\n')
    await expect(page.locator('.ProseMirror > p')).toHaveText(['one', 'two'])
  })

  test("Enter right after the keyboard changes the word before it still starts a line", async ({page}) => {
    await open(page, 'b\n')
    // What Gboard does on Enter after "b", as recorded on an emulator: it replaces the word with
    // "B" (a deletion, which ProseMirror reads only 20 ms later, and an insertion), then sends
    // Enter. Each input is the browser's own unless cancelled, as there.
    await page.evaluate(() => {
      const editor = document.querySelector('.ProseMirror')!
      const input = (inputType: string) =>
        editor.dispatchEvent(new InputEvent('beforeinput', {inputType, bubbles: true, cancelable: true}))
      const key = (key: string, keyCode: number) =>
        editor.dispatchEvent(new KeyboardEvent('keydown', {key, keyCode, bubbles: true, cancelable: true} as KeyboardEventInit))
      key('Unidentified', 229)
      if (input('deleteContentBackward')) document.execCommand('delete')
      key('Unidentified', 229)
      if (input('insertText')) document.execCommand('insertText', false, 'B')
      key('Enter', 13)
      if (input('insertParagraph')) document.execCommand('insertParagraph')
    })
    // A line break, not the browser's paragraph break, and the cursor after it.
    await expect(page.locator('.ProseMirror > p')).toHaveCount(1)
    await page.keyboard.type('c')
    await expect.poll(() => markdown(page)).toBe('B\nc\n')
  })

  test("Enter after a deletion that changed nothing keeps its line break", async ({page}) => {
    await open(page, 'ab\n')
    await page.evaluate(() => {
      const editor = document.querySelector('.ProseMirror')!
      // A keyboard deletion the browser did not carry out (ProseMirror then presses Backspace
      // itself, 50 ms on, unless the page has changed meanwhile)...
      editor.dispatchEvent(new InputEvent('beforeinput', {inputType: 'deleteContentBackward', bubbles: true, cancelable: true}))
      // ...then the keyboard's Enter, the browser's own unless cancelled.
      editor.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, bubbles: true} as KeyboardEventInit))
      const enter = new InputEvent('beforeinput', {inputType: 'insertParagraph', bubbles: true, cancelable: true})
      if (editor.dispatchEvent(enter)) document.execCommand('insertParagraph')
      return new Promise((resolve) => setTimeout(resolve, 150))
    })
    await page.keyboard.type('c')
    expect(await markdown(page)).toBe('ab\nc\n')
  })

  test('a selection change just after Enter does not press Enter again', async ({page}) => {
    await open(page, 'ab\n')
    await page.evaluate(() => {
      const editor = document.querySelector('.ProseMirror')!
      editor.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, bubbles: true} as KeyboardEventInit))
      const enter = new InputEvent('beforeinput', {inputType: 'insertParagraph', bubbles: true, cancelable: true})
      if (editor.dispatchEvent(enter)) document.execCommand('insertParagraph')
      // The keyboard moving the cursor at once, as it may after the text changes (not to the
      // note's start, which ProseMirror would undo just after focus).
      getSelection()!.collapse(editor.querySelector('p')!.firstChild!, 1)
      return new Promise((resolve) => setTimeout(resolve, 150))
    })
    await expect(page.locator('.ProseMirror > p')).toHaveCount(1)
    await expect(page.locator('.ProseMirror p br:not(.ProseMirror-trailingBreak)')).toHaveCount(1)
  })

  test('Enter in the middle of a line breaks it', async ({page}) => {
    await open(page, 'onetwo\n')
    await caret(page, 'one')
    await page.keyboard.press('Enter')
    expect(await markdown(page)).toBe('one\ntwo\n')
  })

  test('Enter at the end of a line adds an empty line below, and Enter there separates paragraphs', async ({page}) => {
    await open(page, 'one\ntwo\n')
    await caret(page, 'one')
    await page.keyboard.press('Enter')
    await page.keyboard.type('new')
    expect(await markdown(page)).toBe('one\nnew\ntwo\n')
    await page.keyboard.press('Backspace')
    await page.keyboard.press('Backspace')
    await page.keyboard.press('Backspace')
    // As on Android, the editor reads the deletions from the page, a moment later. The empty
    // line is a blank line in the file, as Obsidian writes it.
    await expect.poll(() => markdown(page)).toBe('one\n\ntwo\n')
    await page.keyboard.press('Enter')
    // The cursor is on a line of its own between the two paragraphs.
    await expect(page.locator('.ProseMirror > p')).toHaveText(['one', '', 'two'])
    await page.keyboard.type('middle')
    expect(await markdown(page)).toBe('one\n\nmiddle\n\ntwo\n')
  })

  test('Backspace at the start of a paragraph removes the blank line, then the line break', async ({page}) => {
    await open(page, 'one\n\ntwo\n')
    await caret(page, 'two', 'before')
    await page.keyboard.press('Backspace')
    expect(await markdown(page)).toBe('one\ntwo\n')
    await page.keyboard.press('Backspace')
    expect(await markdown(page)).toBe('onetwo\n')
  })

  test('Delete at the end of a paragraph removes the blank line after it', async ({page}) => {
    await open(page, 'one\n\ntwo\n')
    await caret(page, 'one')
    await page.keyboard.press('Delete')
    expect(await markdown(page)).toBe('one\ntwo\n')
  })

  test('markdown typed at the start of a line formats that line', async ({page}) => {
    const cases: [string, string, string][] = [
      ['- ', 'milk', 'Groceries\n\n- milk\n'],
      ['1. ', 'first', 'Groceries\n\n1. first\n'],
      ['# ', 'Title', 'Groceries\n\n# Title\n'],
      ['> ', 'quoted', 'Groceries\n\n> quoted\n'],
      ['[ ] ', 'task', 'Groceries\n\n- [ ] task\n'],
    ]
    for (const [syntax, text, expected] of cases) {
      await open(page)
      await page.keyboard.type('Groceries')
      await page.keyboard.press('Enter')
      await page.keyboard.type(syntax + text)
      expect(await markdown(page), syntax).toBe(expected)
    }
  })

  test('--- and a code fence on a line of their own, then Enter, still make a rule and a code block', async ({page}) => {
    await open(page)
    await page.keyboard.type('above')
    await page.keyboard.press('Enter')
    await page.keyboard.type('---')
    await page.keyboard.press('Enter')
    await page.keyboard.type('below')
    await page.keyboard.press('Enter')
    await page.keyboard.type('```js')
    await page.keyboard.press('Enter')
    await page.keyboard.type('let a = 1')
    expect(await markdown(page)).toBe('above\n\n---\n\nbelow\n\n```js\nlet a = 1\n```\n')
  })

  test('two Enters leave a quote', async ({page}) => {
    await open(page)
    await page.keyboard.type('> quoted')
    await page.keyboard.press('Enter')
    await page.keyboard.type('more')
    await page.keyboard.press('Enter')
    await page.keyboard.press('Enter')
    await page.keyboard.type('after')
    expect(await markdown(page)).toBe('> quoted\n> more\n\nafter\n')
  })

  test('a shortcut typed at the start of a line makes only that line the block', async ({page}) => {
    await open(page, 'Groceries\nmilk\neggs\n')
    await caret(page, 'milk', 'before')
    await page.keyboard.type('# ')
    expect(await markdown(page)).toBe('Groceries\n\n# milk\n\neggs\n')
  })

  test('a shortcut typed at the start of a paragraph makes only its first line the block', async ({page}) => {
    await open(page, 'Title\nbody\n')
    await caret(page, 'Title', 'before')
    await page.keyboard.type('# ')
    expect(await markdown(page)).toBe('# Title\n\nbody\n')
  })

  test('a number other than 1 after a line break is not a list, as in markdown', async ({page}) => {
    await open(page)
    await page.keyboard.type('Born in')
    await page.keyboard.press('Enter')
    await page.keyboard.type('1990. Then')
    await expect(page.locator('.ProseMirror ol')).toHaveCount(0)
    await expect(page.locator('.ProseMirror > p')).toHaveCount(1)
  })

  test('the toolbar formats an empty line between lines, where the cursor is', async ({page}) => {
    await open(page, 'one\ntwo\n')
    await caret(page, 'one')
    await page.keyboard.press('Enter')
    await command(page, 'bulletList')
    await page.keyboard.type('x')
    expect(await markdown(page)).toBe('one\n\n- x\n\ntwo\n')
  })

  test('a divider goes after the cursor line, and splits nothing before it', async ({page}) => {
    await open(page, 'one\ntwo\nthree\n')
    await caret(page, 'two')
    await command(page, 'horizontalRule')
    expect(await markdown(page)).toBe('one\ntwo\n\n---\n\nthree\n')
  })

  test('a heading in a list item keeps its lines apart, as words', async ({page}) => {
    await open(page, '- milk\n  eggs\n')
    await caret(page, 'milk')
    await command(page, 'heading', {level: 2})
    expect(await markdown(page)).toBe('- ## milk eggs\n')
  })

  test('in a list item, a shortcut after a line break stays text', async ({page}) => {
    for (const syntax of ['# ', '- ', '> ', '[ ] ']) {
      await open(page, '- milk\n  eggs\n')
      await caret(page, 'eggs', 'before')
      await page.keyboard.type(syntax)
      // One paragraph still: the line break shows in the text as nothing.
      await expect(page.locator('.ProseMirror li > p'), syntax).toHaveText([`milk${syntax}eggs`])
      await expect(page.locator('.ProseMirror li'), syntax).toHaveCount(1)
      await expect(page.locator('.ProseMirror h1, .ProseMirror blockquote'), syntax).toHaveCount(0)
    }
  })

  test('a heading typed at the start of a list item keeps its lines apart, as words', async ({page}) => {
    await open(page, '- milk\n  eggs\n')
    await caret(page, 'milk', 'before')
    await page.keyboard.type('# ')
    expect(await markdown(page)).toBe('- # milk eggs\n')
  })

  test('the toolbar formats the line the cursor is on, not the whole paragraph', async ({page}) => {
    await open(page, 'one\ntwo\nthree\n')
    await caret(page, 'two')
    await command(page, 'heading', {level: 2})
    expect(await markdown(page)).toBe('one\n\n## two\n\nthree\n')
    await open(page, 'one\ntwo\nthree\n')
    await caret(page, 'two')
    await command(page, 'bulletList')
    expect(await markdown(page)).toBe('one\n\n- two\n\nthree\n')
  })

  test('with shortcuts off, Enter still starts a line, and line-start symbols stay text', async ({page}) => {
    await open(page, '', {shortcuts: false})
    await page.keyboard.type('Groceries')
    await page.keyboard.press('Enter')
    await page.keyboard.type('- milk')
    const md = await markdown(page)
    await open(page, md, {shortcuts: false})
    await expect(page.locator('.ProseMirror > p')).toHaveCount(1)
    expect(await page.locator('.ProseMirror > p').evaluate((p) => (p as HTMLElement).innerText)).toBe('Groceries\n- milk')
  })
})
