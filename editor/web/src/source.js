// Connection sources: direct HTTP (#api=...&token=...), encrypted relay (#<code>), or standalone local mode.

import { RelaySource } from './relay.js'
import { starterAnimation, starterCrate } from './schema.js'

const CODE_RE = /^[A-Za-z0-9_-]{12}$/

export function defaultRelay() {
  const origin = globalThis.location?.origin
  if (origin && /^https?:\/\//.test(origin) && !/localhost|127\.0\.0\.1/.test(origin)) {
    return `${origin}/relay`
  }
  return 'https://crate.xali.to/relay'
}

export function parseHash(hashStr) {
  const raw = String(hashStr ?? '').replace(/^#/, '').trim()
  if (!raw) return { mode: 'local' }
  const firstPart = raw.split('&')[0]
  if (CODE_RE.test(firstPart)) {
    const params = new URLSearchParams(raw.includes('&') ? raw.slice(firstPart.length + 1) : '')
    return {
      mode: 'relay',
      code: firstPart,
      relay: params.get('relay') || defaultRelay(),
      crate: params.get('crate') || null,
    }
  }
  const params = new URLSearchParams(raw)
  if (params.get('api') && params.get('token')) {
    return {
      mode: 'direct',
      api: params.get('api').replace(/\/+$/, ''),
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

  async request(path, options = {}) {
    const headers = {
      Authorization: `Bearer ${this.token}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(this.from ? { 'X-Editor-From': this.from } : {}),
    }
    const res = await fetch(`${this.apiUrl}/v1${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body,
    })
    const text = await res.text()
    const json = text ? JSON.parse(text) : {}
    if (!res.ok) throw new Error(json.error ?? `HTTP ${res.status}`)
    return json
  }

  async info() {
    this.infoData = await this.request('/info')
    return this.infoData
  }
  async registries() { return (await this.request('/registries')).registries ?? {} }
  async glyphs() { return (await this.request('/glyphs')).glyphs ?? [] }
  async items(materials) { return (await this.request('/items', { method: 'POST', body: JSON.stringify({ materials }) })).items ?? {} }
  async packBlob() {
    const res = await fetch(`${this.apiUrl}/v1/pack`, { headers: { Authorization: `Bearer ${this.token}` } })
    return res.ok ? res.blob() : null
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
    }
  }
  async registries() { return {} }
  async glyphs() { return [] }
  async items() { return {} }
  async packBlob() { return null }
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
  if (parsed.mode === 'relay') return { source: new RelaySource(parsed.relay, parsed.code), initialCrate: parsed.crate }
  if (parsed.mode === 'direct') return { source: new DirectSource(parsed.api, parsed.token), initialCrate: parsed.crate }
  return { source: new LocalSource(), initialCrate: 'starter' }
}
