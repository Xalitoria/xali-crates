import test from 'node:test'
import assert from 'node:assert/strict'
import { EditorStore } from '../src/store.js'
import { starterCrate } from '../src/schema.js'

test('store tracks dirty state and supports undo/redo', () => {
  const store = new EditorStore()
  store.setCrate('starter', starterCrate(), { markDirty: false, recordUndo: false })
  store.select('crate', 'starter')
  assert.equal(store.isDirty('crate', 'starter'), false)

  store.mutateActive((c) => { c['display-name'] = '&bUpdated' })
  assert.equal(store.crates.starter['display-name'], '&bUpdated')
  assert.equal(store.isDirty('crate', 'starter'), true)
  assert.equal(store.canUndo(), true)

  store.undo()
  assert.equal(store.crates.starter['display-name'], '&dStarter Crate')
  assert.equal(store.canRedo(), true)

  store.redo()
  assert.equal(store.crates.starter['display-name'], '&bUpdated')
})
