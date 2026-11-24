// XaliCrates Web Editor main entry point.

import { AnimationsTab } from './animationsTab.js'
import { validateAnimation, validateCrate } from './check.js'
import { CratesTab } from './cratesTab.js'
import { el, syncRegistryDatalists } from './form.js'
import { RawTab } from './rawTab.js'
import { loadSchema } from './schema.js'
import { createSource } from './source.js'
import { EditorStore } from './store.js'
import { dumpYaml, parseYaml } from './yaml.js'
import { configureRenderer, loadResourcePack, PACK_FORMATS, registerResolvedItems, renderEnv } from './renderer.js'

export async function boot() {
  const root = document.getElementById('app')
  const schema = await loadSchema('./schema.json')
  const { source, initialCrate } = createSource(window.location.hash)
  const store = new EditorStore()

  let info = await source.info()
  if (info.serverVersion) {
    configureRenderer({ version: info.serverVersion })
  }
  if (info.shared) {
    const savedName = localStorage.getItem('xalicrates_editor_from') || ''
    const who = prompt('Enter your name for shared edit submissions:', savedName) || 'guest'
    localStorage.setItem('xalicrates_editor_from', who)
    source.from = who
  }

  // Load all crates & animations from the connected source
  for (const id of info.crates ?? []) {
    try {
      const crate = await source.loadCrate(id)
      if (crate) store.setCrate(id, crate, { markDirty: false, recordUndo: false })
    } catch (e) {
      console.warn(`Could not load crate ${id}:`, e)
    }
  }
  for (const id of info.animations ?? []) {
    try {
      const anim = await source.loadAnimation(id)
      if (anim) store.setAnimation(id, anim, { markDirty: false, recordUndo: false })
    } catch (e) {
      console.warn(`Could not load animation ${id}:`, e)
    }
  }

  const pickInitial = initialCrate || info.initialCrate || Object.keys(store.crates)[0] || null
  if (pickInitial && store.crates[pickInitial]) {
    store.select('crate', pickInitial)
  } else if (Object.keys(store.animations).length > 0) {
    store.select('animation', Object.keys(store.animations)[0])
  }

  // Fetch server registries, Nexo/Oraxen glyphs, custom item models, and resource pack in the background.
  source.registries().then(syncRegistryDatalists).catch(() => {})
  source.glyphs().then((list) => { renderEnv.glyphCatalog = list ?? []; render() }).catch(() => {})
  if (info.pack) {
    source.packBlob().then((blob) => blob && loadResourcePack(blob, 'server pack')).then(() => render()).catch(() => {})
  }
  const customMats = new Set()
  for (const crate of Object.values(store.crates)) {
    for (const m of [crate?.['crate-item']?.material, crate?.key?.item?.material, ...Object.values(crate?.rewards ?? {}).map((r) => r?.['display-item']?.material)]) {
      if (typeof m === 'string' && m.includes(':') && !m.startsWith('minecraft:')) customMats.add(m)
    }
  }
  if (customMats.size > 0) {
    source.items([...customMats]).then((resolved) => { registerResolvedItems(resolved); render() }).catch(() => {})
  }

  let activeTab = 'crates'
  let showProblems = false
  let statusText = source.mode === 'local' ? 'Standalone local mode' : `Connected to ${info.plugin} (${source.mode})`
  let statusKind = ''

  const onDeleteRemote = async (kind, id) => {
    if (source.mode === 'local' || info.readOnly || info.shared) return
    try {
      if (kind === 'crate') await source.deleteCrate(id)
      else await source.deleteAnimation(id)
    } catch (e) {
      statusText = `Delete warning: ${e.message}`
      statusKind = 'err'
    }
  }

  const cratesTab = new CratesTab({ schema, store, getInfo: () => info, onDeleteRemote })
  const animationsTab = new AnimationsTab({ schema, store, getInfo: () => info, onDeleteRemote })
  const rawTab = new RawTab({ store, getInfo: () => info })

  function currentProblems() {
    const { kind, id } = store.active
    const doc = store.activeDocument()
    if (!id || !doc) return []
    if (kind === 'crate') {
      return validateCrate(id, doc, {
        builtinAnimations: info.builtinAnimations ?? schema.builtinAnimations ?? [],
        knownAnimations: Object.keys(store.animations),
        knownCrates: Object.keys(store.crates),
      })
    }
    return validateAnimation(id, doc)
  }

  async function saveActive() {
    const { kind, id } = store.active
    const doc = store.activeDocument()
    if (!id || !doc) return
    const probs = currentProblems()
    const errs = probs.filter((p) => p.severity === 'ERROR')
    if (errs.length > 0) {
      showProblems = true
      statusText = `Cannot save: ${errs.length} error(s) in ${id}`
      statusKind = 'err'
      render()
      return
    }
    try {
      statusText = `Saving ${kind} '${id}'…`
      statusKind = ''
      render()
      const res = kind === 'crate'
        ? await source.saveCrate(id, doc)
        : await source.saveAnimation(id, doc)
      store.clearDirty(kind, id)
      if (res?.held) {
        statusText = `Submitted shared edit #${res.id} for admin approval (/xcrates edits)`
        statusKind = 'shared'
      } else if (res?.reloaded) {
        statusText = `Saved and reloaded ${kind} '${id}'`
        statusKind = 'live'
      } else if (source.mode === 'local') {
        const blob = new Blob([dumpYaml(doc) + '\n'], { type: 'text/yaml;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `${id}.yml`
        a.click()
        URL.revokeObjectURL(url)
        statusText = `Exported ${id}.yml`
        statusKind = 'live'
      } else {
        statusText = `Saved ${kind} '${id}' (run /xcrates reload to apply live)`
        statusKind = 'live'
      }
    } catch (e) {
      statusText = `Save failed: ${e.message}`
      statusKind = 'err'
    }
    render()
  }

  function importYamlFile() {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.yml,.yaml'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      try {
        const parsed = parseYaml(await file.text())
        const baseId = file.name.replace(/\.(ya?ml)$/i, '').toLowerCase().replace(/[^a-z0-9_-]/g, '_') || 'imported'
        if (parsed && Array.isArray(parsed.stages)) {
          store.setAnimation(baseId, parsed)
          store.select('animation', baseId)
          activeTab = 'animations'
        } else {
          store.setCrate(baseId, parsed)
          store.select('crate', baseId)
          activeTab = 'crates'
        }
        statusText = `Imported ${file.name}`
        statusKind = 'live'
      } catch (e) {
        statusText = `Import failed: ${e.message}`
        statusKind = 'err'
      }
      render()
    }
    input.click()
  }

  function render() {
    const probs = currentProblems()
    const errCount = probs.filter((p) => p.severity === 'ERROR').length
    const warnCount = probs.filter((p) => p.severity === 'WARNING').length
    const { kind, id } = store.active
    const dirty = id ? store.isDirty(kind, id) : false

    const connBadge = source.mode === 'local'
      ? el('span', { class: 'pill' }, 'Standalone')
      : info.readOnly
        ? el('span', { class: 'pill' }, 'Read-Only')
        : info.shared
          ? el('span', { class: 'pill shared' }, `Shared · ${source.from || info.owner}`)
          : el('span', { class: 'pill live' }, `Live · ${info.owner}`)

    const versionSelect = el('select', {
      title: 'Minecraft version (determines item models, textures, and pack overlays)',
      style: 'min-height:26px;padding:2px 6px;font-size:12px',
      onChange: (e) => { configureRenderer({ version: e.target.value }); render() },
    }, ...PACK_FORMATS.map((f) => el('option', { value: f.version, selected: (renderEnv.assets?.versionBranch ?? '26.2') === f.branch }, `MC ${f.version}`)))

    const header = el('header', { class: 'topbar' },
      el('div', { class: 'brand' }, el('span', { class: 'xali' }, 'XALI'), 'CRATES', el('span', { class: 'muted', style: 'font-weight:400;font-size:12px' }, `v${schema.version}`)),
      connBadge,
      versionSelect,
      el('span', { class: `pill ${statusKind}` }, statusText),
      el('div', { class: 'spacer' }),
      el('button', { class: 'small', disabled: !store.canUndo(), onClick: () => store.undo() }, 'Undo'),
      el('button', { class: 'small', disabled: !store.canRedo(), onClick: () => store.redo() }, 'Redo'),
      el('button', { class: 'small', onClick: importYamlFile }, 'Import .yml'),
      !info.readOnly ? el('button', {
        class: 'small primary',
        disabled: !id,
        onClick: saveActive,
      }, source.mode === 'local' ? 'Export .yml' : info.shared ? 'Submit Edit' : (dirty ? 'Save*' : 'Save')) : null,
    )

    const nav = el('nav', { class: 'tabs' },
      el('button', { class: activeTab === 'crates' ? 'active' : '', onClick: () => { animationsTab.stopPlayback(); activeTab = 'crates'; render() } }, `Crates (${Object.keys(store.crates).length})`),
      el('button', { class: activeTab === 'animations' ? 'active' : '', onClick: () => { activeTab = 'animations'; render() } }, `Animations (${Object.keys(store.animations).length})`),
      el('button', { class: activeTab === 'raw' ? 'active' : '', onClick: () => { animationsTab.stopPlayback(); activeTab = 'raw'; render() } }, 'Raw YAML'),
      el('div', { class: 'spacer' }),
      el('button', {
        class: 'small',
        onClick: () => { showProblems = !showProblems; render() },
      }, errCount > 0 ? `⚠ ${errCount} Error(s), ${warnCount} Warn` : warnCount > 0 ? `⚠ ${warnCount} Warning(s)` : '✓ Valid'),
    )

    const main = el('main', {})
    if (activeTab === 'crates') main.append(cratesTab.render())
    else if (activeTab === 'animations') main.append(animationsTab.render())
    else main.append(rawTab.render())

    const drawer = el('div', { class: 'problems-bar', hidden: !showProblems })
    if (probs.length === 0) {
      drawer.append(el('div', { class: 'good' }, 'No problems found in the active document.'))
    } else {
      for (const p of probs) {
        drawer.append(el('div', { class: 'problem-row' },
          el('span', { class: `sev ${p.severity === 'ERROR' ? 'bad' : 'warn'}` }, p.severity),
          el('span', { class: 'path' }, p.path),
          el('span', {}, p.message),
        ))
      }
    }

    root.innerHTML = ''
    root.append(header, nav, main, drawer)
  }

  window.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault()
      if (!info.readOnly) saveActive()
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
      if (document.activeElement?.tagName !== 'TEXTAREA' && document.activeElement?.tagName !== 'INPUT') {
        e.preventDefault()
        store.undo()
      }
    } else if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey))) {
      if (document.activeElement?.tagName !== 'TEXTAREA' && document.activeElement?.tagName !== 'INPUT') {
        e.preventDefault()
        store.redo()
      }
    }
  })

  store.subscribe(render)
  render()
}
