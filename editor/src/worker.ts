// Turns notes into markdown off the page's main thread, for autosave (PitchstoneEditor.emitChange):
// on a long note serializing takes long enough to stall typing. It receives the document as
// JSON and answers with its markdown, or with the error that stopped it.

import {Node} from 'prosemirror-model'
import {serializeMarkdown} from './markdown/serialize'
import {schema} from './schema'

export interface SerializeRequest {
  id: number
  doc: unknown
}

export type SerializeResponse = {id: number; markdown: string; exact: boolean} | {id: number; error: string}

const scope = self as unknown as {onmessage: ((e: MessageEvent<SerializeRequest>) => void) | null; postMessage(r: SerializeResponse): void}

scope.onmessage = (e) => {
  const {id, doc} = e.data
  try {
    const r = serializeMarkdown(Node.fromJSON(schema, doc))
    scope.postMessage({id, markdown: r.markdown, exact: r.exact})
  } catch (err) {
    scope.postMessage({id, error: err instanceof Error ? err.message : String(err)})
  }
}
