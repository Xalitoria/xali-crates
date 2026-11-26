// XaliCrates Web Editor main entry point.
// Hardened: strict URL validation, immediate hash credential stripping, upfront IP verification notices,
// IndexedDB range-streamed resource packs, local draft recovery, scroll preservation, and offline reconnect.

import { AnimationsTab } from './animationsTab.js'
import { validateAnimation, validateCrate } from './check.js'
import { CratesTab } from './cratesTab.js'
import { el, syncRegistryDatalists } from './form.js'
import { promptModal } from './modal.js'
import { PackTab } from './packTab.js'
import { RawTab } from './rawTab.js'
import { assetSettings, configureRenderer, loadResourcePack, PACK_FORMATS, registerResolvedItems, renderEnv } from './renderer.js'
import { loadSchema } from './schema.js'
import { createSource, DirectSource, isSafeEndpoint, parseLinkInput } from './source.js'
import { RelaySource } from './relay.js'
import { EditorStore } from './store.js'
import { dumpYaml, parseYaml } from './yaml.js'

const DRAFT_KEY = 'xc-editor-draft'

function readDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed.crates === 'object' && typeof parsed.animations === 'object') return parsed
  } catch { /* ignore storage errors */ }
  return null
}

function writeDraft(store) {
  try {
    if (store.dirty.size === 0) {
      localStorage.removeItem(DRAFT_KEY)
      return
    }
    localStorage.setItem(DRAFT_KEY, JSON.stringify({
      crates: store.crates,
      animations: store.animations,
      dirty: [...store.dirty],
      at: Date.now(),
    }))
  } catch { /* ignore quota errors */ }
}

function clearDraft() {
  try { localStorage.removeItem(DRAFT_KEY) } catch { /* ignore */ }
}

