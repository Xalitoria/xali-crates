// End-to-end encrypted relay client for XaliCrates using the shared `editor-core` relay crypto primitive.
// Domain-separated from XaliMenu with salt `xalicrates:session:v1` and AAD prefix `xc1`.

import { createRelayCrypto, bytesOf, base64 } from './shared/relayCore.js'
import { BlockCache, RangeFile } from './shared/remote.js'

const { deriveCode, seal, open, importKey } = createRelayCrypto({
  salt: 'xalicrates:session:v1',
  aadPrefix: 'xc1',
})

export { deriveCode, seal, open, importKey }

const decoder = new TextDecoder()

export class RelaySource {
  constructor(relayUrl, code, from = '') {
    this.mode = 'relay'
    this.url = relayUrl.replace(/\/+$/, '')
    this.from = from
    this.credsPromise = deriveCode(code)
    this.infoData = null
  }

  async requestRaw(path, options = {}) {
    const { session, key } = await this.credsPromise
    const rid = base64(globalThis.crypto.getRandomValues(new Uint8Array(12)))
    const payload = {
      rid,
      t: Date.now(),
      from: this.from,
      method: options.method ?? 'GET',
      path: path.startsWith('/') ? path : `/${path}`,
      body: options.body ?? null,
      range: options.headers?.Range ?? null,
    }
    let response
    try {
      response = await fetch(`${this.url}/s/${session}/call`, {
        method: 'POST',
        body: await seal(key, session, 'q', payload),
      })
    } catch (error) {
      const err = new Error(`Cannot reach relay at ${this.url}: ${error.message}`)
      err.offline = true
      throw err
    }
    if (response.status === 404) {
      const err = new Error('This editor link has expired or the server restarted. Run /xcrates web again.')
      err.offline = true
      throw err
    }
    if (response.status === 503) {
      const parsed = await response.json().catch(() => ({}))
      const err = new Error(parsed.error ?? 'The Minecraft server disconnected. Reconnect with a new /xcrates web link.')
      err.offline = true
      throw err
    }
    if (response.status === 504) {
      const err = new Error('The Minecraft server did not respond in time.')
      err.offline = true
      throw err
    }
    if (!response.ok) throw new Error(`Relay returned HTTP ${response.status}`)

    let answer
    try {
      answer = await open(key, session, 'a', new Uint8Array(await response.arrayBuffer()))
    } catch {
      throw new Error('Could not decrypt server answer (invalid link code).')
    }
    if (answer.rid !== rid) throw new Error('Mismatched response id from relay.')
    const body = bytesOf(answer.body ?? '')
    if (answer.status === 401) {
      const err = new Error('This editor link has ended.')
      err.offline = true
      throw err
    }
    if (answer.status >= 400) {
      const text = decoder.decode(body)
      const parsed = (() => { try { return JSON.parse(text) } catch { return {} } })()
      throw new Error(parsed.error ?? `Server returned HTTP ${answer.status}`)
    }
    return { status: answer.status, type: answer.type ?? 'application/json', body }
  }

  async request(path, options = {}) {
    const { body } = await this.requestRaw(path, options)
    const text = decoder.decode(body)
    return text ? JSON.parse(text) : {}
  }

  async info() {
    this.infoData = await this.request('/info')
    return this.infoData
  }
  async registries() { return (await this.request('/registries')).registries ?? {} }
  async glyphs() { return (await this.request('/glyphs')).glyphs ?? [] }
  async items(materials) { return (await this.request('/items', { method: 'POST', body: JSON.stringify({ materials }) })).items ?? {} }
  async packFile(size = null, hash = null) {
    if (size) {
      const cache = BlockCache.forHash(hash)
      return new RangeFile(
        size,
        async (start, end) => (await this.requestRaw('/pack', { headers: { Range: `bytes=${start}-${end - 1}` } })).body,
        1024 * 1024,
        cache,
      )
    }
    const { body } = await this.requestRaw('/pack')
    return new Blob([body], { type: 'application/zip' })
  }
  async skin(url) {
    const { body } = await this.requestRaw(`/skin?url=${encodeURIComponent(url)}`)
    return body
  }
  async loadCrate(id) { return (await this.request(`/crates/${encodeURIComponent(id)}`)).crate }
  async saveCrate(id, crate) {
    return this.request(`/crates/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ crate }) })
  }
  async deleteCrate(id) {
    return this.request(`/crates/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }
  async loadAnimation(id) { return (await this.request(`/animations/${encodeURIComponent(id)}`)).animation }
  async saveAnimation(id, animation) {
    return this.request(`/animations/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ animation }) })
  }
  async deleteAnimation(id) {
    return this.request(`/animations/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }
}
