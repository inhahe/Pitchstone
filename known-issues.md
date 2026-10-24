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

- **Attachments and images stored with notes**: `![[image.png]]` embeds are shown as a chip;
  images from the web (`![](https://…)`) display. A picture cannot be added from the phone.
- **Raw HTML** is shown as source, not rendered (by choice: safety, and email output).
- **Math** (`$…$`), **callouts** (`> [!note]`), **Mermaid**, and **inline footnotes** (`^[…]`)
  are kept as text, not rendered.
- **Heading and block links** (`[[Note#Heading]]`, `[[Note#^id]]`) scroll to the heading or
  block in the editing and reading views, not in markdown source; one that matches nothing
  opens the note at its top, without a message.
- **Sync**: notes live on the phone (and in Android backup); export/import moves them.
- **Tables**: cells hold one line of text (as in GitHub markdown); merged cells pasted from
  elsewhere are split when saved.

## Behaviour to know

- **A note holds at most 1 MB of text** (about 500 pages). A longer note cannot be saved (the
  editor says so and keeps it open), and a longer file is not imported. Search looks at the
  first 600 KB of a note's text.

- Email formatting depends on the mail app: Gmail shows bold, italic, links and lists from
  the formatted body; some apps show only the plain text. Attach the `.html` file for a
  faithful copy.
- Links in other notes are updated after a rename when you leave the renamed note (if the app
  is stopped first, at its next start), and only if no other note still has the old title.
- A link containing `/` may mean a title with a slash or a note in a folder: `[[Work/Plan]]`
  finds a note titled "Work/Plan" first, then a note "Plan" in a folder Work. Tapping such a
  link to a missing note creates "Plan" in the folder Work, as Obsidian does.
- Folders whose names differ only in case ("Work" and "work") would be one folder on most
  phone storage and in exports, so a new folder takes the spelling of an existing one ("work/Plans"
  goes into "Work"). Renaming a folder keeps the spelling you give it.
- Gboard's own autocorrect and capitalisation apply in the editor as in any text field.
