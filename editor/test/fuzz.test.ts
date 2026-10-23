import {describe, expect, it} from 'vitest'
import {Gen, build, rng, shrink} from './fuzz/generate'
import {roundTripProblem} from './fuzz/check'

const CASES = Number(process.env.FUZZ_CASES || 3000)
const SEED = Number(process.env.FUZZ_SEED || 1)
const FROM = Number(process.env.FUZZ_FROM || 0)

describe('round trip of generated documents', () => {
  it(`holds for ${CASES} documents from seed ${SEED}`, () => {
    for (let i = FROM; i < FROM + CASES; i++) {
      const g = new Gen(rng(SEED * 1_000_003 + i))
      const json = g.doc()
      const doc = build(json)
      if (!doc) throw new Error(`generator produced an invalid document: ${JSON.stringify(json)}`)
      const problem = roundTripProblem(doc)
      if (problem) {
        const minimal = shrink(json, (d) => roundTripProblem(d) !== null)
        const minimalDoc = build(minimal)!
        expect.fail(
          `case ${i}: ${roundTripProblem(minimalDoc)}\n\nminimal document:\n${JSON.stringify(minimal)}`,
        )
      }
    }
  })
})
