# Known issues and limitations

## By design (markdown cannot say otherwise)

- **Editing rewrites the formatting syntax of the whole note.** The meaning is preserved
  (tested against the CommonMark spec and a fuzzer), but the spelling may change: `_em_` ->
  `*em*`, `+`/`*` bullets -> `-` (another bullet only where two lists would merge), setext
  headings -> `#` headings, indented code -> fenced, reference links -> inline links (the
  definitions themselves are kept), loose lists that need no blank lines -> tight, HTML
  entities -> the characters. Notes that are only opened are never rewritten.
- **Blank lines**: extra blank lines between top-level blocks are kept; extra blank lines
  inside lists and quotes, and blank lines at the end of a note, are not.
- **Formatting markdown cannot express** is adjusted when saving: whitespace at the edge of
  bold/italic moves outside it; bold/italic starting or ending on punctuation between letters
  (`a**.b**c`) loses that punctuation character's formatting; in pathological overlaps of
  several formats, one format run may be dropped. Nothing of the text is lost.
- A line break at the very end of a paragraph is dropped.
- **A `---` line inside the front matter** (Properties) ends it when the note is saved: YAML
  front matter cannot contain such a line. The lines after it become part of the note's text
  (nothing is lost, and the editor warns that the note could not be saved exactly).

## Not supported (yet)

- **Raw HTML** is shown as source, not rendered (by choice: safety, and email output).
- **Math** (`$…$`), **callouts** (`> [!note]`), **Mermaid**, and **inline footnotes** (`^[…]`)
  are kept as text, not rendered.
- **Heading and block links** (`[[Note#Heading]]`, `[[Note#^id]]`) scroll to the heading or
  block in the editing and reading views, not in markdown source; one that matches nothing
  opens the note at its top, without a message.
- **Sync with a server** of Pitchstone's own: folder sync works through a folder another app
  (Syncthing, a cloud app) keeps in step with other devices.
- **Tables**: cells hold one line of text (as in GitHub markdown); merged cells pasted from
  elsewhere are split when saved.

## Behaviour to know

- **Pictures are not in Google's cloud backup** (they go to a new phone in a device transfer,
  from Android 9 on): its 25 MB limit per app would stop the notes being backed up too. Keep
  them safe with export or folder sync.
- An export puts all pictures in one `attachments` folder. Obsidian finds `![[name]]` anywhere,
  but a `![](some/path/pic.png)` link written by another app may then point elsewhere.
- Turning HEIC photos into JPEG, and shrinking large photos, needs Android 9 or later; before
  it, pictures are stored as they are.

- **A note holds at most 1 MB of text** (about 500 pages). A longer note cannot be saved (the
  editor says so and keeps it open), and a longer file is not imported. Search looks at the
  first 600 KB of a note's text.

- Email formatting depends on the mail app: Gmail shows bold, italic, links and lists from
  the formatted body; some apps show only the plain text. Attach the `.html` file for a
  faithful copy.
- Links in other notes are updated after a rename when you leave the renamed note (if the app
  is stopped first, at its next start), and only if no other note still has the old title. A
  note you are typing in at that moment keeps your typing, and with it the old link: update
  that one by hand (or rename the note back and forth).
- A link containing `/` may mean a title with a slash or a note in a folder: `[[Work/Plan]]`
  finds a note titled "Work/Plan" first, then a note "Plan" in a folder Work. Tapping such a
  link to a missing note creates "Plan" in the folder Work, as Obsidian does.
- Folders whose names differ only in case ("Work" and "work") would be one folder on most
  phone storage and in exports, so a new folder takes the spelling of an existing one ("work/Plans"
  goes into "Work"). Renaming a folder keeps the spelling you give it.
- Gboard's own autocorrect and capitalisation apply in the editor as in any text field.

### Folder sync

- **Not instant**: changes made in the folder arrive when Pitchstone comes to the front, or
  within about 15 minutes in the background, when Android lets background work run (battery
  saver and Doze can postpone it). Sync now in Settings does it at once.
- **A note's title is its file name.** Characters files cannot hold (`/ \ : * ? " < > |`)
  become spaces in the name ("Client/Server" is `Client Server.md`, which Obsidian's
  `[[Client/Server]]` would not find), and a note made from a file takes the file's name.
  A change of case alone in a title does not rename the file (some storage would number it
  instead).
- **Editing a note in Pitchstone rewrites its formatting syntax** (see the top of this file),
  and that reaches the file: `_em_` in a note edited on the phone becomes `*em*` in the vault.
  Notes only opened are never rewritten.
- **Changed on both sides** compares the file's time with the note's last edit; the clocks of
  two devices may disagree by a little. Nothing is lost either way: the other text is in the
  note's version history.
- Empty folders are not synced, and a note with neither title nor text is not written.
- Files are read as UTF-8; a file in another encoding shows replacement characters and is
  written back as UTF-8 if the note is edited.
- **Attachments** (pictures, PDFs...): two different files of one name are both left as they
  are, and stay so (Settings lists them) until one is made the same as the other; an attachment
  is never deleted by a sync, and one deleted from the folder comes back while a note still
  links to it. One over 100 MB is not stored (Settings lists it). A file no note links to is not
  synced, nor is one in a hidden folder or a folder left out.
- **A note moved into a folder left out** whose name is taken there by a file of the vault
  cannot follow: Settings lists it, and it is tried again at each sync. Rename one of them.
- A folder in the vault whose name the app spells otherwise ("Q&A: misc" is `Q&A misc`, a
  colon being something files cannot hold) is the app's folder of that spelling; a folder
  made in the vault takes the vault's spelling.
