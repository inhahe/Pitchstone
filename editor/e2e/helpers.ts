import {expect, type Page} from '@playwright/test'
import {pathToFileURL} from 'node:url'
import {join} from 'node:path'

export const PAGE = pathToFileURL(join(import.meta.dirname, '..', 'dist-test', 'index.html')).href

export interface Flush {
  dirty: boolean
  markdown: string
  title: string
  exact: boolean
}

export async function open(page: Page, markdown = '', opts: {shortcuts?: boolean; titles?: string[]; mode?: string} = {}) {
  await page.goto(PAGE)
  await page.evaluate(
    ([markdown, shortcuts, titles, mode]) => {
      window.inkdown.load(
        JSON.stringify({
          markdown,
          title: 'Test',
          mode,
          settings: {markdownShortcuts: shortcuts},
          noteTitles: titles,
          focus: 'end',
        }),
      )
    },
    [markdown, opts.shortcuts ?? true, opts.titles ?? [], opts.mode ?? 'edit'] as const,
  )
  if ((opts.mode ?? 'edit') === 'edit') await page.locator('.ProseMirror').focus()
}

export async function flush(page: Page): Promise<Flush> {
  return JSON.parse(await page.evaluate(() => window.inkdown.flush()))
}

/** The note's markdown after the edits so far (flushes them). */
export async function markdown(page: Page): Promise<string> {
  const f = await flush(page)
  expect(f.exact).toBe(true)
  return f.markdown
}

export async function messages(page: Page): Promise<{type: string; [k: string]: unknown}[]> {
  return page.evaluate(() => (window.__inkdownMessages ?? []) as never)
}

export async function command(page: Page, name: string, args?: object) {
  return page.evaluate(([n, a]) => window.inkdown.command(n, a ? JSON.stringify(a) : undefined), [name, args] as const)
}

/** Types text the way a soft keyboard with composition does: each word composed, then committed. */
export async function typeComposed(page: Page, text: string) {
  const cdp = await page.context().newCDPSession(page)
  for (const token of text.split(/(\s+)/)) {
    if (!token) continue
    if (/^\s+$/.test(token)) {
      await cdp.send('Input.insertText', {text: token})
      continue
    }
    // Letters are composed one by one, as Gboard does; punctuation is committed directly.
    let composing = ''
    for (const ch of token) {
      if (/[\p{L}\p{N}]/u.test(ch)) {
        composing += ch
        await cdp.send('Input.imeSetComposition', {text: composing, selectionStart: composing.length, selectionEnd: composing.length})
      } else {
        if (composing) await cdp.send('Input.insertText', {text: composing})
        composing = ''
        await cdp.send('Input.insertText', {text: ch})
      }
    }
    if (composing) await cdp.send('Input.insertText', {text: composing})
  }
  await cdp.detach()
}
