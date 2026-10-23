// The editor page: a title field, the WYSIWYG editor, and a raw-markdown textarea for source
// mode. It reports edits to the app as markdown (debounced), and answers flush() synchronously
// so the app can save before leaving the note.

import {baseKeymap, chainCommands, toggleMark} from 'prosemirror-commands'
import {dropCursor} from 'prosemirror-dropcursor'
import {gapCursor} from 'prosemirror-gapcursor'
import {history, redo, undo} from 'prosemirror-history'
import {undoInputRule} from 'prosemirror-inputrules'
import {keymap} from 'prosemirror-keymap'
import {Fragment, Slice, type Node} from 'prosemirror-model'
import {type Command, EditorState, Selection, TextSelection, type Transaction} from 'prosemirror-state'
import {tableEditing} from 'prosemirror-tables'
import {EditorView} from 'prosemirror-view'
import {MarkdownParseError, parseMarkdown} from '../markdown/parse'
import {serializeMarkdown} from '../markdown/serialize'
import {schema} from '../schema'
import {type Mode, post} from './bridge'
import {bubblePlugin, openTarget, targetAtPos} from './bubble'
import {
  alignColumnCommand,
  backspaceAtStart,
  blockquoteCommand,
  clearFormattingCommand,
  codeBlockCommand,
  currentLink,
  enterInTable,
  hardBreakCommand,
  headingCommand,
  horizontalRuleCommand,
  indentCommand,
  insertFootnoteCommand,
  insertTableCommand,
  insertWikilinkCommand,
  listCommand,
  outdentCommand,
  removeLinkCommand,
  setLinkCommand,
  splitItemCommand,
  tableCommands,
} from './commands'
import {decorationState, decorationsKey, decorationsPlugin} from './decorations'
import {anchorPosition} from './links'
import {structurePlugin, toolbarPlugin} from './plugins'
import {enterShortcut, markdownInputRules} from './rules'
import {applySettings, type EditorSettings, settings} from './settings'
import {suggestPlugin} from './suggest'
import {ImageView, ListItemView} from './views'

const {nodes, marks} = schema

/** The other notes, for link suggestions and for marking links to missing notes. */
export interface NoteNames {
  /** Every note's title, as shown. */
  titles: string[]
  /** The app's lookup keys of every note (see links.ts, noteExists). */
  keys: string[]
}

export interface LoadPayload {
  markdown: string
  title: string
  mode?: Mode
  settings?: Partial<EditorSettings>
  notes?: NoteNames
  /** Where to put the cursor: the title (new notes), the end of the text, or nowhere. */
  focus?: 'title' | 'end' | 'none'
}

export interface FlushResult {
  /** Whether anything changed since the last report; if not, there is nothing to save. */
  dirty: boolean
  markdown: string
  title: string
  exact: boolean
  seq: number
  /**
   * Set when the change could not be turned into markdown. It stays pending (a later flush
   * tries again), and `markdown` is empty: there is nothing to save.
   */
  error?: string
}

const SAVE_DELAY_MS = 400

export class InkdownEditor {
  readonly view: EditorView
  private readonly title: HTMLTextAreaElement
  private readonly source: HTMLTextAreaElement
  private mode: Mode = 'edit'
  private notes: NoteNames = {titles: [], keys: []}
  private dirty = false
  private seq = 0
  private saveTimer: ReturnType<typeof setTimeout> | null = null
  /** The markdown shown when source mode was entered; unchanged source is not re-parsed. */
  private sourceBaseline: string | null = null
  private warnedInexact = false
  /** Whether the last attempt to report a change failed (the app is told once per streak). */
  private failing = false
  /**
   * Whether the view holds an empty stand-in because the note could not be parsed: it must
   * never be shown or saved, so leaving source mode always re-parses.
   */
  private placeholder = false