export async function boot() {
  const root = document.getElementById('app')
  const schema = await loadSchema('./schema.json')
  const store = new EditorStore()

  let source
  let info
  let activeTab = 'crates'
  let showProblems = false
  let statusText = ''
  let statusKind = ''
  let serverOffline = false
  let offlineMessage = ''
  let savedDraft = readDraft()
  let toastMessage = ''
  let toastTimer = null

  function showToast(msg) {
    toastMessage = msg
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => { toastMessage = ''; render() }, 3000)
    render()
  }

  async function loadServerPack(currentInfo, currentSource) {
    if (!currentInfo?.pack && !currentInfo?.packUrl) return 'Server has no resource pack configured.'
    if (currentInfo.packUrl && isSafeEndpoint(currentInfo.packUrl)) {
      try {
        const res = await fetch(currentInfo.packUrl)
        if (res.ok) {
          const blob = await res.blob()
          if (!currentInfo.packSize || blob.size === currentInfo.packSize) {
            await loadResourcePack(blob, new URL(currentInfo.packUrl).host)
            render()
            return null
          }
        }
      } catch { /* fall back to range-streamed server pack */ }
    }
    if (currentInfo.pack) {
      try {
        const file = await currentSource.packFile(currentInfo.packSize ?? null, currentInfo.packHash ?? null)
        if (file) {
          await loadResourcePack(file, 'server pack')
          render()
          return null
        }
      } catch (err) {
        return `Could not load server pack: ${err.message}`
      }
    }
    return 'No pack loaded.'
  }

  async function connectTo(nextSource, initialCrate, { keepCurrent = false } = {}) {
    source = nextSource
    serverOffline = false
    offlineMessage = ''

    assetSettings.skinProxy = source.mode === 'local' ? null : (url) => source.skin(url)

    info = await source.info()
    if (info.serverVersion) {
      configureRenderer({ version: info.serverVersion })
    }
    if (info.shared && !info.readOnly && !source.from) {
      const savedName = sessionStorage.getItem('xc-editor-from') || ''
      const who = await promptModal({
        title: 'Shared Editor Session',
        message: 'Enter the name admins will see when approving your edits in /xcrates edits.',
        initialValue: savedName,
        placeholder: 'Your name',
        confirmText: 'Continue',
      })
      const cleanWho = (who || 'guest').trim().slice(0, 32) || 'guest'
      sessionStorage.setItem('xc-editor-from', cleanWho)
      source.from = cleanWho
    }

    if (!keepCurrent) {
      store.crates = {}
      store.animations = {}
      store.dirty.clear()
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
    }

    statusText = source.mode === 'local'
      ? 'Standalone local mode'
      : info.ipVerified === false
        ? `Connected (${info.owner}) · IP approval required`
        : `Connected to ${info.plugin} (${source.mode})`
    statusKind = source.mode === 'local' ? '' : (info.ipVerified === false ? 'shared' : 'live')

    // Fetch server registries, glyphs, custom item metadata, and pack in the background.
    source.registries().then(syncRegistryDatalists).catch(() => {})
    source.glyphs().then((list) => { renderEnv.glyphCatalog = list ?? []; render() }).catch(() => {})
    loadServerPack(info, source).catch(() => {})

    const customMats = new Set()
    for (const crate of Object.values(store.crates)) {
      for (const m of [
        crate?.['crate-item']?.material,
        crate?.key?.item?.material,
        ...Object.values(crate?.rewards ?? {}).map((r) => r?.['display-item']?.material),
      ]) {
        if (typeof m === 'string' && m.includes(':') && !m.startsWith('minecraft:')) customMats.add(m)
      }
    }
    if (customMats.size > 0) {
      source.items([...customMats]).then((resolved) => { registerResolvedItems(resolved); render() }).catch(() => {})
    }
  }

  // Initial boot from URL hash, then strip secret session credentials from browser URL/history immediately!
  const initialHash = window.location.hash
  const initial = createSource(initialHash)
  if (initialHash && typeof history?.replaceState === 'function') {
    history.replaceState(null, '', window.location.pathname + window.location.search)
  }

  try {
    await connectTo(initial.source, initial.initialCrate)
    if (initial.error) {
      statusText = initial.error
      statusKind = 'err'
    }
  } catch (err) {
    // Fall back gracefully to standalone mode while surfacing the connection error
    const fallback = createSource('')
    await connectTo(fallback.source, fallback.initialCrate)
    serverOffline = Boolean(err.offline)
    offlineMessage = err.message
    statusText = err.message
    statusKind = 'err'
  }

  const onDeleteRemote = async (kind, id) => {
    if (source.mode === 'local' || info.readOnly || info.shared || info.ipVerified === false) return
    try {
      if (kind === 'crate') await source.deleteCrate(id)
      else await source.deleteAnimation(id)
    } catch (e) {
      if (e.offline) { serverOffline = true; offlineMessage = e.message }
      statusText = `Delete warning: ${e.message}`
      statusKind = 'err'
    }
  }

  const cratesTab = new CratesTab({ schema, store, getInfo: () => info, onDeleteRemote, onToast: showToast })
  const animationsTab = new AnimationsTab({ schema, store, getInfo: () => info, onDeleteRemote, onToast: showToast })
  const rawTab = new RawTab({ store, getInfo: () => info })
  const packTab = new PackTab({
    onUpdate: () => render(),
    onToast: showToast,
    reloadServerPack: () => loadServerPack(info, source),
    hasServer: () => source.mode !== 'local',
  })

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

  async function saveDoc(kind, id) {
    const doc = kind === 'crate' ? store.crates[id] : store.animations[id]
    if (!id || !doc) return false
    const probs = kind === 'crate'
      ? validateCrate(id, doc, {
          builtinAnimations: info.builtinAnimations ?? schema.builtinAnimations ?? [],
          knownAnimations: Object.keys(store.animations),
          knownCrates: Object.keys(store.crates),
        })
      : validateAnimation(id, doc)
    const errs = probs.filter((p) => p.severity === 'ERROR')
    if (errs.length > 0) {
      showProblems = true
      statusText = `Cannot save ${id}: ${errs.length} error(s)`
      statusKind = 'err'
      render()
      return false
    }
    try {
      statusText = `Saving ${kind} '${id}'…`
      statusKind = ''
      render()
      const res = kind === 'crate'
        ? await source.saveCrate(id, doc)
        : await source.saveAnimation(id, doc)
      store.clearDirty(kind, id)
      writeDraft(store)
      serverOffline = false
      if (res?.held) {
        const why = res.reason ? ` (${res.reason})` : ''
        statusText = `Held edit #${res.id}${why} — approve with /xcrates edits inspect ${res.id}`
        statusKind = 'shared'
        showToast(`Submitted edit #${res.id} for approval`)
      } else if (res?.reloaded) {
        statusText = `Saved and reloaded ${kind} '${id}'`
        statusKind = 'live'
        showToast(`Saved & reloaded ${id}`)
      } else if (source.mode === 'local') {
        downloadYaml(id, doc)
        statusText = `Exported ${id}.yml`
        statusKind = 'live'
        showToast(`Exported ${id}.yml`)
      } else {
        statusText = `Saved ${kind} '${id}' (run /xcrates reload to apply live)`
        statusKind = 'live'
        showToast(`Saved ${id}`)
      }
      return true
    } catch (e) {
      if (e.offline) {
        serverOffline = true
        offlineMessage = e.message
      }
      statusText = `Save failed: ${e.message}`
      statusKind = 'err'
      return false
    } finally {
      render()
    }
  }

  async function saveActive() {
    const { kind, id } = store.active
    await saveDoc(kind, id)
  }

  async function saveAllDirty() {
    const keys = [...store.dirty]
    for (const key of keys) {
      const [kind, ...rest] = key.split(':')
      const id = rest.join(':')
      const ok = await saveDoc(kind, id)
      if (!ok) break
    }
  }

  function downloadYaml(id, doc) {
    const blob = new Blob([dumpYaml(doc) + '\n'], { type: 'text/yaml;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${id}.yml`
    a.click()
    URL.revokeObjectURL(url)
  }

  async function openConnectDialog({ keepCurrent = false } = {}) {
    const raw = await promptModal({
      title: keepCurrent ? 'Reconnect / Save to Another Server' : 'Connect to Server',
      message: keepCurrent
        ? 'Paste a new /xcrates web or /xcrates share link (or 12-character code). Your current edits will stay loaded.'
        : 'Paste a /xcrates web or /xcrates share link (or 12-character code).',
      placeholder: 'https://crate.xali.to/#AbCdEf0123_-  or  AbCdEf0123_-',
      confirmText: 'Connect',
      validate: (v) => {
        const parsed = parseLinkInput(v)
        if (parsed.mode === 'invalid') return parsed.error
        if (parsed.mode === 'local') return 'Enter a valid /xcrates web link, direct API link, or 12-character code.'
        return null
      },
    })
    if (!raw) return
    const parsed = parseLinkInput(raw)
    const nextSource = parsed.mode === 'relay'
      ? new RelaySource(parsed.relay, parsed.code)
      : new DirectSource(parsed.api, parsed.token)
    try {
      statusText = 'Connecting…'
      statusKind = ''
      render()
      await connectTo(nextSource, parsed.crate, { keepCurrent })
      showToast(`Connected to ${info.plugin} (${info.owner})`)
    } catch (err) {
      serverOffline = Boolean(err.offline)
      offlineMessage = err.message
      statusText = err.message
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
        showToast(`Imported ${file.name}`)
      } catch (e) {
        statusText = `Import failed: ${e.message}`
        statusKind = 'err'
      }
      render()
    }
    input.click()
  }

  function render() {
    // Capture scroll positions before re-rendering so editing never jumps the viewport!
    const scrolls = new Map()
    for (const node of root.querySelectorAll('[data-scroll-key]')) {
      scrolls.set(node.getAttribute('data-scroll-key'), node.scrollTop)
    }

    const probs = currentProblems()
    const errCount = probs.filter((p) => p.severity === 'ERROR').length
    const warnCount = probs.filter((p) => p.severity === 'WARNING').length
    const { kind, id } = store.active
    const dirty = id ? store.isDirty(kind, id) : false
    const dirtyCount = store.dirty.size

    const connBadge = source.mode === 'local'
      ? el('span', { class: 'pill' }, 'Standalone')
      : serverOffline
        ? el('span', { class: 'pill err' }, 'Disconnected')
        : info.readOnly
          ? el('span', { class: 'pill' }, 'Read-Only')
          : info.shared
            ? el('span', { class: 'pill shared' }, `Shared · ${source.from || info.owner}`)
            : info.ipVerified === false
              ? el('span', { class: 'pill shared' }, `Unverified IP · ${info.owner}`)
              : el('span', { class: 'pill live' }, `Live · ${info.clientName || info.owner}`)

    const versionSelect = el('select', {
      title: 'Minecraft version (matches XaliMenu supported versions)',
      style: 'min-height:26px;padding:2px 6px;font-size:12px',
      onChange: (e) => { configureRenderer({ version: e.target.value }); render() },
    }, ...PACK_FORMATS.map((f) => el('option', {
      value: f.version,
      selected: (renderEnv.assets?.versionBranch ?? PACK_FORMATS[0].branch) === f.branch,
    }, `MC ${f.version}`)))

    const header = el('header', { class: 'topbar' },
      el('div', { class: 'brand' }, el('span', { class: 'xali' }, 'XALI'), 'CRATES', el('span', { class: 'muted', style: 'font-weight:400;font-size:12px' }, `v${schema.version}`)),
      connBadge,
      versionSelect,
      el('span', { class: `pill ${statusKind}`, style: 'max-width:340px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap', title: statusText }, statusText),
      el('div', { class: 'spacer' }),
      el('button', { class: 'small', onClick: () => openConnectDialog({ keepCurrent: store.dirty.size > 0 }) }, source.mode === 'local' ? 'Connect' : 'Load Link'),
      el('button', { class: 'small', disabled: !store.canUndo(), onClick: () => store.undo(), title: 'Undo (Ctrl+Z)' }, 'Undo'),
      el('button', { class: 'small', disabled: !store.canRedo(), onClick: () => store.redo(), title: 'Redo (Ctrl+Y)' }, 'Redo'),
      el('button', { class: 'small', onClick: importYamlFile }, 'Import .yml'),
      !info.readOnly && dirtyCount > 1 && source.mode !== 'local' ? el('button', {
        class: 'small',
        onClick: saveAllDirty,
      }, `Save All (${dirtyCount})`) : null,
      !info.readOnly ? el('button', {
        class: 'small primary',
        disabled: !id,
        onClick: saveActive,
        title: 'Save active document (Ctrl+S)',
      }, source.mode === 'local' ? 'Export .yml' : (info.shared || info.ipVerified === false) ? 'Submit Edit' : (dirty ? 'Save*' : 'Save')) : null,
    )

    const nav = el('nav', { class: 'tabs' },
      el('button', { class: activeTab === 'crates' ? 'active' : '', onClick: () => { animationsTab.stopPlayback(); activeTab = 'crates'; render() } }, `Crates (${Object.keys(store.crates).length})`),
      el('button', { class: activeTab === 'animations' ? 'active' : '', onClick: () => { activeTab = 'animations'; render() } }, `Animations (${Object.keys(store.animations).length})`),
      el('button', { class: activeTab === 'raw' ? 'active' : '', onClick: () => { animationsTab.stopPlayback(); activeTab = 'raw'; render() } }, 'Raw YAML'),
      el('button', { class: activeTab === 'pack' ? 'active' : '', onClick: () => { animationsTab.stopPlayback(); activeTab = 'pack'; render() } }, `Pack & Glyphs${renderEnv.pack ? ' ✓' : ''}`),
      el('div', { class: 'spacer' }),
      el('button', {
        class: `small${errCount > 0 ? ' danger' : ''}`,
        onClick: () => { showProblems = !showProblems; render() },
      }, errCount > 0 ? `⚠ ${errCount} Error(s), ${warnCount} Warn` : warnCount > 0 ? `⚠ ${warnCount} Warning(s)` : '✓ Valid'),
    )

    // Contextual banners (IP verification, offline disconnect recovery, interrupted draft recovery)
    const banners = el('div', { class: 'banners' })
    if (serverOffline) {
      banners.append(el('div', { class: 'banner err' },
        el('span', {}, `Connection lost: ${offlineMessage || 'The server stopped responding.'} Your edits are safe in memory.`),
        el('div', { class: 'banner-actions' },
          el('button', { class: 'small primary', onClick: () => openConnectDialog({ keepCurrent: true }) }, 'Reconnect / Save to Another Server'),
          id ? el('button', { class: 'small', onClick: () => downloadYaml(id, store.activeDocument()) }, `Download ${id}.yml`) : null,
        ),
      ))
    }
    if (!serverOffline && info.ipVerified === false && info.ipNotice) {
      banners.append(el('div', { class: 'banner warn' },
        el('span', {}, info.ipNotice),
      ))
    }
    if (savedDraft) {
      const count = savedDraft.dirty?.length ?? 0
      banners.append(el('div', { class: 'banner info' },
        el('span', {}, `Recovered ${count || 'unsaved'} local draft edit(s) from a previous session.`),
        el('div', { class: 'banner-actions' },
          el('button', {
            class: 'small primary',
            onClick: () => {
              Object.assign(store.crates, savedDraft.crates ?? {})
              Object.assign(store.animations, savedDraft.animations ?? {})
              for (const k of savedDraft.dirty ?? []) store.dirty.add(k)
              savedDraft = null
              clearDraft()
              showToast('Restored unsaved draft')
            },
          }, 'Restore Draft'),
          el('button', {
            class: 'small',
            onClick: () => { savedDraft = null; clearDraft(); render() },
          }, 'Discard'),
        ),
      ))
    }

    const main = el('main', {})
    if (banners.childNodes.length > 0) main.append(banners)
    if (activeTab === 'crates') main.append(cratesTab.render())
    else if (activeTab === 'animations') main.append(animationsTab.render())
    else if (activeTab === 'raw') main.append(rawTab.render())
    else main.append(packTab.render())

    const drawer = el('div', { class: 'problems-bar', hidden: !showProblems })
    if (probs.length === 0) {
      drawer.append(el('div', { class: 'good' }, 'No problems found in the active document.'))
    } else {
      for (const p of probs) {
        drawer.append(el('button', {
          class: 'problem-row',
          onClick: () => {
            if (kind === 'crate') {
              activeTab = 'crates'
              cratesTab.jumpToPath(p.path)
            }
          },
        },
          el('span', { class: `sev ${p.severity === 'ERROR' ? 'bad' : 'warn'}` }, p.severity),
          el('span', { class: 'path' }, p.path),
          el('span', {}, p.message),
        ))
      }
    }

    const nodes = [header, nav, main, drawer]
    if (toastMessage) nodes.push(el('div', { class: 'toast', role: 'status' }, toastMessage))
    root.replaceChildren(...nodes)

    // Restore captured scroll positions
    for (const [key, top] of scrolls) {
      const target = root.querySelector(`[data-scroll-key="${key}"]`)
      if (target) target.scrollTop = top
    }
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

  window.addEventListener('beforeunload', (e) => {
    if (store.dirty.size > 0) e.preventDefault()
  })

  store.subscribe(() => {
    writeDraft(store)
    render()
  })
  render()
}
