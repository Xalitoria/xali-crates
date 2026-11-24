import test from 'node:test'
import assert from 'node:assert/strict'
import { dumpYaml, parseYaml } from '../src/yaml.js'
import { starterAnimation, starterCrate } from '../src/schema.js'

test('round-trips starterCrate cleanly through dumpYaml and parseYaml', () => {
  const crate = starterCrate()
  const text = dumpYaml(crate)
  const parsed = parseYaml(text)
  assert.deepEqual(parsed, crate)
})

test('round-trips starterAnimation cleanly through dumpYaml and parseYaml', () => {
  const anim = starterAnimation()
  const text = dumpYaml(anim)
  const parsed = parseYaml(text)
  assert.deepEqual(parsed, anim)
})

test('parses comments, quoted strings, booleans, numbers, and flow arrays', () => {
  const input = `
# crate config
enabled: true
display-name: "&dMagic: #1 Crate"
weight: 12.5
empty-list: []
tags: [a, "b: c", 42]
nested:
  null-val: null
  single: 'it''s fine'
`
  const parsed = parseYaml(input)
  assert.equal(parsed.enabled, true)
  assert.equal(parsed['display-name'], '&dMagic: #1 Crate')
  assert.equal(parsed.weight, 12.5)
  assert.deepEqual(parsed['empty-list'], [])
  assert.deepEqual(parsed.tags, ['a', 'b: c', 42])
  assert.equal(parsed.nested['null-val'], null)
  assert.equal(parsed.nested.single, "it's fine")
})
