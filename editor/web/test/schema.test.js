import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { defaultObject } from '../src/schema.js'
import { renderMcHtml, stripMc } from '../src/mctext.js'

const schema = JSON.parse(readFileSync(new URL('../schema.json', import.meta.url), 'utf8'))

test('schema.json defines CrateConfig and AnimationConfig with defaults', () => {
  assert.equal(schema.crateRoot, 'CrateConfig')
  assert.equal(schema.animationRoot, 'AnimationConfig')
  assert.ok(schema.types.CrateConfig)
  assert.ok(schema.types.RewardConfig)
  assert.ok(schema.types.AnimationConfig)
  assert.ok(schema.types.AnimationStage)

  const reward = defaultObject(schema, 'RewardConfig')
  assert.equal(reward.weight, 10)
  assert.equal(reward.rarity, 'default')
  assert.equal(reward.redeem.mode, 'AUTO')
})

test('mctext renders legacy colors, hex, and Xali gradients', () => {
  assert.equal(stripMc('{#a0a>}&lXALI{#fff<}&f&lCRATES'), 'XALICRATES')
  const html = renderMcHtml('&d&lStarter &#55ff55Crate')
  assert.ok(html.includes('#ff55ff'))
  assert.ok(html.includes('#55ff55'))
  assert.ok(html.includes('font-weight:700'))
})
