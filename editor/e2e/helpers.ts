import {expect, type Page} from '@playwright/test'
import {pathToFileURL} from 'node:url'
import {join} from 'node:path'
import {linkKey} from '../src/editor/links'

const DIST = join(import.meta.dirname, '..', 'dist-test')
const ORIGIN = 'http://pitchstone.test'
/**
 * The page, served from an http origin (routed to dist-test) as the app serves it from its own:
 * a file:// page cannot start the autosave worker.
 */
export const PAGE = `${ORIGIN}/index.html`
/** The page as a file, where it autosaves without a worker. */
export const FILE_PAGE = pathToFileURL(join(DIST, 'index.html')).href

export interface Flush {
  dirty: boolean
  markdown: string
  title: string
  exact: boolean
}

/**
 * Removes the web APIs that the oldest WebView the app supports (Chrome 90, the target in
 * build.mjs) lacks: esbuild lowers syntax for it but adds no polyfills, so a call to one of
 * these would throw on such a phone, and should throw in the tests too.
 */
function asChrome90() {
  const drop = (owner: object, name: string) => {
    delete (owner as Record<string, unknown>)[name]
  }
  drop(globalThis, 'structuredClone') // Chrome 98
  drop(Object, 'hasOwn') // 93
  drop(Array.prototype, 'at') // 92
  drop(String.prototype, 'at') // 92
  drop(Crypto.prototype, 'randomUUID') // 92
  drop(Array.prototype, 'findLast') // 97
  drop(Array.prototype, 'findLastIndex') // 97
  for (const name of ['toSorted', 'toReversed', 'toSpliced', 'with']) drop(Array.prototype, name) // 110
}

export async function open(
  page: Page,
  markdown = '',
  opts: {shortcuts?: boolean; titles?: string[]; mode?: string; url?: string} = {},
) {
  await page.addInitScript(asChrome90)
  await page.route(`${ORIGIN}/**`, (route) => route.fulfill({path: join(DIST, new URL(route.request().url()).pathname)}))
  await page.goto(opts.url ?? PAGE)
  await page.evaluate(
    ([markdown, shortcuts, titles, keys, mode]) => {
      window.pitchstone.load(
        JSON.stringify({
          markdown,
          title: 'Test',
          mode,
          settings: {markdownShortcuts: shortcuts},
          notes: {titles, keys},
          focus: 'end',
        }),
      )
    },
    [markdown, opts.shortcuts ?? true, opts.titles ?? [], (opts.titles ?? []).map(linkKey), opts.mode ?? 'edit'] as const,
  )
  if ((opts.mode ?? 'edit') === 'edit') await page.locator('.ProseMirror').focus()
}

export async function flush(page: Page): Promise<Flush> {
  return JSON.parse(await page.evaluate(() => window.pitchstone.flush()))
}

/** The note's markdown after the edits so far (flushes them). */
export async function markdown(page: Page): Promise<string> {
  const f = await flush(page)
  expect(f.exact).toBe(true)
  return f.markdown
}

export async function messages(page: Page): Promise<{type: string; [k: string]: unknown}[]> {
  return page.evaluate(() => (window.__pitchstoneMessages ?? []) as never)
}

export async function command(page: Page, name: string, args?: object) {
  return page.evaluate(([n, a]) => window.pitchstone.command(n, a ? JSON.stringify(a) : undefined), [name, args] as const)
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
