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
                         suggest.ts, views.ts, plugins.ts, bridge.ts, settings.ts,
                         links.ts (link rules), attachments.ts (pictures)
  src/worker.ts          autosave serialization off the main thread
  src/main.ts            window.pitchstone API for the app
  test/                  vitest: round trips, CommonMark spec corpus, fuzzer (+ shrinker)
  e2e/                   Playwright (Chromium, Pixel 7 emulation): typing, IME, commands
  build.mjs              esbuild bundle -> <out>/{index.html, editor.js, *.css}
app/                     The Android app (package app.pitchstone)
  data/                  Room: entities, DAO, AppDatabase; NoteRepository, LinkResolver;
                         Attachments (pictures); Settings (DataStore)
  markdown/              commonmark-java in the editor's dialect: Markdown, WikiLinks,
                         PlainText, Html, NoteIndexer, LinkRewriter
  export/                FileNames, ExportPlanner, ExportWriter, DocumentTree (SAF), Importer
  sync/                  Folder sync: SyncEntries (table, hash), Vault (the folder listed),
                         SyncPlanner (pure), FolderSync (notes), AttachmentSync,
                         SyncManager (triggers, status, SyncWorker), FolderWatcher
  share/                 Sharing: email/text/file intents
  ui/                    Compose screens: notes (list, drawer), editor (screen, WebView,
                         toolbar, dialogs, view model), export, importer, share, settings,
                         trash, history, common
