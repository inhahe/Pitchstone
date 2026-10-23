// Entry point of the editor page. The app drives it through `window.pitchstone`; every argument
// that carries data is a JSON string, so no character can break out of the call.

import {post, type Mode} from './editor/bridge'
import {PitchstoneEditor, type LoadPayload, type NoteNames} from './editor/editor'
import type {EditorSettings} from './editor/settings'

export interface PitchstoneApi {
  load(json: string): void
  setSettings(json: string): void
  /** The other notes (see NoteNames). */
  setNotes(json: string): void
  setMode(mode: Mode): void
  getMode(): Mode
  /** Runs a toolbar command; `json` holds its arguments. Returns whether it applied. */
  command(name: string, json?: string): boolean
  /** The pending change as JSON (see FlushResult), and marks it as reported. */
  flush(): string
  focus(where: 'start' | 'end'): void
  /** Scrolls to the heading or `^block` a link's anchor names; whether it was found. */
  scrollToAnchor(anchor: string): boolean
  find(query: string): void
  findNext(): void
  findPrevious(): void
}

declare global {
  interface Window {
    pitchstone: PitchstoneApi
  }
}

const editor = new PitchstoneEditor(document.getElementById('app')!)

window.pitchstone = {
  load: (json) => editor.load(JSON.parse(json) as LoadPayload),
  setSettings: (json) => editor.setSettings(JSON.parse(json) as Partial<EditorSettings>),
  setNotes: (json) => editor.setNotes(JSON.parse(json) as NoteNames),
  setMode: (mode) => editor.setMode(mode),
  getMode: () => editor.getMode(),
  command: (name, json) => editor.command(name, json ? JSON.parse(json) : {}),
  flush: () => JSON.stringify(editor.flush()),
  focus: (where) => editor.focusEditor(where),
  scrollToAnchor: (anchor) => editor.scrollToAnchor(anchor),
  find: (query) => editor.find(query),
  findNext: () => editor.findStep(1),
  findPrevious: () => editor.findStep(-1),
}

post({type: 'ready'})
