// Shared rendering bridge for XaliCrates web editor: configures editor-core Assets, Pack, ItemRenderer,
// Minecraft Font Text renderer, Tooltip renderer, and Nexo/Oraxen/ItemsAdder glyph & shift expansion.

import { Assets, setMinecraftVersion, settings as assetSettings } from './shared/assets.js'
import { Pack } from './shared/pack.js'
import { Text } from './shared/text.js'
import { ItemRenderer } from './shared/itemrender.js'
import { icon, settings as iconSettings } from './shared/icons.js'
import { expandPlaceholders } from './shared/placeholders.js'
import { TooltipView } from './shared/tooltip.js'
import { LATEST_VERSION, PACK_FORMATS } from './shared/versions.js'

export const renderEnv = {
  pack: null,
  packLabel: '',
  packSummary: null,
  version: null,
  assets: null,
  text: null,
  renderer: null,
  tooltip: null,
  glyphCatalog: [],
  fontIndex: null,
}

export function configureRenderer({ pack = renderEnv.pack, version = renderEnv.version } = {}) {
  if (version) {
    renderEnv.version = version
    setMinecraftVersion(version)
  }
  renderEnv.pack = pack
  renderEnv.assets = new Assets({ pack, version: renderEnv.version })
  renderEnv.text = new Text(renderEnv.assets, () => renderEnv.pack)
  renderEnv.text.preprocess = (t) => expandPlaceholders(t, renderEnv.glyphCatalog, renderEnv.fontIndex)
  renderEnv.renderer = new ItemRenderer(renderEnv.assets)
  renderEnv.tooltip = new TooltipView(renderEnv.text, renderEnv.assets)
  iconSettings.pack = pack
  iconSettings.renderer = renderEnv.renderer
}

export async function loadResourcePack(source, label = 'server pack') {
  const targetFormat = renderEnv.assets?.packFormat ?? null
  const pack = await Pack.load(source, label, targetFormat)
  configureRenderer({ pack })
  const idx = new Map()
  for (const k of pack.fontKeys()) {
    const f = await pack.font(k)
    for (const ch of f.glyphs.keys()) if (!idx.has(ch)) idx.set(ch, k)
  }
  renderEnv.fontIndex = idx
  renderEnv.packLabel = label
  renderEnv.packSummary = await pack.summary()
  return pack
}

export function registerResolvedItems(resolvedMap) {
  for (const [k, v] of Object.entries(resolvedMap ?? {})) {
    iconSettings.resolved.set(k, v)
  }
}

export function renderItemIcon(item, scale = 2, { contain = true } = {}) {
  return icon(item ?? { material: 'chest' }, scale, 1, { contain })
}

export function renderItemTooltip(item, scale = 2) {
  if (!renderEnv.tooltip) return null
  return renderEnv.tooltip.draw(item ?? { material: 'chest' }, {}, { scale })
}

export { PACK_FORMATS, LATEST_VERSION, assetSettings }

configureRenderer()
