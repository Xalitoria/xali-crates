// Resource Pack, Minecraft Version & Nexo/Oraxen/ItemsAdder Glyph Browser tab.

import { el } from './form.js'
import { assetSettings, configureRenderer, loadResourcePack, PACK_FORMATS, renderEnv } from './renderer.js'
import { isSafeEndpoint } from './source.js'
import { mcTextCanvas } from './shared/textpreview.js'

export class PackTab {
  constructor({ onUpdate, onToast, reloadServerPack, hasServer }) {
    this.onUpdate = onUpdate
    this.onToast = onToast
    this.reloadServerPack = reloadServerPack
    this.hasServer = hasServer
    this.glyphFilter = ''
    this.packStatus = ''
    this.packUrlInput = ''
  }

  render() {
    const wrap = el('div', { class: 'pack-page' })

    // 1. Resource Pack loader card
    const fileInput = el('input', {
      type: 'file',
      accept: '.zip',
      onChange: async () => {
        const file = fileInput.files?.[0]
        if (!file) return
        this.packStatus = `Reading ${file.name}…`
        this.onUpdate()
        try {
          await loadResourcePack(file, file.name)
          this.packStatus = this.describeLoadedPack()
          this.onToast?.(`Loaded resource pack: ${file.name}`)
        } catch (err) {
          this.packStatus = `Could not read pack: ${err.message}`
        }
        this.onUpdate()
      },
    })

    const urlInput = el('input', {
      type: 'text',
      placeholder: 'https://…/pack.zip (host must send CORS headers)',
      value: this.packUrlInput,
      style: 'flex:1;min-width:220px',
      onInput: (e) => { this.packUrlInput = e.target.value },
    })

    const loadUrl = async () => {
      const url = this.packUrlInput.trim()
      if (!isSafeEndpoint(url)) {
        this.packStatus = 'URL must use https:// (or http://localhost).'
        this.onUpdate()
        return
      }
      this.packStatus = 'Downloading resource pack…'
      this.onUpdate()
      try {
        const res = await fetch(url)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        await loadResourcePack(await res.blob(), new URL(url).host)
        this.packStatus = this.describeLoadedPack()
        this.onToast?.('Loaded resource pack from URL')
      } catch (err) {
        this.packStatus = `Download failed: ${err.message}`
      }
      this.onUpdate()
    }

    const packButtons = el('div', { class: 'row-inline' },
      this.hasServer() ? el('button', {
        class: 'small primary',
        onClick: async () => {
          this.packStatus = 'Fetching pack from server…'
          this.onUpdate()
          const msg = await this.reloadServerPack()
          this.packStatus = msg || this.describeLoadedPack()
          this.onUpdate()
        },
      }, 'Load from server') : null,
      fileInput,
      renderEnv.pack ? el('button', {
        class: 'small danger',
        onClick: () => {
          renderEnv.packLabel = ''
          renderEnv.packSummary = null
          renderEnv.fontIndex = null
          configureRenderer({ pack: null })
          this.packStatus = 'Cleared resource pack.'
          this.onUpdate()
        },
      }, 'Clear pack') : null,
    )

    const summaryText = this.packStatus || this.describeLoadedPack()
    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' }, el('h3', {}, 'Resource Pack')),
      el('p', { class: 'muted', style: 'margin:0' }, 'Read locally in your browser — never uploaded. Provides custom Nexo/Oraxen/ItemsAdder item models, textures, fonts, and glyphs.'),
      packButtons,
      el('div', { class: 'row-inline' },
        urlInput,
        el('button', { class: 'small', onClick: loadUrl }, 'Download URL'),
      ),
      summaryText ? el('div', { class: 'pill', style: 'align-self:start' }, summaryText) : null,
    ))

    // 2. Minecraft Version card (matching XaliMenu's exact PACK_FORMATS)
    const versionSel = el('select', {
      onChange: (e) => {
        configureRenderer({ version: e.target.value })
        this.onUpdate()
      },
    }, ...PACK_FORMATS.map((entry) => el('option', {
      value: entry.version,
      selected: assetSettings.version === entry.branch,
    }, `Minecraft ${entry.version} (pack_format ${entry.packFormat})`)))

    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' }, el('h3', {}, 'Minecraft Version')),
      el('p', { class: 'muted', style: 'margin:0' }, 'Matches XaliMenu’s supported version lines. Controls vanilla textures, 1.21.4+ item definitions vs legacy overrides, 1.21.6+ oversized_in_gui clipping, and pack overlays.'),
      el('div', { class: 'row-inline' }, versionSel),
    ))

    // 3. Plugin Glyphs browser card
    const glyphs = renderEnv.glyphCatalog ?? []
    const glyphCard = el('div', { class: 'card' })
    const filterInput = el('input', {
      type: 'text',
      placeholder: 'Search glyphs by name or plugin…',
      value: this.glyphFilter,
      style: 'max-width:300px',
    })
    const grid = el('div', { class: 'glyph-grid' })

    const fillGrid = () => {
      const q = this.glyphFilter.trim().toLowerCase()
      const matches = glyphs.filter((g) => !q || g.name.toLowerCase().includes(q) || g.plugin.toLowerCase().includes(q)).slice(0, 120)
      if (matches.length === 0) {
        grid.replaceChildren(el('div', { class: 'muted' }, glyphs.length === 0 ? 'No plugin glyphs discovered. Connect to a server with Nexo or Oraxen installed.' : 'No glyphs match your filter.'))
        return
      }
      const cards = matches.map((g) => {
        const tag = `<glyph:${g.name}>`
        return el('button', {
          class: 'glyph-tile',
          title: `Click to copy ${tag}`,
          onClick: async () => {
            await navigator.clipboard?.writeText(tag)
            this.onToast?.(`Copied ${tag}`)
          },
        },
          el('div', { class: 'glyph-preview' }, mcTextCanvas(renderEnv.text, tag, { scale: 2, color: '#ffffff', shadow: false })),
          el('div', { class: 'glyph-name mono' }, g.name),
          el('div', { class: 'muted', style: 'font-size:10px' }, g.plugin),
        )
      })
      grid.replaceChildren(...cards)
    }

    filterInput.addEventListener('input', (e) => {
      this.glyphFilter = e.target.value
      fillGrid()
    })
    fillGrid()

    glyphCard.append(
      el('div', { class: 'card-head' },
        el('h3', {}, `Plugin Glyphs (${glyphs.length})`),
        filterInput,
      ),
      grid,
    )
    wrap.append(glyphCard)
    return wrap
  }

  describeLoadedPack() {
    if (!renderEnv.pack || !renderEnv.packSummary) return ''
    const s = renderEnv.packSummary
    const fmt = s.packFormat ? ` · pack_format ${s.packFormat}` : ''
    const ov = s.overlays ? ` · ${s.overlays} overlays` : ''
    return `${renderEnv.packLabel || 'Pack'}: ${s.namespaces.length} namespaces, ${s.fonts} fonts, ${s.bitmaps} bitmap providers${fmt}${ov}`
  }
}
