// Entry point of the editor page. The app drives it through `window.inkdown`; every argument
// that carries data is a JSON string, so no character can break out of the call.

import {post, type Mode} from './editor/bridge'
import {InkdownEditor, type LoadPayload} from './editor/editor'
import type {EditorSettings} from './editor/settings'

export interface InkdownApi {
  load(json: string): void
  setSettings(json: string): void
  setNoteTitles(json: string): void
  setMode(mode: Mode): void
  getMode(): Mode
  /** Runs a toolbar command; `json` holds its arguments. Returns whether it applied. */
  command(name: string, json?: string): boolean
  /** The pending change as JSON (see FlushResult), and marks it as reported. */
  flush(): string
  focus(where: 'start' | 'end'): void
  find(query: string): void
  findNext(): void
  findPrevious(): void
}

declare global {
  interface Window {
    inkdown: InkdownApi
  }
}

const editor = new InkdownEditor(document.getElementById('app')!)

window.inkdown = {
  load: (json) => editor.load(JSON.parse(json) as LoadPayload),
  setSettings: (json) => editor.setSettings(JSON.parse(json) as Partial<EditorSettings>),
  setNoteTitles: (json) => editor.setNoteTitles(JSON.parse(json) as string[]),
  setMode: (mode) => editor.setMode(mode),
  getMode: () => editor.getMode(),
  command: (name, json) => editor.command(name, json ? JSON.parse(json) : {}),
  flush: () => JSON.stringify(editor.flush()),
  focus: (where) => editor.focusEditor(where),
  find: (query) => editor.find(query),
  findNext: () => editor.findStep(1),
  findPrevious: () => editor.findStep(-1),
}

post({type: 'ready'})
