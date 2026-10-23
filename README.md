# Inkdown

A markdown note-taking app for Android, in the spirit of Obsidian: notes are markdown, linked
with `[[wikilinks]]`, tagged with `#tags`, organised in folders, and easy to get out of the app
again as `.md` or `.txt` files.

## Writing

The editor shows formatting as you write (bold looks bold, a heading looks like a heading),
and stores each note as markdown.

- **Formatting toolbar** above the keyboard: undo/redo, heading level, bold, italic,
  strikethrough, highlight, inline code, bulleted/numbered/check lists, indent/outdent, link,
  link to a note, quote, code block, table (with row/column actions when inside one), divider,
  footnote, clear formatting. Buttons light up for the formatting at the cursor.
- **Markdown shortcuts** (Settings → Markdown shortcuts, on by default): type markdown and it
  formats on the fly.

  | Type | Get |
  | --- | --- |
  | `# ` … `###### ` at the start of a line | heading |
  | `- `, `* `, `+ ` | bulleted list |
  | `1. ` | numbered list |
  | `[ ] ` or `[x] ` | checklist item |
  | `> ` | quote |
  | ```` ```lang ```` then space or Enter | code block |
  | `---` then Enter | divider |
  | `**bold**`, `__bold__` | **bold** |
  | `*italic*`, `_italic_` | *italic* |
  | `~~strike~~` | ~~strike~~ |
  | `==highlight==` | highlight |
  | `` `code` `` | `code` |
  | `[text](https://…)` | link |
  | `[[Note]]`, `[[Note\|shown text]]`, `![[file]]` | link to a note (typing `[[` suggests notes) |

  Backspace right after a shortcut fires undoes it and leaves the characters you typed.

  **With shortcuts off**, what you type stays exactly as typed: `**`, `#`, `[[`, `- ` and the
  rest are ordinary characters, and they are escaped when saved, so they never turn into
  formatting, not even when the note is opened in another markdown app. The toolbar still
  formats. Pasted text follows the same rule (markdown is read as markdown only with shortcuts
  on).
- **Reading view** (the book icon) shows the note without editing it; links open on tap and
  checkboxes can be ticked. **Markdown source** (⋮ menu) shows and edits the raw markdown.
- **Links**: tap a link, note link, `#tag` or web address while editing and a bubble offers
  *Open*, *Edit* and *Unlink*. Opening a link to a note that does not exist creates it
  (`[[Folder/Note]]` creates it in that folder). Note links to missing notes are drawn with a
  dashed underline. `[[Note#Heading]]` and `[[Note#^block-id]]` open the note at that heading
  or block, and `[[#Heading]]` jumps within the note.
- **Find in note** (magnifier in the note): matches are highlighted, with next/previous.
- The title of a new note has the keyboard; Enter moves on to the text.

## Organising

- **Folders**, nested to any depth: create, rename and delete them from the drawer (☰). The list
  shows a folder's notes with its subfolders on top; Back climbs to the parent folder.
- **Tags**: any `#tag` (or `#nested/tag`) in a note, and `tags:` in its front matter. The drawer
  lists them; tapping one shows its notes, including those with nested tags under it.
- **Pin** notes to keep them at the top. **Sort** by last edited, created, or title.
- **Search** (magnifier in the list) finds notes containing every word you type, in titles and
  text, ignoring case.
- **Select** notes with a long press to pin, move, export or delete several at once.
- **Links to this note** (⋮ in a note) lists the notes that link to it. When you rename a note,
  links to it in other notes are updated (Settings → Update links when renaming).

## Safety of your notes

- Notes save themselves as you type, and again when you leave a note or switch apps. If a
  save fails (the phone's storage is full, say), the note does not close: a dialog lets you try
  again, stay and copy your text somewhere safe, or leave anyway.
- **Version history** (⋮ → Version history): while you edit, the previous version of a note is
  kept every 10 minutes, and always right before you empty a note or delete more than half of
  it (up to 60 versions, the oldest pruned after 90 days). Tap a version to read it, then
  restore it (the current text is kept as a version too) or delete it. The bin icon on a version
  deletes it (with Undo); the icon at the top deletes them all.
- **Trash**: deleted notes stay there for 30 days and can be restored. A note you empty by hand
  goes to the trash (with Undo) rather than disappearing; only a new note you never typed in is
  discarded.
- Notes are included in Android's backup (Google One / device transfer).
- Opening a note never changes it: only your edits are saved.

## Exporting

From the list (⋮ → Export these notes…, or select notes → export icon), from the drawer
(Export all notes…), or from a note (⋮ → Export…):

- **Format**: Markdown (`.md`), or plain text (`.txt`) with the formatting symbols removed
  (lists keep their markers, tables are laid out in columns, links show their address). For
  `.txt` you can also keep the markdown symbols.
- **Files**: one file per note, named after its title, with the note folders recreated as
  subfolders (optional); or all notes in one file, each under its title.
- **Options**: start each file with the title as a heading; add created/modified dates and tags
  as front matter (Markdown).
- **If a file already exists**: keep both (adds a number), replace it, or skip that note. Two
  notes with the same title in one export never overwrite each other.
- **Destination**: a folder you choose with Android's folder picker; the app remembers it for
  next time. The result lists what was written, skipped, or failed.

## Sharing

The share icon in a note offers:

- **Email**: the note's title as subject and the note as body, as **Formatted** text (headings,
  bold, lists, links and tables appear formatted in the email), **Markdown**, or **Plain text**.
  Optionally the note is attached as a file (`.html`, `.md` or `.txt`).
- **Share as text…** and **Share as file…** to any app.

## Importing

Drawer → Import notes…: import a whole folder (an Obsidian vault works; its hidden `.obsidian`
folder is skipped) or chosen files. `.md` files are read as markdown; `.txt` files are kept as
plain text by default (their symbols stay literal), or read as markdown if you choose. Folder
structure can be kept. Importing the same files again does not create duplicates.

Text shared to Inkdown from another app becomes a new note, and markdown files opened with
Inkdown are imported.

## Markdown details

Notes use CommonMark with GitHub tables, strikethrough and task lists, plus Obsidian's
`==highlight==`, `[[wikilinks]]`, `![[embeds]]`, footnotes and YAML front matter
("Properties"). A single line break is a line break, as in Obsidian. Raw HTML, link reference
definitions and front matter are kept exactly; HTML is shown as source, not rendered.

When you edit a note, the app writes the whole note back as markdown. The meaning never
changes, but the spelling of formatting can: `_italic_` becomes `*italic*`, `+` bullets become
`-`, underlined (setext) headings become `#` headings, reference-style links become inline
links. Notes you only open are never rewritten. See `known-issues.md` for the details.

## Installing on your phone

1. On the phone: Settings → About phone → tap **Build number** seven times; then Settings →
   System → Developer options → turn on **USB debugging**.
2. Connect the phone to the PC with USB, unlock it, and accept **Allow USB debugging?**
   (tick *Always allow from this computer*).
3. In this folder, run `install-on-phone.bat` (it builds the release version and installs it).

To update later, run the same script: notes are kept. **Back up
`C:\Users\Computer\.android\inkdown-release.jks` and `keystore.properties`**: Android only
accepts an update signed with the same key, and installing differently signed builds means
uninstalling first, which deletes the notes on the phone (export them first).

## Building

Requirements: JDK 17+ (`JAVA_HOME`), the Android SDK (`local.properties` → `sdk.dir`), and
Node.js on `PATH` (the editor is TypeScript, bundled by the Gradle build).

```
gradlew assembleDebug          # app/build/outputs/apk/debug, package app.inkdown.debug
gradlew assembleRelease        # signed if keystore.properties exists
gradlew testDebugUnitTest      # Kotlin tests (JVM and Robolectric)
cd editor && npm test          # markdown round-trip, CommonMark spec, fuzzer
cd editor && npm run test:e2e  # editor in Chromium: typing, IME composition, commands
```

`keystore.properties` (not in git) names the release key:

```
storeFile=C:/Users/Computer/.android/inkdown-release.jks
storePassword=…
keyAlias=inkdown
keyPassword=…
```

How the app is built is described in `design.md`.
