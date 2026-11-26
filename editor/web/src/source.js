// Connection sources: direct HTTP (#api=...&token=...), encrypted relay (#<code>), or standalone local mode.

import { RelaySource } from './relay.js'
import { starterAnimation, starterCrate } from './schema.js'
import { BlockCache, RangeFile } from './shared/remote.js'

const CODE_RE = /^[A-Za-z0-9_-]{12}$/
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

export function defaultRelay() {
  const origin = globalThis.location?.origin
  if (origin && /^https?:\/\//.test(origin) && !/localhost|127\.0\.0\.1/.test(origin)) {
    return `${origin}/relay`
  }
  return 'https://crate.xali.to/relay'
}

/**
 * Validates an editor endpoint or relay URL: permits only `https:` or loopback `http:` (`127.0.0.1` / `localhost`).
 * Rejects `javascript:`, `data:`, plaintext remote `http:`, embedded credentials, and malformed URLs.
 */
export function isSafeEndpoint(raw) {
  try {
    const uri = new URL(String(raw ?? '').trim())
    if (uri.username || uri.password) return false
    if (uri.protocol === 'https:') return Boolean(uri.hostname)
    if (uri.protocol === 'http:') return LOOPBACK_HOSTS.has(uri.hostname.toLowerCase())
    return false
  } catch {
    return false
  }
}

/** Parses a pasted editor link (`https://crate.xali.to/#<code>`), raw fragment (`#<code>`), or bare 12-char code. */
export function parseLinkInput(raw) {
  const text = String(raw ?? '').trim()
  if (!text) return { mode: 'local' }
  if (CODE_RE.test(text)) return parseHash(`#${text}`)
  const hashIdx = text.indexOf('#')
  if (hashIdx >= 0) return parseHash(text.slice(hashIdx))
  return parseHash(text)
}

export function parseHash(hashStr) {
  const raw = String(hashStr ?? '').replace(/^#/, '').trim()
  if (!raw) return { mode: 'local' }
  const firstPart = raw.split('&')[0]
  if (CODE_RE.test(firstPart)) {
    const params = new URLSearchParams(raw.includes('&') ? raw.slice(firstPart.length + 1) : '')
    const rawRelay = params.get('relay')
    if (rawRelay && !isSafeEndpoint(rawRelay)) {
      return { mode: 'invalid', error: 'Refused unsafe relay URL (must use https:// or localhost).' }
    }
    return {
      mode: 'relay',
      code: firstPart,
      relay: (rawRelay || defaultRelay()).replace(/\/+$/, ''),
      crate: params.get('crate') || null,
    }
  }
  const params = new URLSearchParams(raw)
  if (params.get('api') && params.get('token')) {
    const rawApi = params.get('api').replace(/\/+$/, '')
    if (!isSafeEndpoint(rawApi)) {
      return { mode: 'invalid', error: 'Refused unsafe server API URL (must use https:// or localhost).' }
    }
    return {
      mode: 'direct',
      api: rawApi,
      token: params.get('token'),
      crate: params.get('crate') || null,
    }
  }
  return { mode: 'local' }
}

export class DirectSource {
  constructor(apiUrl, token, from = '') {
    this.mode = 'direct'
    this.apiUrl = apiUrl.replace(/\/+$/, '')
    this.token = token
    this.from = from
    this.infoData = null
  }

  async requestRaw(path, options = {}) {
    const headers = {
      Authorization: `Bearer ${this.token}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(this.from ? { 'X-Editor-From': this.from } : {}),
      ...(options.headers ?? {}),
    }
    let res
    try {
      res = await fetch(`${this.apiUrl}/v1${path}`, {
        method: options.method ?? 'GET',
        headers,
        body: options.body,
      })
    } catch (error) {
      const err = new Error(`Cannot reach ${this.apiUrl}. The server may be offline (${error.message}).`)
      err.offline = true
      throw err
    }
    if (res.status === 401) {
      const err = new Error('This editor link has expired. Run /xcrates web again.')
      err.offline = true
      throw err
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      const parsed = (() => { try { return JSON.parse(text) } catch { return {} } })()
      const err = new Error(parsed.error ?? `HTTP ${res.status}`)
      if (res.status === 503 || res.status === 504) err.offline = true
      throw err
    }
    return res
  }

  async request(path, options = {}) {
    const res = await this.requestRaw(path, options)
    const text = await res.text()
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
        async (start, end) => new Uint8Array(await (await this.requestRaw('/pack', { headers: { Range: `bytes=${start}-${end - 1}` } })).arrayBuffer()),
        1024 * 1024,
        cache,
      )
    }
    const res = await this.requestRaw('/pack')
    return res.blob()
  }
  async skin(url) {
    const res = await this.requestRaw(`/skin?url=${encodeURIComponent(url)}`)
    return new Uint8Array(await res.arrayBuffer())
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

export class LocalSource {
  constructor() {
    this.mode = 'local'
    this.crates = { starter: starterCrate() }
    this.animations = { starburst: starterAnimation() }
  }

  async info() {
    return {
      plugin: 'XaliCrates (Standalone)',
      version: 'local',
      owner: 'local',
      shared: false,
      readOnly: false,
      autoReload: false,
      initialCrate: 'starter',
      crates: Object.keys(this.crates),
      animations: Object.keys(this.animations),
      builtinAnimations: ['csgo', 'discover', 'discover-eula', 'enclose', 'instant', 'menu', 'open-chest', 'roulette'],
      redeemAvailable: true,
      economyAvailable: true,
      ipVerified: true,
    }
  }
  async registries() { return {} }
  async glyphs() { return [] }
  async items() { return {} }
  async packFile() { return null }
  async skin() { return null }
  async loadCrate(id) { return structuredClone(this.crates[id]) }
  async saveCrate(id, crate) {
    this.crates[id] = structuredClone(crate)
    return { problems: [], reloaded: false }
  }
  async deleteCrate(id) {
    delete this.crates[id]
    return { problems: [], reloaded: false }
  }
  async loadAnimation(id) { return structuredClone(this.animations[id]) }
  async saveAnimation(id, anim) {
    this.animations[id] = structuredClone(anim)
    return { problems: [], reloaded: false }
  }
  async deleteAnimation(id) {
    delete this.animations[id]
    return { problems: [], reloaded: false }
  }
}

export function createSource(hashStr) {
  const parsed = parseHash(hashStr)
  if (parsed.mode === 'invalid') return { source: new LocalSource(), initialCrate: 'starter', error: parsed.error }
  if (parsed.mode === 'relay') return { source: new RelaySource(parsed.relay, parsed.code), initialCrate: parsed.crate, error: null }
  if (parsed.mode === 'direct') return { source: new DirectSource(parsed.api, parsed.token), initialCrate: parsed.crate, error: null }
  return { source: new LocalSource(), initialCrate: 'starter', error: null }
}
