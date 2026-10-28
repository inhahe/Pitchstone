# Pitchstone Notes

A markdown note-taking app for Android, in the spirit of Obsidian: notes are markdown, linked
with `[[wikilinks]]`, tagged with `#tags`, organised in folders, and easy to get out of the app
again as `.md` or `.txt` files, or kept in step with a folder (an Obsidian vault, a Syncthing
folder) as one markdown file per note.

## Writing

The editor shows formatting as you write (bold looks bold, a heading looks like a heading),
and stores each note as markdown.

- **Enter starts a new line**, as in Obsidian: one row down, and one newline in the file.
  Enter on an empty line makes a blank line, which separates paragraphs (and leaves a quote).
  An empty line left between two lines is saved as a blank line too, as Obsidian writes it.
  Backspace at the start of a line after a blank line removes the blank line. In a list, Enter
  starts the next item; in a table, it moves to the cell below.

- **Formatting toolbar** above the keyboard: undo/redo, heading level, bold, italic,
  strikethrough, highlight, inline code, bulleted/numbered/check lists, indent/outdent, link,
  link to a note, quote, code block, table (with row/column actions when inside one), divider,
  footnote, clear formatting. Buttons light up for the formatting at the cursor.
- **Markdown shortcuts** (Settings → Markdown shortcuts, on by default): type markdown and it
  formats on the fly. Those for headings, lists, quotes, code blocks and dividers work at the
  start of any line, and the line becomes that block; so do the toolbar's buttons for them.

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
- **Pictures**: paste a picture into a note (long-press → Paste), or pick one with the picture
  button in the toolbar. It is stored with your notes and written into the note the way
  Obsidian does, `![[Pasted image 20261023211500.png]]`; `![[pic.png|300]]` shows it 300 pixels
  wide. Tap a picture, then Open, to see it full size. Large photos are made at most 2560
  pixels on their long side (Settings → Shrink large photos), and HEIC photos become JPEG.
  A picture no note or version uses any more is deleted after 30 days.
- **Other attachments**: a link to a PDF, a recording, a video, an office file or a canvas
  (`[[report.pdf]]`) opens it in a viewer, if it came in with an import or a folder sync. Such
  a link is shown dimmed, like a link to a missing note, while the file is not stored.
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
- Notes are included in Android's backup (Google One / device transfer). Pictures go along to
  a new phone in a device transfer (from Android 9 on), but not into Google's cloud backup, whose 25 MB limit they
  would soon exceed (and then the notes would not be backed up either): export them, or sync
  them with a folder.
- Opening a note never changes it: only your edits are saved.

## Exporting

From the list (⋮ → Export these notes…, or select notes → export icon), from the drawer
(Export all notes…), or from a note (⋮ → Export…):

- **Format**: Markdown (`.md`), or plain text (`.txt`) with the formatting symbols removed
  (lists keep their markers, tables are laid out in columns, links show their address). For
  `.txt` you can also keep the markdown symbols.
- **Files**: one file per note, named after its title, with the note folders recreated as
  subfolders (optional); or all notes in one file, each under its title. A Markdown export also
  writes the pictures and other attachments the notes link to into an `attachments` folder.
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
  Optionally the note is attached as a file (`.html`, `.md` or `.txt`); an `.html` file
  holds the note's pictures (up to 20 MB of them; the rest are named), while an email names
  them in brackets.
- **Share as text…** and **Share as file…** to any app.

## Importing

Drawer → Import notes…: import a whole folder (an Obsidian vault works; its hidden `.obsidian`
folder is skipped) or chosen files. `.md` files are read as markdown; `.txt` files are kept as
plain text by default (their symbols stay literal), or read as markdown if you choose. Folder
structure can be kept. The pictures and other attachments the imported notes link to (PDFs,
recordings, videos, office files, canvases; up to 100 MB each) come along, one whose name is
already taken by a different file getting a new name, which the note follows. Importing the
same files again does not create duplicates.

Text shared to Pitchstone from another app becomes a new note, and markdown files opened with
Pitchstone are imported.

## Syncing with a folder

Settings → Sync with a folder keeps the notes in step with a folder on the phone: an Obsidian
vault, or any folder that Syncthing, FolderSync or a cloud app keeps in step with a computer.
No more exporting and importing by hand.

- **Choose folder**: before anything happens, Pitchstone shows what the first sync will do: how
  many notes it will write to the folder, how many files will become notes, and how many are in
  both (and how many of those differ: the newer text is kept, the other goes to the note's
  version history). Nothing is deleted.
