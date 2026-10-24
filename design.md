# Pitchstone Notes: design

An Android markdown notes app: a Kotlin/Jetpack Compose app around a WYSIWYG editor that runs
in a WebView. User-facing behaviour is in `README.md`; known limitations in `known-issues.md`.

## Layout

```
editor/                  The editor page (TypeScript, ProseMirror, markdown-it)
  src/schema.ts          Document model
  src/markdown/          The markdown dialect: md.ts (markdown-it config), parse.ts, serialize.ts
  src/editor/            Interactive layer: editor.ts (page controller), rules.ts (shortcuts),
                         commands.ts, keymap in editor.ts, decorations.ts, bubble.ts,
                         suggest.ts, views.ts, plugins.ts, bridge.ts, settings.ts
  src/main.ts            window.pitchstone API for the app
  test/                  vitest: round trips, CommonMark spec corpus, fuzzer (+ shrinker)
  e2e/                   Playwright (Chromium, Pixel 7 emulation): typing, IME, commands
  build.mjs              esbuild bundle -> <out>/{index.html, editor.js, *.css}
app/                     The Android app (package app.pitchstone)
  data/                  Room: entities, DAO, AppDatabase; NoteRepository; Settings (DataStore)
  markdown/              commonmark-java in the editor's dialect: Markdown, WikiLinks,
                         PlainText, Html, NoteIndexer, LinkRewriter
  export/                FileNames, ExportPlanner, ExportWriter, DocumentTree (SAF), Importer
  share/                 Sharing: email/text/file intents
  ui/                    Compose screens: notes (list, drawer), editor (screen, WebView,
                         toolbar, dialogs, view model), export, importer, share, settings,
                         trash, history, common
```

The Gradle task `:app:buildEditor` runs `node editor/build.mjs` into
`app/build/generated/editorAssets/editor`, registered as generated assets through the AGP
variant API; the bundle is never committed.

## The markdown contract

Notes are stored as markdown, in this dialect (`editor/src/markdown/md.ts`): CommonMark, GFM
tables/strikethrough (`~~` only)/task lists, Obsidian `==highlight==`, `[[target|alias]]`,
`![[embed]]`, footnotes (`[^x]`, definitions kept in place), YAML front matter at the very top,
and `breaks: true` (one newline is a line break). Raw HTML is parsed so it can be kept, never
rendered. Bare URLs stay text (no linkify); the editor decorates them.

The editor's document model is not markdown, so two directions must agree:

- **parse** (`parse.ts`): markdown-it tokens -> ProseMirror document. Every token type maps to
  a node or mark; unknown ones throw `MarkdownParseError`, and the page then opens the note in
  source mode rather than risk losing anything. Link reference definitions (`link_def`),
  HTML blocks/inline HTML and front matter become atoms holding their source verbatim. Extra
  blank lines between top-level blocks become empty paragraphs ("spacers"); trailing blank
  lines are dropped.
- **serialize** (`serialize.ts`): document -> markdown, with the contract
  `parse(serialize(doc).markdown) == serialize(doc).doc`, where `.doc` is the input after
  `normalizeDoc` (the changes markdown forces: whitespace moved out of bold/italic ranges,
  trailing hard breaks dropped, empty paragraphs inside lists/quotes dropped, table header row
  fixed, invalid URLs unlinked...). `exact: false` reports a contract violation (a bug); the
  page warns once.

Escaping is minimal-first and verified. Each paragraph/heading/cell is rendered with as few
backslashes as heuristics allow (level 0), parsed back with the real parser as a block, and
re-rendered with full escaping (level 1) or `_` emphasis if it does not come back identical.
If formatting itself cannot be expressed (a delimiter between a letter and punctuation),
`shrinkMarks` moves the mark edge off the punctuation; as a last resort single formatting runs
are dropped until the paragraph round-trips. Attempts run in a fixed order on shrunk content
too, so serializing a result again gives the same text (stability). Finally the whole note is
parsed back; blocks that still differ are re-rendered at level 1.