```

The Gradle task `:app:buildEditor` runs `node editor/build.mjs` into
`app/build/generated/editorAssets/editor`, registered as generated assets through the AGP
variant API; the bundle is never committed. It runs `npm ci` first when `node_modules` is
missing or older than `package-lock.json` (npm's `node_modules/.package-lock.json` records the
install).

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
  page warns once. A paragraph's empty lines (runs of hard breaks) are written as blank lines,
  as Obsidian has them, not as `\` lines (`readRun`, `paragraphsOfLines`): the lines either
  side of one become paragraphs, and at the top level each further empty line between them is a
  spacer, and so is each leading one. A quote or footnote holds no extra blank lines, so there
  those stay `\` lines; a list item's paragraphs keep all of them as breaks, since a blank line
  would loosen the list. Empty lines ending a paragraph are not written. The split paragraphs
  are cached per source paragraph, so that their renderings stay cached across saves.

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
on it (`- [ ]` over a nested list). Tests: `test/roundtrip.test.ts`, all 652 CommonMark spec
examples (`test/spec.test.ts`: each must round-trip, and must also render as the spec says,
with `breaks` off, except the three where `[[` is a wikilink), and a fuzzer (`test/fuzz.test.ts`, `FUZZ_SEED`/`FUZZ_CASES`) that
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
- **Lines** (`lines.ts`): Enter in a paragraph inserts a line break (one newline: `breaks:
  true`), as in Obsidian; on an empty line it ends the paragraph there (a blank line), keeping
  any empty lines below the cursor; on the empty last line of a quote's paragraph it leaves the
  quote, splitting it if more follows. At the start of a paragraph in a quote or footnote, which
  hold no empty paragraph, Enter adds a line break rather than a paragraph. Backspace at the start of a paragraph after another, and
  Delete at the end of one, turn the blank line between back into a line break. What acts a line
  at a time first makes the selected lines paragraphs of their own (`onLines`, `splitLines`):
  the toolbar's heading, list, quote and code block, and Enter's fence shortcut; a selection
  that only reaches the start of a line (or of a textblock) leaves that line out
  (`selectionEnd`). The divider goes after the line the selection ends on, splitting off only
  what follows it (`splitAfterLine`), and on an empty line or empty paragraph takes its place;
  `---` then Enter
  puts the cursor on a new line under the rule, before the lines that followed. Each block
  input rule is a `blockRule`, matching at the start of a textblock or after a line break
  (U+FFFC in the text rules see); after a break it splits the line off on both sides before
  applying, and a numbered list starts there only from 1, since in CommonMark only that
  interrupts a paragraph. The code block shortcut keeps inline atoms as their markdown, as the
  toolbar's does. Every split turns a run of line breaks into what the serializer reads it as
  (`splitAtRun` by `readRun`; a lone line break is split all the same), and Enter on an empty
  line does the same with one empty line more, so each Enter adds one newline to the file and
  the editor shows no empty line the file lacks. Line breaks a quote keeps stay only before
  lines that are not being made blocks. The command runs on an intermediate state built
  without plugins, so no `appendTransaction` sees the half-done split. Paragraphs in list items
  keep Enter as a new item, are not split, and a shortcut typed after a line break in one stays
  text. The Enter key is `enterCommand` (commands.ts): with a selection over blocks it deletes
  that first and runs the whole chain where it was, and with a picture or link to a note
  selected it runs at the cursor before it.
- **Clipboard**: copy gives markdown (shortcuts on) or plain text; pasted plain text is parsed
  as markdown only with shortcuts on.
- **Plugins**: decorations (tags, bare URLs, missing-note links, find matches, placeholder;
  the first three are mapped through each edit and recomputed only for the textblocks it
  touched, so typing costs a block, not the note), structure fix-ups (table header row, task items start with a paragraph), toolbar state
  reports, link bubble, `[[` note suggestions, and the keyboard's Enter on Android
  (`androidEnterPlugin`). ProseMirror leaves Enter to the browser there and reads the paragraph
  break back as an Enter; but Gboard often corrects the word first (a deletion, which
  ProseMirror reads only 20 ms later, and an insertion), the two are then read together, and
  the browser's paragraph break stays. The plugin takes the `insertParagraph` input itself:
  reads what the page holds so far (prosemirror-view's internal `domObserver.forceFlush`), runs
  `enterCommand`, and cancels the browser's.
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
(restored version, link rename). The page is asked first for edits it has not reported; those,
and any not saved yet, win and are saved over the outside change. Otherwise the page is
reloaded with it (a reload alone would drop the last moments of typing).

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
link to a missing note creates it (`folder/note` in that folder, as Obsidian does); a link to
an attachment (`WikiLinks.isAttachment`: images, audio, video, PDF, office files, canvases)
opens the stored file in a viewer through the FileProvider, and never makes a note. The editor's missing-link marks use the same rules (`editor/src/editor/links.ts`).

Settings live in DataStore (`Settings.kt`).

`sync_entries` (version 3, automatic migration tested from the exported version 2 schema) is
folder sync's memory: see Folder sync.

## Markdown on the app side

Export, sharing and indexing use commonmark-java configured like the editor (`Markdown.kt`).
Where the two parsers could disagree, the app follows markdown-it:

- Front matter is split with the editor's own regex.
- commonmark-java cannot hand `[` to a custom parser, so `WikiLinks.scan` finds wikilinks with
  markdown-it's rule (first `]]`, no brackets/newlines inside, escapes, `\|` in tables),
  skipping ranges where `[[` is literal (code, raw HTML, autolinks, definitions; found by a
  first parse with source spans). In table rows an unescaped `|` ends the attempt, since the
  row is split into cells first. Each link is replaced by a private-use placeholder that a
  custom inline parser turns into a `WikiLink` node. `LinkRewriter` uses the same scan to
  rename links in place.
- `==highlight==` is a custom delimiter processor.

`Html` renders with raw HTML escaped and unsafe URLs dropped; email output uses inline styles
and ☐/☑ characters for tasks, whose list items show no bullet (`class="task"` in a document,
an inline `list-style:none` in email). `PlainText` renders structure without symbols.

## Pictures and other attachments

Attachments are files stored with the notes in `files/attachments/<name>` (`AttachmentStore`,
at most 100 MB each), with a row each in `attachments` (name unique ignoring case,
`orphanedAt`). Which files are attachments is `WikiLinks.isAttachment` (pictures, audio, video,
PDFs, office files, canvases), the one list for import, sync and links (`attachments.ts` has a
copy). The app adds pictures itself; the rest come by import and sync. Notes mention them as
Obsidian does, by name: `![[name]]`, `[[report.pdf]]` (or `![](path/name)` from imported notes,
matched by file name). Following a link to one opens it in a viewer (FileProvider); the page
marks such a link missing only if no attachment of that name is stored (`NoteNames.attachments`,
from the `attachments` table as it changes). The page loads them from `/attachments/<name>` on its own origin, which
`EditorWebView.AttachmentPathHandler` serves (case and folders ignored); `attachments.ts` and
`views.ts` (`WikilinkView`, `ImageView`) draw image embeds and local image paths as pictures,
sized by Obsidian's `|300` or `|300x200`, or as a box with the name when missing.

In: a paste or drop of image files is posted to the app as `pasteImage` (base64). Pastes
(`ImagePrep.fromBytes`) and the photo picker (`fromUri`) both go through `ImagePrep` (HEIC
becomes JPEG; with the setting on, photos over 2560 pixels are scaled down, as JPEG, EXIF
orientation applied). The app stores the picture (`add`: a unique name, "Pasted image
<time>.png" for pastes, without the `[ ] | # ^` that would break a `![[name]]`) and answers
with `insertWikilink {embed: true}`. Out: a markdown export adds the pictures its notes
mention in `attachments/`; an HTML file holds them as data URLs (up to
`Html.INLINE_TOTAL_BYTES`, 20 MB, in all; the share sheet builds it off the main thread);
email bodies name them. Import stores the attachments an imported note mentions (`addOrReuse`:
identical bytes are reused; a taken name gets a new one and the note's mentions follow, all at
once with `LinkRewriter.renameAttachments`, so a new name that is another one's old one is
not renamed twice). One that cannot be stored is reported (`failedAttachments`); its note
is imported. A pasted picture is at most 25 MB (`MAX_IMAGE_BYTES`, checked by the page before
it reads the file): it crosses the bridge as base64. Mentions are found by name, the table-safe `![[pic.png\|200]]` included.

Clean-up (`collectAttachmentGarbage`, at start-up at most daily): names mentioned by any note
(trash included) are read page by page; if some stored names are left, versions are read the
same way (one pass, by `mentionedNames`, stopping once all are found);
an attachment nothing mentions is marked and deleted 30 days later. Pictures go to device
transfer, not cloud backup (25 MB limit): `data_extraction_rules.xml` from Android 12,
`xml-v28/backup_rules.xml` (`requireFlags="deviceToDeviceTransfer"`) on 9 to 11; Android 8
cannot tell the two apart, so there they stay behind. Version 2 of the database added the table (automatic
migration, tested from the exported version 1 schema).

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

## Folder sync

Two-way sync with a folder chosen through SAF (`syncFolderUri`, a persisted tree permission).
Each note is `Folder/Title.md` whose content is exactly the note's body; the attachments the
notes link to are files there. `Vault` lists the folder (hidden entries and the folders left
out skipped); `SyncPlanner` decides; `FolderSync` does the notes' steps, then `AttachmentSync`
the attachments'.

**Folders left out** (`syncExcluded`, paths in the vault; `Excluded` compares a vault path and
an app folder alike, each part as `FileNames.safe` names it, ignoring case): not looked into.
Pairs inside them are forgotten (a `Forget` step), so both sides stay as they are; notes in
them, and the attachments only they link to, are left out, unless a note still has a pair
elsewhere: it was just moved there in the app, and its file is moved after it, numbered
`Title (2).md` if the name is taken there (the folder was never listed); the pair is forgotten
at the next sync. The other way, a paired file gone from sync is looked for in those folders
too (`Vault.outside`, listed only then): a file there before has its own time, so the moved one
is the one with the pair's size, text and time (a move keeps a file's time), or, edited on the
way, the only file there of its name and newer than at the last sync (a template of the name is
older), which is then not written over. The note moves after it
(Adopt). Taken back into sync, a folder's notes and files meet as at a first sync, by natural
path.

**Memory.** `sync_entries` holds a row per pair as both sides last agreed: the path (unique
ignoring case, `NOCASE`), the note id (or, for attachments, the attachment's name), the SHA-256 of
the text (`SyncHash`; files are read as import reads them: BOM dropped, CRLF made LF), and the
file's time and size. A side changed if its text's hash differs from the row's; a file is only
read when its time or size moved, or it is new. Choosing another folder clears the table;
choosing the same one again (after its permission was lost) keeps it.

**Planning** (`SyncPlanner.plan`, pure, unit-tested case by case): pairs whose file is still
there give Record / WriteFile / UpdateNote / MoveFile / Conflict (newer wins by file time
against `updatedAt`), or, when the note left the app, TrashFile (unchanged file) / RestoreNote
or Import (changed file). Pairs whose file is gone look for an unpaired file with the same
text (a rename outside: Adopt; only files of the pair's size are read) and else give TrashNote,
or the file is written again if the note changed since. Unpaired files match an unpaired note by natural path (a first sync:
Record or Conflict) or are imported; the notes left are written as new files. `pathFor` keeps a
file's path while it still suits the note (`Title (2).md` from a clash, another case, a name
spelled with a character `FileNames.safe` replaces), else takes the first free
`Title (n).md`. Every path listed or paired is reserved for the whole plan, so a note never
takes the path of a pair forgotten later in the same plan, and a gone pair is forgotten before
its note's file is written again at the same path.

**Doing** (`FolderSync.sync`): lists the folder (hidden entries skipped), plans, and runs each
step in a try: one failing is reported in `SyncReport.problems` and, its pair untouched, comes
up again next time. Guards, each with a test that fails without it:

- A step that changes a note does so only if the note still has the text planned from
  (`unchanged`), and saves with `expectedBody` (compare and set); otherwise the next sync
  decides. Notes open in an editor (`busy`: `EditorViewModel` registers them with
  `SyncManager.opened`/`closed`) are not changed at all; their files may still be written.
- Overwriting a file first re-reads its time and size (`DocumentDir.refresh`): a file changed
  since the listing is left for the next sync.
- A file moves with its pair (`moveFile`), so a step failing after the move leaves the pair
  where the file is.
- More than `TRASH_LIMIT` (5) notes losing their files at once are held back
  (`SyncReport.heldBack`) until the user picks `GoneFiles.TRASH_NOTES` or `WRITE_AGAIN`. A
  folder with no markdown file anywhere, its left-out folders included (a card not there, a
  folder moved, a vault emptied but for its `.obsidian`), while notes are paired holds back
  everything, forgetting no pair, until the user picks.
- Deletions are moves: files to the folder's `.trash` (numbered if the name is taken there),
  notes to the app's trash; the text a conflict does not keep becomes a version
  (`forceRevision`, or `keepVersion` for the file's text, stored before the file is
  overwritten).
- A renamed file renames its note (`retitle`), unless its name still suits the note's title
  (`fitsName`: a character files cannot hold, a clash's number), so moving a file between
  folders never respells a title; links are updated afterwards with `applyRename`, whose
  rewrites reach their files at the next sync. A file moved or made in a folder goes to the
  app's folder of that file name (`appFolder`: "Q&A: misc" for `Q&A misc`).

Attachments come after the notes (`AttachmentSync`): for each attachment name a note
mentions, one only in the folder is stored (`addOrReuse`), one only in the app is written to
Obsidian's `attachmentFolderPath` (`.obsidian/app.json`: `/`, a folder, or `./…` beside the
note; nothing is written into a folder left out), and one changed in the folder since its row
replaces the app's copy (`AttachmentStore.replace`, an atomic move). Of copies in several
folders, the one synced before is used, else the first by path. One synced before into
a folder now left out is not written out again. One stored under another name (the app had
another file of that name) is found through its row, and gets its own name back
(`AttachmentStore.rename`) once that is free and no note in sync names the other. Files are hashed as they are
read, not held in memory. Two different files of one name are both left, reported, and
remembered as a clash (the row's hash starts `clash:`), so a later change to the folder's copy
never replaces the app's; the clash ends when the two are the same. Sync never deletes an
attachment.

**Running** (`SyncManager`, in `AppContainer`): one sync at a time (a mutex). In process: a
conflated request channel with a 1.5 s debounce, fed when the process comes to the front
(`ProcessLifecycleOwner`: `onFront`), when an editor session ends, and by `FolderWatcher`
while the app is in front. Android's storage provider (phone storage, memory cards) watches a
folder for as long as a listing of it is open, and announces each change at that folder's
children URI (it and Downloads do; cloud providers do not, and are not watched); so the watcher
keeps a listing of every folder synced open (at most 300, not
hidden, not left out: listed again after each sync, the new listings opened before the old
close) and listens to the provider's authority, keeping the announcements for folders under
the synced one, and calls once they have been quiet for 2 s. A folder that announces a change
is listed again, so a folder made in it is watched from then on (all are listed afresh when
the app comes to the front, and when the folders left out change). A sync's own writes are
announced too: `hold` and `release` around each sync, with the folders it wrote into
(`SyncReport.wroteIn`, from `Vault.touch`), drop what those folders announce during it and
1.5 s after, and pass on what other folders did, so a sync never wakes another by itself (an
open note's file, rewritten by each sync, once looped). A provider that announces nothing
leaves the other triggers. Watching stops when the app leaves the front (`onBack`), and with
`stop` once no folder is set. Through WorkManager (`SyncWorker`):
a periodic job every 15 minutes (battery not low) while a folder is set, and a one-off job 3
s after the app leaves the front, which keeps the process alive for it (appended after one
running, which replacing would cancel). The last report, the last one that changed something,
and any error are kept in `SharedPreferences` "sync" for Settings (`SyncSection`), which also
shows the first-sync preview (`FolderSync.preview`: the plan as if nothing were paired), the
held-back choice, the folders left out (`FoldersDialog`, also offered in the preview, which
then counts without them), and stop (permission released unless export remembers the same
folder).

`DocumentDir` gained what sync needs: `createFile` returns the new file's handle, `refresh`
re-reads one entry (listing a folder after each write would make a first sync quadratic over
SAF), `rename`, `moveTo` (SAF `moveDocument`, else copy and delete) and `delete`.

## Sharing

Email without attachment is `ACTION_SENDTO mailto:` with subject, `EXTRA_HTML_TEXT` and a
styled `EXTRA_TEXT` (Gmail renders the Spanned). With an attachment it is an `ACTION_SEND`
chooser that leaves out every app that is not an email app (`EXTRA_EXCLUDE_COMPONENTS`; email
apps are those answering a `mailto:` query, others those answering `SEND`, both declared in
the manifest's `<queries>`). A `mailto:` selector no longer resolves for apps targeting
Android 15, and a chooser shows at most two `EXTRA_INITIAL_INTENTS` since Android 10. Shared files are
written to `cache/shared` and served by a FileProvider; files older than an hour are removed.

## Testing on a device

Debug builds enable WebView debugging; with `adb forward tcp:9222
localabstract:webview_devtools_remote_<pid>` the page can be inspected in `chrome://inspect`
or driven over the DevTools protocol (raw `Runtime.evaluate`; Playwright's `connectOverCDP`
needs browser-level commands Android WebView lacks).
