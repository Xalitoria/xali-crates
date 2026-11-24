import test from 'node:test'
import assert from 'node:assert/strict'
import { rewardProbabilities, validateAnimation, validateCrate } from '../src/check.js'
import { starterAnimation, starterCrate } from '../src/schema.js'

test('starter crate and animation pass validation with zero errors', () => {
  const crate = starterCrate()
  const anim = starterAnimation()
  const crateProblems = validateCrate('starter', crate, {
    builtinAnimations: ['csgo', 'roulette'],
    knownAnimations: ['starburst'],
    knownCrates: ['starter'],
  })
  assert.equal(crateProblems.filter((p) => p.severity === 'ERROR').length, 0)

  const animProblems = validateAnimation('starburst', anim)
  assert.equal(animProblems.filter((p) => p.severity === 'ERROR').length, 0)
})

test('rewardProbabilities excludes fallback-only and guaranteed rewards from weight pool', () => {
  const { totalWeight, percentages } = rewardProbabilities({
    rewards: {
      a: { weight: 30 },
      b: { weight: 70 },
      fb: { weight: 50, 'fallback-only': true },
      g: { weight: 10, guaranteed: true },
    },
  })
  assert.equal(totalWeight, 100)
  assert.equal(percentages.a, 30)
  assert.equal(percentages.b, 70)
  assert.equal(percentages.fb, 0)
  assert.equal(percentages.g, 100)
})

test('validateCrate flags bad durations, missing fallback rewards, and self-referencing multi-crates', () => {
  const problems = validateCrate('bad', {
    cooldown: { duration: 'not-a-duration' },
    display: { kind: 'ENTITY', entity: null },
    opening: { animation: 'unknown_anim', 'required-slots': 99 },
    rewards: {
      r1: { weight: -1, fallback: { permission: 'p', reward: 'missing' } },
    },
  }, { builtinAnimations: ['csgo'] })
  const paths = new Set(problems.map((p) => p.path))
  assert.ok(paths.has('cooldown.duration'))
  assert.ok(paths.has('display.entity'))
  assert.ok(paths.has('opening.required-slots'))
  assert.ok(paths.has('rewards.r1.weight'))
  assert.ok(paths.has('rewards.r1.fallback.reward'))

  const multi = validateCrate('hub', { 'multi-crate': { enabled: true, crates: ['hub'] } })
  assert.ok(multi.some((p) => p.path === 'multi-crate.crates[0]' && p.severity === 'ERROR'))
})

test('validateAnimation checks 9-column grids and repeat loops', () => {
  const problems = validateAnimation('bad', {
    'inventory-size': 27,
    stages: [
      { grid: ['A,A,A'] },
      { 'repeat-stage': 2, 'repeat-times': 1 },
    ],
  })
  assert.ok(problems.some((p) => p.path === 'stages[0].grid[0]' && p.severity === 'ERROR'))
  assert.ok(problems.some((p) => p.path === 'stages[1].repeat-stage' && p.severity === 'ERROR'))
})