So a typed `*`, `#`, `[[`, `- ` is always literal: with markdown shortcuts off nothing turns it
into formatting, and the serializer escapes whatever the parser would otherwise read as
markup. A task item keeps its first paragraph even when empty, since the checkbox is written
on it (`- [ ]` over a nested list). Tests: `test/roundtrip.test.ts`, all 652 CommonMark spec examples
(`test/spec.test.ts`), and a fuzzer (`test/fuzz.test.ts`, `FUZZ_SEED`/`FUZZ_CASES`) that
generates documents rich in markdown punctuation, nested containers, tables, footnotes,
wikilinks and HTML, and shrinks any failure to a minimal document. 800,000 generated
documents pass.

## The editor page

`editor.ts` owns an `EditorView` plus a title `<textarea>` and a source `<textarea>`.

- **Modes**: `edit`, `read` (not editable; links open on click, checkboxes still toggle) and
  `source` (raw markdown; switching back parses it as one undoable step).
- **Shortcuts** (`rules.ts`): prosemirror-inputrules, gated as a whole on
  `settings.markdownShortcuts`. Rules also run on IME `compositionend` (Gboard composes words),
  where the whole match is already in the document. Backspace right after a rule undoes it.
- **Clipboard**: copy gives markdown (shortcuts on) or plain text; pasted plain text is parsed
  as markdown only with shortcuts on.
- **Plugins**: decorations (tags, bare URLs, missing-note links, find matches, placeholder),
  structure fix-ups (table header row, task items start with a paragraph), toolbar state
  reports, link bubble, `[[` note suggestions.
- **Links** (`links.ts`): which notes exist is decided by the app's own rules (see Data): the
  app sends every note's lookup keys, and `noteExists` matches a link's whole name, then its
  `folder/note` path key. A picked suggestion is written as the title's link name (no `#|^[]`),
  with the title as alias when they differ. `anchorPosition` finds the heading (last part of
  `#A#B`, ignoring `#|^:%[]`) or `^block` a link's anchor names, for `scrollToAnchor`.
- **Code blocks**: making one from paragraphs keeps links to notes, images, footnote
  references and inline HTML as their markdown (`atomMarkdown`); other formatting is dropped.
- **Old WebViews**: the bundle targets Chrome 90. esbuild lowers syntax but adds no polyfills,
  so newer APIs must not be used; the e2e tests delete the post-Chrome-90 ones (`structuredClone`,
  `Array.prototype.at`, ...) before the page loads, and CSS `color-mix` has a plain fallback.
- The page never navigates; anchors are prevented, the app opens links.

### Bridge (app <-> page)

The app calls `window.pitchstone.*` with JSON-string arguments (`EditorWebView.call`):
`load(payload)`, `setSettings`, `setNotes({titles, keys})`, `setMode`, `command(name, args)`,
`flush()` (returns the pending change synchronously as JSON and marks it reported; if the
change cannot be turned into markdown it stays pending and the result carries `error`),
`focus`, `scrollToAnchor`, `find`/`findNext`/`findPrevious`.

If `load` cannot parse a note, it opens in source mode over an empty stand-in document that is
never shown or saved: leaving source mode always re-parses, and stays in source mode on failure.

The page posts JSON to `PitchstoneAndroid.postMessage` (`bridge.ts` `OutMessage`): `ready`,
`changed {markdown, title, exact, seq}` (400 ms after the last edit, on blur, on page hide),
`state {toolbar state}`, `openLink`, `openNote`, `openTag`, `editLink`, `editWikilink`,
`modeChanged`, `findResult`, `warning` (`parseFailed`, `sourceParseFailed`, `serializeFailed`
once per failing streak, `inexact`).

## Saving

**In the page.** Autosave (400 ms after the last edit, and on blur) serializes in a Web Worker
(`src/worker.ts`, bundled as `editor-worker.js`): the page sends `doc.toJSON()` and posts the
answer as `changed`, so typing never waits for a long note's serialization (a 72 KB note: about
65 ms in the worker, about 5 ms left on the page, against 20-40 ms on the page before, on the
emulator). The worker has one job at a time: an autosave that comes due while it works runs
when it answers, so a slow worker still saves during bursts of typing. It builds each document
with `DocInterner`, which gives unchanged textblocks their node from the previous request, so
the serializer's per-node cache works there as on the page. Every edit counts up `editGen`; a
worker answer is reported only if no flush has already reported that edit count or a later one
(`reportedGen`), so an older version never reaches the app after a newer one, and edits made
meanwhile stay pending. `flush()` and the
page-hidden save serialize synchronously on the page, as does everything where no worker can
run (a `file://` page) or the worker fails. The e2e tests serve the page from a routed http
origin so that the worker runs.

