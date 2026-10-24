// Documents from JSON that keep their unchanged textblocks' nodes, for the autosave worker.

import {Fragment, Node} from 'prosemirror-model'
import {schema} from '../schema'

interface NodeJson {
  type: string
  attrs?: Record<string, unknown>
  content?: NodeJson[]
  marks?: unknown[]
}

/**
 * Builds documents from JSON (doc.toJSON()), giving each textblock that is unchanged since the
 * previous document its node from then. The serializer caches what it made of each node, by
 * identity; on the page unchanged blocks stay the same node from edit to edit, so only edited
 * ones are serialized again, and this keeps that true across the worker's requests. Only the
 * previous document's blocks are kept.
 */
export class DocInterner {
  private blocks = new Map<string, Node>()

  fromJSON(json: unknown): Node {
    const next = new Map<string, Node>()
    const build = (j: NodeJson): Node => {
      const type = schema.nodes[j.type]
      if (!type) throw new RangeError(`Unknown node type: ${j.type}`)
      if (type.isTextblock) {
        const key = JSON.stringify(j)
        const node = this.blocks.get(key) ?? next.get(key) ?? Node.fromJSON(schema, j)
        next.set(key, node)
        return node
      }
      if (!j.content) return Node.fromJSON(schema, j)
      const marks = j.marks ? j.marks.map((m) => schema.markFromJSON(m as Parameters<typeof schema.markFromJSON>[0])) : undefined
      return type.create(j.attrs, Fragment.fromArray(j.content.map(build)), marks)
    }
    const doc = build(json as NodeJson)
    this.blocks = next
    return doc
  }
}