- **One file per note**: `Folder/Title.md`, holding exactly the note's text. The files notes
  link to (pictures, PDFs, recordings, videos, office files, canvases) are there too, where
  Obsidian's "Default location for new attachments" says (at the top otherwise). Hidden
  folders (`.obsidian`, `.trash`, `.git`) are left alone, as are files no note links to.
- **Folders left out**: choose folders of the vault to leave out of sync (Templates, an old
  Archive), in the first-sync dialog or later in Settings. Their notes stay as they are on both
  sides, and are not kept in step. A note moved into one, here or in the vault, is moved on the
  other side too, and left alone from then on; nothing is trashed for it. Taken back into sync,
  a folder's notes and files meet as at a first sync.
- **When**: as Pitchstone comes to the front, a few seconds after it leaves it or a note is
  closed, about every 15 minutes in the background, and when you tap Sync now. While
  Pitchstone is open, changes another app makes in the folder (Syncthing, Obsidian) arrive
  within seconds, if the folder is on the phone's storage or a memory card.
- **Changes** on either side reach the other: edits, renames (a renamed file renames its note,
  and links to it are updated if Settings → Update links when renaming is on), and moves
  between folders. A note open in Pitchstone is not changed by a sync until you close it; its
  own edits still go out to the folder.
- **Changed on both sides**: the newer text is kept, the other goes to the note's version
  history, and Pitchstone tells you which notes.
- **Deleting**: a file deleted from the folder sends its note to Pitchstone's trash; a note
  deleted in Pitchstone sends its file to the folder's `.trash` (Obsidian's trash). If the files
  of more than 5 notes vanish at once (a folder emptied by mistake, a memory card not there),
  nothing is trashed until you choose in Settings: move those notes to the trash, or write
  their files again. Attachments are never deleted by a sync.
- **Settings** shows when the last sync ran and what it did, and lists files that could not be
  synced (too large for a note, unreadable); those are tried again at the next sync.
- **Stop syncing** leaves the notes and the files as they are. **Change folder** starts afresh
  with the new one, as a first sync.

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

The simplest way: on the phone, open [Releases](https://github.com/inhahe/Pitchstone/releases),
download the latest `Pitchstone-<version>.apk`, and open it (allow your browser or file
manager to install apps when asked). A newer release installs over an older one, keeping your
notes.

To build it yourself and install it over USB:

1. On the phone: Settings → About phone → tap **Build number** seven times; then Settings →
   System → Developer options → turn on **USB debugging**.
2. Connect the phone to the PC with USB, unlock it, and accept **Allow USB debugging?**
   (tick *Always allow from this computer*).
3. In this folder, run `install-on-phone.bat` (Windows: it builds the release version and
   installs it). Elsewhere, `./gradlew assembleRelease`, then
   `adb install -r app/build/outputs/apk/release/app-release.apk`.

To update later, do the same again: notes are kept. **Back up your release key** (the `.jks`
file `keystore.properties` names) **and `keystore.properties`**: Android only accepts an update
signed with the same key, and installing differently signed builds means uninstalling first,
which deletes the notes on the phone (export them first).

## Building

Requirements: JDK 17+ (`JAVA_HOME`), the Android SDK (`local.properties` → `sdk.dir`), and
Node.js on `PATH` (the editor is TypeScript, bundled by the Gradle build).

```
gradlew assembleDebug          # app/build/outputs/apk/debug, package app.pitchstone.debug
gradlew assembleRelease        # signed if keystore.properties exists
gradlew testDebugUnitTest      # Kotlin tests (JVM and Robolectric)
cd editor && npm test          # markdown round-trip, CommonMark spec, fuzzer
cd editor && npm run test:e2e  # editor in Chromium: typing, IME composition, commands
```

`keystore.properties` (not in git) names the release key; without it, the release build is
unsigned. Make a key once, kept outside the project:

```
keytool -genkeypair -v -keystore pitchstone-release.jks -alias pitchstone -keyalg RSA -keysize 2048 -validity 10000
```

```
storeFile=/path/to/pitchstone-release.jks
storePassword=…
keyAlias=pitchstone
keyPassword=…
```

To publish a release on GitHub, raise the version (`versionName` and `versionCode` in
`app/build.gradle.kts`), commit and push, then run `release.bat` (it needs the GitHub CLI,
`gh`, logged in). It builds the signed APK and publishes it as `v<versionName>`, unless that
version is already released; `release.bat --dry-run` shows what it would publish.

How the app is built is described in `design.md`.
