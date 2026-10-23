import type {Node} from 'prosemirror-model'
import {parseMarkdown} from '../../src/markdown/parse'
import {normalizeDoc, serializeMarkdown} from '../../src/markdown/serialize'

/** Returns a description of how the round trip fails for `doc`, or null if it holds. */
export function roundTripProblem(doc: Node): string | null {
  let r
  try {
    r = serializeMarkdown(doc)
  } catch (e) {
    return `serialize threw: ${(e as Error).stack}`
  }
  if (!r.exact) return `not exact:\n${r.markdown}`
  let back: Node
  try {
    back = parseMarkdown(r.markdown)
  } catch (e) {
    return `parse threw: ${(e as Error).message}\n${r.markdown}`
  }
  if (!back.eq(r.doc)) return `reparse differs:\n${r.markdown}`
  const again = serializeMarkdown(back)
  if (again.markdown !== r.markdown) return `not stable:\n${r.markdown}\n---\n${again.markdown}`
  if (!normalizeDoc(r.doc).eq(r.doc)) return 'normalization not idempotent'
  return null
}
