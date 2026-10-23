// Bundles the editor page into one directory the Android app serves from its assets:
// index.html, editor.js, editor-worker.js, editor.css and prosemirror.css.
//
//   node build.mjs [--out <dir>] [--dev]
//
// The Gradle build calls this with --out pointing into app/build, so the bundle is never
// committed.

import {build} from 'esbuild'
import {copyFileSync, mkdirSync, readFileSync, writeFileSync} from 'node:fs'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const outIndex = args.indexOf('--out')
const out = resolve(here, outIndex >= 0 ? args[outIndex + 1] : 'dist')
const dev = args.includes('--dev')

mkdirSync(out, {recursive: true})

// The page, and the worker it serializes notes in for autosave (src/worker.ts).
for (const [entry, file] of [
  ['src/main.ts', 'editor.js'],
  ['src/worker.ts', 'editor-worker.js'],
]) {
  await build({
    entryPoints: [join(here, entry)],
    bundle: true,
    format: 'iife',
    target: ['chrome90'],
    minify: !dev,
    sourcemap: dev ? 'inline' : false,
    outfile: join(out, file),
    legalComments: 'none',
    logLevel: 'warning',
  })
}

// ProseMirror's own required styles, then the gap cursor's and the tables'.
const pmCss = [
  'node_modules/prosemirror-view/style/prosemirror.css',
  'node_modules/prosemirror-gapcursor/style/gapcursor.css',
  'node_modules/prosemirror-tables/style/tables.css',
]
  .map((p) => readFileSync(join(here, p), 'utf-8'))
  .join('\n')
writeFileSync(join(out, 'prosemirror.css'), pmCss)
copyFileSync(join(here, 'src/editor.css'), join(out, 'editor.css'))
copyFileSync(join(here, 'index.html'), join(out, 'index.html'))
console.log(`editor bundled into ${out}`)