**In the app.** `EditorViewModel` keeps `content` (newest known), `saved` (what the database has) and
`inFlight`. Each `changed` message updates `content` and starts `saveNow()` in the
application scope (leaving the screen never cancels a save); saves are serialized by a mutex
and always write the newest content. Before anything that leaves the note (back, opening a
link or note, sharing, exporting, going to the background) `flushThen` asks the page for its
pending change synchronously, saves, then continues. The view model also observes the note in
the database: a change that is none of `saved`/`inFlight`/`content` came from elsewhere
(restored version, link rename) and is reloaded into the page.

Every way out of a note is an `Exit` (back, a tapped `#tag`, a followed `[[link]]`) taken only
once everything is saved: if the save failed or the page could not produce markdown, the note
stays open with a dialog (try again, leave anyway, stay and copy the text), which then takes
the same exit. A tag closes every editor, and an editor left along a link may be closed that
way later, so none may be left with unsaved text. Every session ends once in `finishSession`,
from Back or from `onCleared` when the screen is removed some other way: an empty note is
deleted only if it never held text in this session and was never written
(`NoteRepository.isPristine`), and otherwise goes to the trash with Undo. Its failures (and
those of the start-up chores) are logged and reported, never thrown: an exception in the app
scope would crash the app, at start-up at every start.

Renames: each note records `linkedTitle`, the title its incoming links were last updated for.
`finishSession` calls `NoteRepository.applyRename`, which rewrites the links that led to the
note before the rename (`LinkResolver` over the notes as they were: `[[Client/Server]]` may
lead to a note titled "Client/Server", not to the renamed "Server"), each linking note with a
compare-and-set save of title and body, retried, so a racing editor save is never overwritten,
and then records the new title; renames left pending (the process died) are completed at
start-up (`applyPendingRenames`, one failure skipped and left pending). Links are left alone
when the setting is off or another note still has the old name.

The WebView is recreated if its renderer dies (`onRenderProcessGone`); the activity handles
configuration changes itself, so rotation or theme changes keep the WebView and its undo
history.

## Data

Room database `pitchstone.db` (WAL), schema exported to `app/schemas`:

- `notes`: id, title, body (markdown), folder path, created/updated, pinned, trashedAt,
  `searchText` (lower-cased title + plain text, at most 600 KB), `snippet`. A note holds at
  most 1 MB (`MAX_NOTE_BYTES`, checked by create, import and save, which throw
  `NoteTooLargeException`): Android reads a row through a 2 MB window, and a larger row could
  be written but never read back.
- `folders`: explicit folder paths (so empty folders exist). The folder list is these plus
  every folder holding a note, with ancestors.
- `note_tags` (key, display) and `note_links` (target key): the derived index, rewritten on
  every save from `NoteIndexer` (commonmark-java).
- `revisions`: earlier versions; a revision of the replaced content is written when the last
  one is older than 10 minutes, and always when a save empties the note or cuts it by more
  than half (so a deletion can be undone); at most 60 per note, older than 90 days pruned
  beyond 10. The history screen restores a version (`restoreRevision`, which keeps the current
  content as a version) and deletes one (`deleteRevision`, Undo re-inserts it with its id) or
  all (`deleteAllRevisions`).

Folder trees are matched in SQL by `folder = :old OR substr(folder, 1, length(:old) + 1) =
:old || '/'`: case-sensitive (unlike `LIKE`) and counted in SQLite's own characters (a Kotlin
length counts UTF-16 units). New folder paths (create, import, move, a link to a missing note)
take the spelling of an existing folder that matches ignoring case (`existingSpelling`), since
two such folders would be one on most phone storage and in every export. Bulk actions on note
ids run in chunks of 500, since SQLite before 3.32 (Android 8 to 11) allows 999 variables per
statement.