  constructor(root: HTMLElement) {
    this.title = root.querySelector('#title') as HTMLTextAreaElement
    this.source = root.querySelector('#source') as HTMLTextAreaElement
    const mount = root.querySelector('#editor') as HTMLElement

    this.view = new EditorView(mount, {
      state: this.createState(schema.nodes.doc.createAndFill()!),
      nodeViews: {
        list_item: (node, view, getPos) => new ListItemView(node, view, getPos),
        image: (node) => new ImageView(node),
      },
      attributes: () => ({
        spellcheck: String(settings.spellcheck),
        autocapitalize: 'sentences',
        autocorrect: 'on',
        'aria-label': 'Note text',
      }),
      editable: () => this.mode === 'edit',
      dispatchTransaction: (tr) => this.dispatch(tr),
      handleClick: (view, pos) => {
        if (this.mode !== 'read') return false
        const target = targetAtPos(view.state, pos)
        if (!target) return false
        openTarget(target)
        return true
      },
      clipboardTextSerializer: (slice) => sliceToText(slice),
      clipboardTextParser: (text, _$context, plain) => (settings.markdownShortcuts && !plain ? markdownSlice(text) : (null as unknown as Slice)),
      handleDOMEvents: {
        // The page must never navigate: links open through the app (outside editing mode an
        // anchor would otherwise be followed by the WebView itself).
        click: (_view, event) => {
          if (event.target instanceof Element && event.target.closest('a')) event.preventDefault()
          return false
        },
        focus: (view) => {
          view.dispatch(view.state.tr.setMeta('focus', true))
          return false
        },
        blur: (view) => {
          view.dispatch(view.state.tr.setMeta('focus', false))
          this.emitChange()
          return false
        },
      },
      scrollMargin: 48,
      scrollThreshold: 48,
    })

    this.title.addEventListener('input', () => {
      this.fitTitle()
      this.markDirty()
    })
    this.title.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault()
        this.focusEditor('start')
      }
    })
    this.source.addEventListener('input', () => {
      this.fitSource()
      this.markDirty()
    })
    // Tapping below the text puts the cursor at the end, as in a paper notebook.
    root.querySelector('#below')?.addEventListener('click', () => {
      if (this.mode === 'edit') this.focusEditor('end')
      else if (this.mode === 'source') this.source.focus()
    })
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') this.emitChange()
    })
    const keepCursorVisible = () => {
      if (this.mode === 'edit' && this.view.hasFocus()) {
        requestAnimationFrame(() => this.view.dispatch(this.view.state.tr.scrollIntoView()))
      }
    }
    window.visualViewport?.addEventListener('resize', keepCursorVisible)
    window.addEventListener('resize', keepCursorVisible)
  }

  private createState(doc: Node): EditorState {
    return EditorState.create({
      doc,
      plugins: [
        markdownInputRules(),
        suggestPlugin(() => this.notes.titles, () => this.mode),
        keymap({
          'Mod-z': undo,
          'Shift-Mod-z': redo,
          'Mod-y': redo,
          'Mod-b': toggleMark(marks.strong),
          'Mod-i': toggleMark(marks.em),
          'Mod-e': toggleMark(marks.code),
          'Mod-Shift-s': toggleMark(marks.strike),
          'Mod-Shift-h': toggleMark(marks.highlight),
          'Mod-k': () => {
            post({type: 'editLink', ...currentLink(this.view.state)})
            return true
          },
          Enter: chainCommands(enterShortcut, enterInTable, splitItemCommand),
          'Shift-Enter': hardBreakCommand,
          Backspace: chainCommands(undoInputRule, backspaceAtStart),
          Tab: chainCommands(indentCommand, () => true),
          'Shift-Tab': chainCommands(outdentCommand, () => true),
        }),
        keymap(baseKeymap),
        history({newGroupDelay: 600}),
        dropCursor(),
        gapCursor(),
        tableEditing(),
        structurePlugin(),
        decorationsPlugin(),
        bubblePlugin(() => this.mode),
        toolbarPlugin(),
      ],
    })
  }

  private dispatch(tr: Transaction) {
    const state = this.view.state.apply(tr)
    this.view.updateState(state)
    if (tr.docChanged) this.markDirty()
  }

  private markDirty() {
    this.dirty = true
    if (this.saveTimer) clearTimeout(this.saveTimer)
    this.saveTimer = setTimeout(() => this.emitChange(), SAVE_DELAY_MS)
  }

  /** The note as markdown right now. */
  private currentMarkdown(): {markdown: string; exact: boolean} {
    if (this.mode === 'source') return {markdown: this.source.value, exact: true}
    const r = serializeMarkdown(this.view.state.doc)
    if (!r.exact && !this.warnedInexact) {
      this.warnedInexact = true
      post({type: 'warning', code: 'inexact', message: 'Some formatting in this note could not be saved exactly.'})
    }
    return {markdown: r.markdown, exact: r.exact}
  }

  /**
   * Computes the pending change, if any, and marks it as reported. A change that cannot be
   * computed stays pending and comes back with `error`.
   */
  flush(): FlushResult {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer)
      this.saveTimer = null
    }
    const title = this.title.value
    if (!this.dirty) return {dirty: false, markdown: '', title, exact: true, seq: this.seq}
    let r: {markdown: string; exact: boolean}
    try {
      r = this.currentMarkdown()
    } catch (e) {
      return {dirty: true, markdown: '', title, exact: false, seq: this.seq, error: e instanceof Error ? e.message : String(e)}
    }
    this.dirty = false
    this.seq++
    return {dirty: true, markdown: r.markdown, title, exact: r.exact, seq: this.seq}
  }

  /** Reports the pending change to the app, if any. */
  private emitChange() {
    const r = this.flush()
    if (r.error !== undefined) {
      if (!this.failing) post({type: 'warning', code: 'serializeFailed', message: r.error})
      this.failing = true
      return
    }
    this.failing = false
    if (r.dirty) post({type: 'changed', markdown: r.markdown, title: r.title, exact: r.exact, seq: r.seq})
  }

  load(p: LoadPayload) {
    if (p.settings) this.setSettings(p.settings)
    if (p.notes) this.notes = p.notes
    if (this.saveTimer) clearTimeout(this.saveTimer)
    this.saveTimer = null
    this.dirty = false
    this.warnedInexact = false
    this.failing = false
    this.placeholder = false
    this.title.value = p.title
    this.fitTitle()
    let mode: Mode = p.mode ?? 'edit'
    let doc: Node
    try {
      doc = parseMarkdown(p.markdown)
    } catch (e) {
      // Never risk a note the editor cannot read faithfully: show it as source instead.
      if (!(e instanceof MarkdownParseError)) throw e
      post({type: 'warning', code: 'parseFailed', message: e.message})
      doc = schema.nodes.doc.createAndFill()!
      mode = 'source'
      this.placeholder = true
    }
    this.view.updateState(this.createState(doc))
    this.view.dispatch(this.view.state.tr.setMeta(decorationsKey, {keys: this.notes.keys}))
    this.source.value = p.markdown
    this.sourceBaseline = p.markdown
    this.applyMode(mode, false)
    window.scrollTo(0, 0)
    if (p.focus === 'title' && mode === 'edit') this.title.focus()
    else if (p.focus === 'end' && mode === 'edit') this.focusEditor('end')
  }

  setSettings(next: Partial<EditorSettings>) {
    applySettings(next)
    this.title.spellcheck = settings.spellcheck
    this.source.spellcheck = false
    this.view.setProps({})
    this.fitTitle()
  }

  setNotes(notes: NoteNames) {
    this.notes = notes
    this.view.dispatch(this.view.state.tr.setMeta(decorationsKey, {keys: notes.keys}))
  }

  getMode(): Mode {
    return this.mode
  }

  setMode(mode: Mode) {
    this.applyMode(mode, true)
  }

  private applyMode(mode: Mode, fromUser: boolean) {
    const previous = this.mode
    if (previous === 'source' && mode !== 'source' && fromUser) {
      const text = this.source.value
      if (text !== this.sourceBaseline || this.placeholder) {
        let doc: Node
        try {
          doc = parseMarkdown(text)
        } catch (e) {
          if (!(e instanceof MarkdownParseError)) throw e
          post({type: 'warning', code: 'sourceParseFailed', message: e.message})
          post({type: 'modeChanged', mode: 'source'})
          return
        }
        if (this.placeholder) {
          // Nothing to undo back to: the stand-in was never the note.
          const pending = this.dirty
          this.view.updateState(this.createState(doc))
          this.view.dispatch(this.view.state.tr.setMeta(decorationsKey, {keys: this.notes.keys}))
          this.dirty = pending
          this.placeholder = false
        } else {
          // Undoable, like any other edit.
          const tr = this.view.state.tr.replaceWith(0, this.view.state.doc.content.size, doc.content)
          tr.setSelection(Selection.atStart(tr.doc))
          this.view.dispatch(tr)
        }
      }
    }
    if (mode === 'source' && previous !== 'source') {
      const pending = this.dirty
      let text = this.source.value
      if (fromUser) {
        try {
          text = serializeMarkdown(this.view.state.doc).markdown
        } catch (e) {
          post({type: 'warning', code: 'serializeFailed', message: e instanceof Error ? e.message : String(e)})
          post({type: 'modeChanged', mode: previous})
          return
        }
      }
      this.source.value = text
      this.sourceBaseline = text
      this.dirty = pending
    }
    this.mode = mode
    document.body.dataset.mode = mode
    this.title.readOnly = mode === 'read'
    this.view.dom.hidden = mode === 'source'
    this.source.hidden = mode !== 'source'
    this.view.setProps({})
    this.fitSource()
    this.view.dispatch(this.view.state.tr.setMeta('mode', mode))
    post({type: 'modeChanged', mode})
  }

  /**
   * Shows the heading or block a link's `#anchor` names (see anchorPosition) at the top of the
   * screen, with the cursor at its start. Returns whether it was found.
   */
  scrollToAnchor(anchor: string): boolean {
    if (this.mode === 'source') return false
    const pos = anchorPosition(this.view.state.doc, anchor)
    if (pos === null) return false
    this.view.dispatch(this.view.state.tr.setSelection(TextSelection.create(this.view.state.doc, pos + 1)))
    const dom = this.view.nodeDOM(pos)
    if (dom instanceof HTMLElement) dom.scrollIntoView({block: 'start'})
    return true
  }

  focusEditor(where: 'start' | 'end') {
    if (this.mode === 'source') {
      this.source.focus()
      return
    }
    if (this.mode !== 'edit') return
    const sel = where === 'start' ? Selection.atStart(this.view.state.doc) : Selection.atEnd(this.view.state.doc)
    this.view.dispatch(this.view.state.tr.setSelection(sel).scrollIntoView())
    this.view.focus()
  }

  private fitTitle() {
    this.title.style.height = 'auto'
    this.title.style.height = `${this.title.scrollHeight}px`
  }

  private fitSource() {
    if (this.source.hidden) return
    this.source.style.height = 'auto'
    this.source.style.height = `${this.source.scrollHeight}px`
  }

  // ---- Find in note ---------------------------------------------------------------------

  find(query: string) {
    this.view.dispatch(this.view.state.tr.setMeta(decorationsKey, {query}))
    this.revealMatch()
  }

  findStep(delta: number) {
    const s = decorationState(this.view.state)
    this.view.dispatch(this.view.state.tr.setMeta(decorationsKey, {current: s.current + delta}))
    this.revealMatch()
  }

  private revealMatch() {
    const s = decorationState(this.view.state)
    post({type: 'findResult', count: s.matches.length, current: s.matches.length ? s.current + 1 : 0})
    const m = s.matches[s.current]
    if (!m) return
    const coords = this.view.coordsAtPos(m.from)
    const vv = window.visualViewport
    const height = vv ? vv.height : window.innerHeight
    if (coords.top < 60 || coords.bottom > height - 60) {
      window.scrollTo({top: window.scrollY + coords.top - height / 3, behavior: 'smooth'})
    }
  }

  // ---- Commands from the toolbar --------------------------------------------------------

  command(name: string, args: Record<string, unknown> = {}): boolean {
    if (name === 'undo' && this.mode === 'source') return document.execCommand('undo')
    if (name === 'redo' && this.mode === 'source') return document.execCommand('redo')
    if (this.mode !== 'edit') return false
    const state = this.view.state
    let cmd: Command | null = null
    switch (name) {
      case 'undo':
        cmd = undo
        break
      case 'redo':
        cmd = redo
        break
      case 'bold':
        cmd = toggleMark(marks.strong)
        break
      case 'italic':
        cmd = toggleMark(marks.em)
        break
      case 'strike':
        cmd = toggleMark(marks.strike)
        break
      case 'highlight':
        cmd = toggleMark(marks.highlight)
        break
      case 'code':
        cmd = toggleMark(marks.code)
        break
      case 'heading':
        cmd = headingCommand(Number(args.level ?? 0))
        break
      case 'bulletList':
        cmd = listCommand(nodes.bullet_list)
        break
      case 'orderedList':
        cmd = listCommand(nodes.ordered_list)
        break
      case 'taskList':
        cmd = listCommand(nodes.bullet_list, true)
        break
      case 'blockquote':
        cmd = blockquoteCommand
        break
      case 'codeBlock':
        cmd = codeBlockCommand
        break
      case 'horizontalRule':
        cmd = horizontalRuleCommand
        break
      case 'indent':
        cmd = indentCommand
        break
      case 'outdent':
        cmd = outdentCommand
        break
      case 'clearFormatting':
        cmd = clearFormattingCommand
        break
      case 'footnote':
        cmd = insertFootnoteCommand
        break
      case 'link':
        // The app shows its link dialog and answers with setLink.
        post({type: 'editLink', ...currentLink(state)})
        return true
      case 'setLink':
        cmd = setLinkCommand({text: String(args.text ?? ''), href: String(args.href ?? ''), title: (args.title as string) ?? null})
        break
      case 'removeLink':
        cmd = removeLinkCommand
        break
      case 'insertWikilink':
        cmd = insertWikilinkCommand(String(args.target ?? ''), (args.alias as string) ?? null, !!args.embed)
        break
      case 'insertTable':
        cmd = insertTableCommand(Number(args.rows ?? 3), Number(args.cols ?? 3))
        break
      case 'alignColumn':
        cmd = alignColumnCommand((args.align as 'left' | 'center' | 'right' | null) ?? null)
        break
      default:
        cmd = tableCommands[name] ?? null
    }
    if (!cmd) return false
    const ok = cmd(this.view.state, (tr) => this.view.dispatch(tr), this.view)
    this.view.focus()
    return ok
  }
}

/** Plain text for the clipboard: markdown with shortcuts on, else just the text. */
function sliceToText(slice: Slice): string {
  const plain = () => slice.content.textBetween(0, slice.content.size, '\n\n', '\n')
  if (!settings.markdownShortcuts) return plain()
  let content = slice.content
  if (content.firstChild?.isInline) content = Fragment.from(schema.nodes.paragraph.create(null, content))
  const doc = schema.nodes.doc.createAndFill(null, content)
  if (!doc) return plain()
  return serializeMarkdown(doc).markdown.replace(/\n+$/, '')
}

/**
 * Pasted plain text, read as markdown (when shortcuts are on). Front matter can only open a
 * note, so pasted front matter becomes a YAML code block.
 */
function markdownSlice(text: string): Slice {
  let content = parseMarkdown(text).content
  const first = content.firstChild
  if (first?.type === schema.nodes.frontmatter) {
    const yaml = schema.nodes.code_block.create({language: 'yaml'}, first.content)
    content = content.replaceChild(0, yaml)
  }
  return Slice.maxOpen(content)
}
