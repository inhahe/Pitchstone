import {expect, test} from '@playwright/test'
import {flush, open} from './helpers'

test('Enter in the title moves to the text, and typing there works', async ({page}) => {
  await open(page, '')
  await page.locator('#title').fill('My title')
  await page.keyboard.press('Enter')
  await page.keyboard.type('# Groceries')
  await page.keyboard.press('Enter')
  await page.keyboard.type('after')
  const f = await flush(page)
  expect(f.title).toBe('My title')
  expect(f.markdown).toBe('# Groceries\n\nafter\n')
})