Search is substring AND over words on `searchText` (LIKE with escaped wildcards). Notes in the
trash for 30 days are purged at start-up.

Wikilinks resolve (`LinkResolver`) by the link's note name (`WikiLinks.noteName`: before any
`#`, trimmed, `.md` dropped) and `WikiLinks.key` (without `[]|#^`, spaces collapsed,
lower-cased): first a note whose whole title matches (titles may contain `/`), then a
`folder/note` path (`WikiLinks.pathKey`) among each note's `lookupKeys` (title, and
`tail-of-folder/title` for every tail of its folder path, `/title` at the top level). Among
several: same folder, then top level, then most recently edited. A link is indexed
(`note_links`) under its whole name and the part after each `/`, so backlinks and renames find
every link that may mean a note; `backlinks` then keeps those that resolve to it. Following a
link to a missing note creates it (`folder/note` in that folder, as Obsidian does); links to
attachments (`WikiLinks.isAttachment`: images, audio, video, PDF, office files, canvases) are
refused. The editor's missing-link marks use the same rules (`editor/src/editor/links.ts`).

Settings live in DataStore (`Settings.kt`).

## Markdown on the app side

Export, sharing and indexing use commonmark-java configured like the editor (`Markdown.kt`).
Where the two parsers could disagree, the app follows markdown-it:

- Front matter is split with the editor's own regex.
- commonmark-java cannot hand `[` to a custom parser, so `WikiLinks.scan` finds wikilinks with
  markdown-it's rule (first `]]`, no brackets/newlines inside, escapes, `\|` in tables),
  skipping ranges where `[[` is literal (code, raw HTML, autolinks, definitions; found by a
  first parse with source spans). Each link is replaced by a private-use placeholder that a
  custom inline parser turns into a `WikiLink` node. `LinkRewriter` uses the same scan to
  rename links in place.
- `==highlight==` is a custom delimiter processor.

`Html` renders with raw HTML escaped and unsafe URLs dropped; email output uses inline styles
and ☐/☑ characters for tasks. `PlainText` renders structure without symbols.

## Export and import

`ExportPlanner` (pure) turns notes and options into files: per-note files keep the markdown
exactly (plus optional title heading and metadata front matter); `.txt` uses `PlainText`
unless the markdown is kept; a combined file puts each note under its title, front matter as
a YAML code block. `FileNames` makes names valid on FAT/NTFS/ext4, cut at a character boundary
within 200 bytes.

`ExportWriter` writes to a `DocumentDir` (`SafDir` over a Storage Access Framework tree, or
`FileDir` for tests): folders are matched ignoring case (as on FAT/exFAT), names it wrote
itself are never overwritten (duplicates get " (2)"),
existing files follow the chosen policy, replacing opens with `"wt"` (truncate) and falls back
to delete-and-create, and `.md` is created as `application/octet-stream` so providers do not
append an extension.

`Importer` walks a tree (skipping hidden folders) or picked files, reads UTF-8 with or without
BOM, converts plain text to markdown that displays it literally (`plainTextToMarkdown`), and
skips notes already present unchanged. A file shared or opened from another app is read only
from a `content://` URI. Files are read up to `Importer.MAX_BYTES` (a note's limit), counted
as they are read since a provider's reported size can be missing; a larger file is skipped
(folder import) or refused with a message (shared or opened file).

## Sharing

Email without attachment is `ACTION_SENDTO mailto:` with subject, `EXTRA_HTML_TEXT` and a
styled `EXTRA_TEXT` (Gmail renders the Spanned). With an attachment it is `ACTION_SEND`
addressed to each email app by package (from a `mailto:` query; `<queries>` in the manifest):
a `mailto:` selector no longer resolves for apps targeting Android 15. Shared files are
written to `cache/shared` and served by a FileProvider; files older than an hour are removed.

## Testing on a device

Debug builds enable WebView debugging; with `adb forward tcp:9222
localabstract:webview_devtools_remote_<pid>` the page can be inspected in `chrome://inspect`
or driven over the DevTools protocol (raw `Runtime.evaluate`; Playwright's `connectOverCDP`
needs browser-level commands Android WebView lacks).
