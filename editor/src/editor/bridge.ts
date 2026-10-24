// Messages from the editor to the Android app.
//
// The app injects `PitchstoneAndroid` (a JavascriptInterface) whose postMessage takes a JSON
// string. Without it (tests, a desktop browser) messages are recorded in
// `window.__pitchstoneMessages` and dispatched as `pitchstone-message` events.

export type OutMessage =
  | {type: 'ready'}
  | {type: 'changed'; markdown: string; title: string; exact: boolean; seq: number}
  | {type: 'state'; state: ToolbarState}
  | {type: 'openLink'; href: string}
  | {type: 'openNote'; target: string}
  | {type: 'openTag'; tag: string}
  /** Images pasted or dropped: the app stores each and answers with insertWikilink (embed). */
  | {type: 'pasteImage'; mime: string; name: string; data: string}
  | {type: 'editLink'; text: string; href: string; title: string | null; existing: boolean}
  | {type: 'editWikilink'; target: string; alias: string | null}
  | {type: 'modeChanged'; mode: Mode}
  | {type: 'findResult'; count: number; current: number}
  | {type: 'warning'; code: 'parseFailed' | 'inexact' | 'sourceParseFailed' | 'serializeFailed' | 'imageTooLarge'; message: string}

export type Mode = 'edit' | 'read' | 'source'

export interface ToolbarState {
  strong: boolean
  em: boolean
  strike: boolean
  highlight: boolean
  code: boolean
  link: boolean
  /** paragraph, heading, code_block, ... */
  block: string
  headingLevel: number
  list: 'bullet' | 'ordered' | null
  task: boolean
  quote: boolean
  table: boolean
  canUndo: boolean
  canRedo: boolean
  canIndent: boolean
  canOutdent: boolean
}

interface AndroidBridge {
  postMessage(json: string): void
}

declare global {
  interface Window {
    PitchstoneAndroid?: AndroidBridge
    __pitchstoneMessages?: OutMessage[]
  }
}

export function post(message: OutMessage): void {
  if (window.PitchstoneAndroid) {
    window.PitchstoneAndroid.postMessage(JSON.stringify(message))
    return
  }
  ;(window.__pitchstoneMessages ??= []).push(message)
  window.dispatchEvent(new CustomEvent('pitchstone-message', {detail: message}))
}
