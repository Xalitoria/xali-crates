import test from 'node:test'
import assert from 'node:assert/strict'
import { deriveCode, open, seal } from '../src/relay.js'
import { parseHash } from '../src/source.js'

test('deriveCode + seal/open round-trips and rejects wrong direction or session', async () => {
  const { session, key } = await deriveCode('AbCdEf0123_-')
  assert.equal(session.length, 22)

  const payload = { rid: 'r1', method: 'GET', path: '/info' }
  const encrypted = await seal(key, session, 'q', payload)
  const decrypted = await open(key, session, 'q', encrypted)
  assert.deepEqual(decrypted, payload)

  await assert.rejects(() => open(key, session, 'a', encrypted))
  await assert.rejects(() => open(key, 'wrong-session', 'q', encrypted))
})

test('parseHash recognizes short relay code, direct API, and local fallback', () => {
  assert.deepEqual(parseHash('#AbCdEf0123_-'), {
    mode: 'relay',
    code: 'AbCdEf0123_-',
    relay: 'https://crate.xali.to/relay',
    crate: null,
  })
  assert.deepEqual(parseHash('#api=http%3A%2F%2F127.0.0.1%3A8124&token=abc&crate=starter'), {
    mode: 'direct',
    api: 'http://127.0.0.1:8124',
    token: 'abc',
    crate: 'starter',
  })
  assert.deepEqual(parseHash(''), { mode: 'local' })
})
