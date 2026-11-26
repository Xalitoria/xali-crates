// Dedicated visual editor tab for XaliCrates crate configurations (crates/<id>.yml).

import { el, renderObjectForm } from './form.js'
import { rewardProbabilities } from './check.js'
import { confirmModal, promptModal } from './modal.js'
import { defaultObject, starterCrate } from './schema.js'
import { renderEnv, renderItemIcon, renderItemTooltip } from './renderer.js'
import { plain } from './shared/mctext.js'
import { mcTextCanvas } from './shared/textpreview.js'

const SWATCH_COLORS = ['#b87cff', '#5b9dff', '#5fc27b', '#e2b453', '#e5675f', '#4dd0e1', '#f06292', '#aed581', '#ffb74d', '#90a4ae']
const ID_RE = /^[a-z0-9][a-z0-9_-]{0,63}$/

export class CratesTab {
  constructor({ schema, store, getInfo, onDeleteRemote, onToast }) {
    this.schema = schema
    this.store = store
    this.getInfo = getInfo
    this.onDeleteRemote = onDeleteRemote
    this.onToast = onToast
    this.subtab = 'overview'
    this.selectedReward = null
    this.filter = ''
    this.rewardFilter = ''
  }

  /** Navigates directly to the subtab (and reward, if applicable) referenced by a validation problem path. */
  jumpToPath(path) {
    const p = String(path ?? '')
    if (p.startsWith('rewards.')) {
      this.subtab = 'rewards'
      const rid = p.split('.')[1]
      if (rid) this.selectedReward = rid
    } else if (p.startsWith('hologram') || p.startsWith('crate-item') || p.startsWith('key') || p.startsWith('display')) {
      this.subtab = 'items'
    } else if (p.startsWith('ambient') || p.startsWith('effects') || p.startsWith('tiers')) {
      this.subtab = 'effects'
    } else if (p.startsWith('menus') || p.startsWith('lucky-chest')) {
      this.subtab = 'extras'
    } else {
      this.subtab = 'overview'
    }
    this.store.emit()
  }

  render() {
    const info = this.getInfo()
    const readOnly = Boolean(info?.readOnly)
    const root = el('div', { class: 'workspace' })

    // Left sidebar: crate list (filter updates list in-place without losing input focus)
    const sidebar = el('aside', { class: 'sidebar' })
    const head = el('div', { class: 'side-head' }, el('h2', {}, 'Crates'))
    if (!readOnly) {
      head.append(el('button', {
        class: 'small primary',
        onClick: () => this.createCrate(),
      }, '+ New'))
    }
    sidebar.append(head)

    const body = el('div', { class: 'side-body', 'data-scroll-key': 'crates-sidebar' })
    const listWrap = el('div', { class: 'crate-list-items' })

    const fillList = () => {
      const q = this.filter.trim().toLowerCase()
      const ids = Object.keys(this.store.crates).sort().filter((id) => {
        if (!q) return true
        const dn = plain(this.store.crates[id]?.['display-name'] ?? '').toLowerCase()
        return id.includes(q) || dn.includes(q)
      })
      if (ids.length === 0) {
        listWrap.replaceChildren(el('div', { class: 'muted', style: 'padding:8px;font-size:12px' }, 'No matching crates.'))
        return
      }
      const rows = ids.map((id) => {
        const crate = this.store.crates[id]
        const active = this.store.active.kind === 'crate' && this.store.active.id === id
        const dirty = this.store.isDirty('crate', id)
        const isMulti = crate?.['multi-crate']?.enabled === true
        const rewardCount = Object.keys(crate?.rewards ?? {}).length
        return el('button', {
          class: `doc-row${active ? ' active' : ''}`,
          onClick: () => { this.selectedReward = null; this.store.select('crate', id) },
        },
          el('div', { class: 'doc-icon' }, renderItemIcon(crate?.['crate-item'] ?? { material: 'chest' }, 1.5, { contain: true })),
          el('div', { style: 'min-width:0;flex:1' },
            el('div', { style: 'font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, id, dirty ? el('span', { class: 'dirty' }, ' •') : ''),
            el('div', { class: 'meta' }, isMulti ? 'Multi-Crate' : `${crate?.obtain ?? 'STATIC'} · ${rewardCount} rewards`),
          ),
          el('span', { class: `tag${crate?.enabled === false ? ' warn' : ' good'}` }, crate?.enabled === false ? 'OFF' : 'ON'),
        )
      })
      listWrap.replaceChildren(...rows)
    }

    const filterInput = el('input', {
      type: 'text',
      placeholder: 'Filter crates…',
      value: this.filter,
      onInput: (e) => {
        this.filter = e.target.value
        fillList()
      },
    })
    fillList()
    body.append(filterInput, listWrap)
    sidebar.append(body)
    root.append(sidebar)

    // Main crate editor content
    const main = el('section', { class: 'content', 'data-scroll-key': 'crates-content' })
    const activeId = this.store.active.kind === 'crate' ? this.store.active.id : Object.keys(this.store.crates)[0]
    const crate = activeId ? this.store.crates[activeId] : null

    if (!crate) {
      main.append(el('div', { class: 'card' },
        el('h3', {}, 'No crate selected'),
        el('p', { class: 'muted' }, 'Create a crate from the sidebar or import a YAML file.'),
      ))
      root.append(main)
      return root
    }

    if (this.store.active.kind !== 'crate' || this.store.active.id !== activeId) {
      this.store.active = { kind: 'crate', id: activeId }
    }

    const mutate = (fn) => this.store.mutateActive(fn)

    // Crate banner & subtabs
    const banner = el('div', { class: 'crate-banner' },
      el('div', { class: 'crate-banner-icon' }, renderItemIcon(crate['crate-item'] ?? { material: 'chest' }, 2.5, { contain: true })),
      el('div', { style: 'min-width:0' },
        el('div', { class: 'crate-banner-title' }, mcTextCanvas(renderEnv.text, crate['display-name'] || activeId, { scale: 2.5, color: '#ffffff', shadow: true })),
        el('div', { class: 'muted mono', style: 'font-size:12px' }, `crates/${activeId}.yml`),
      ),
      el('div', { style: 'flex:1' }),
    )
    if (!readOnly) {
      banner.append(
        el('button', { class: 'small', onClick: () => this.duplicateCrate(activeId, crate) }, 'Duplicate'),
        el('button', { class: 'small danger', onClick: () => this.deleteCrate(activeId) }, 'Delete'),
      )
    }
    main.append(banner)

    const subtabs = el('div', { class: 'subtabs' })
    const tabs = [
      ['overview', 'Overview & Opening'],
      ['items', 'Items & Hologram'],
      ['rewards', `Rewards (${Object.keys(crate.rewards ?? {}).length})`],
      ['effects', 'Effects & Tiers'],
      ['extras', 'Lucky Chest & Menus'],
    ]
    for (const [key, label] of tabs) {
      subtabs.append(el('button', {
        class: this.subtab === key ? 'active' : '',
        onClick: () => { this.subtab = key; this.store.emit() },
      }, label))
    }
    main.append(subtabs)

    if (this.subtab === 'overview') main.append(this.renderOverview(crate, mutate, readOnly))
    else if (this.subtab === 'items') main.append(this.renderItemsAndHologram(crate, mutate, readOnly))
    else if (this.subtab === 'rewards') main.append(this.renderRewards(crate, mutate, readOnly))
    else if (this.subtab === 'effects') main.append(this.renderEffects(crate, mutate, readOnly))
    else if (this.subtab === 'extras') main.append(this.renderExtras(crate, mutate, readOnly))

    root.append(main)
    return root
  }

  renderOverview(crate, mutate, readOnly) {
    const wrap = el('div', { style: 'display:flex;flex-direction:column;gap:14px' })
    const info = this.getInfo()
    const allAnims = [...new Set([...(info?.builtinAnimations ?? []), ...Object.keys(this.store.animations)])].sort()

    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' }, el('h3', {}, 'General')),
      renderObjectForm({
        schema: this.schema,
        typeName: 'CrateConfig',
        value: crate,
        readOnly,
        onlyKeys: ['enabled', 'display-name', 'obtain', 'permission'],
        onChange: (next) => mutate((c) => Object.assign(c, {
          enabled: next.enabled,
          'display-name': next['display-name'],
          obtain: next.obtain,
          permission: next.permission,
        })),
      }),
    ))

    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' },
        el('h3', {}, 'Opening & Animation'),
        el('span', { class: 'muted', style: 'font-size:12px' }, `Available: ${allAnims.join(', ')}`),
      ),
      renderObjectForm({
        schema: this.schema,
        typeName: 'OpeningConfig',
        value: crate.opening ?? {},
        readOnly,
        onChange: (next) => mutate((c) => { c.opening = next }),
      }),
    ))

    wrap.append(el('div', { class: 'grid-2' },
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Cooldown')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'CooldownConfig',
          value: crate.cooldown ?? {},
          readOnly,
          onChange: (next) => mutate((c) => { c.cooldown = next }),
        }),
      ),
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Economy Cost')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'CostConfig',
          value: crate.cost ?? {},
          readOnly,
          onChange: (next) => mutate((c) => { c.cost = next }),
        }),
      ),
    ))

    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' }, el('h3', {}, 'Multi-Crate Selector')),
      renderObjectForm({
        schema: this.schema,
        typeName: 'MultiCrateConfig',
        value: crate['multi-crate'] ?? { enabled: false, crates: [] },
        readOnly,
        onChange: (next) => mutate((c) => { c['multi-crate'] = next }),
      }),
    ))

    return wrap
  }

  itemPreviewBanner(itemSpec, label) {
    const spec = itemSpec ?? { material: 'chest' }
    const tip = renderItemTooltip(spec, 2)
    return el('div', { class: 'item-preview-card' },
      el('div', { class: 'item-preview-slot' }, renderItemIcon(spec, 2.5, { contain: true })),
      el('div', { class: 'item-preview-tip' },
        el('div', { class: 'muted', style: 'font-size:11px;margin-bottom:4px' }, label),
        tip,
      ),
    )
  }

  renderItemsAndHologram(crate, mutate, readOnly) {
    const wrap = el('div', { style: 'display:flex;flex-direction:column;gap:14px' })

    // Live Hologram Preview
    const holoLines = crate.hologram?.lines ?? []
    const holoBox = el('div', { class: 'holo-preview' })
    if (crate.hologram?.enabled !== false && holoLines.length > 0) {
      for (const line of holoLines) {
        holoBox.append(el('div', { class: 'holo-line' }, mcTextCanvas(renderEnv.text, line, { scale: 2, color: '#ffffff', shadow: true, align: 'center' })))
      }
    } else {
      holoBox.append(el('div', { class: 'muted' }, 'Hologram disabled or no lines configured'))
    }

    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' }, el('h3', {}, 'Hologram (Native TextDisplay)')),
      holoBox,
      renderObjectForm({
        schema: this.schema,
        typeName: 'HologramConfig',
        value: crate.hologram ?? {},
        readOnly,
        onChange: (next) => mutate((c) => { c.hologram = next }),
      }),
    ))

    wrap.append(el('div', { class: 'grid-2' },
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Crate Item')),
        this.itemPreviewBanner(crate['crate-item'], 'Crate item tooltip preview'),
        renderObjectForm({
          schema: this.schema,
          typeName: 'CrateItem',
          value: crate['crate-item'] ?? {},
          readOnly,
          onChange: (next) => mutate((c) => { c['crate-item'] = next }),
        }),
      ),
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Key Configuration')),
        this.itemPreviewBanner(crate.key?.item ?? { material: 'tripwire_hook' }, 'Key item tooltip preview'),
        renderObjectForm({
          schema: this.schema,
          typeName: 'KeyConfig',
          value: crate.key ?? {},
          readOnly,
          onChange: (next) => mutate((c) => { c.key = next }),
        }),
      ),
    ))

    wrap.append(el('div', { class: 'card' },
      el('div', { class: 'card-head' }, el('h3', {}, 'World Display')),
      renderObjectForm({
        schema: this.schema,
        typeName: 'CrateDisplayConfig',
        value: crate.display ?? {},
        readOnly,
        onChange: (next) => mutate((c) => { c.display = next }),
      }),
    ))

    return wrap
  }

  renderRewards(crate, mutate, readOnly) {
    const wrap = el('div', { style: 'display:flex;flex-direction:column;gap:14px' })
    const rewards = crate.rewards ?? {}
    const entries = Object.entries(rewards)
    const { totalWeight, percentages } = rewardProbabilities(crate)

    if (!this.selectedReward || !rewards[this.selectedReward]) {
      this.selectedReward = entries[0]?.[0] ?? null
    }

    // Probability distribution bar card
    const barCard = el('div', { class: 'card' })
    const barHead = el('div', { class: 'card-head' },
      el('h3', {}, `Reward Odds (Total Weight: ${totalWeight.toFixed(1)})`),
    )
    if (!readOnly) {
      barHead.append(el('button', {
        class: 'small primary',
        onClick: () => {
          let i = entries.length + 1
          while (rewards[`reward_${i}`]) i++
          const rid = `reward_${i}`
          mutate((c) => {
            c.rewards = c.rewards ?? {}
            c.rewards[rid] = defaultObject(this.schema, 'RewardConfig')
          })
          this.selectedReward = rid
          this.store.emit()
        },
      }, '+ Add Reward'))
    }
    barCard.append(barHead)

    const bar = el('div', { class: 'prob-bar' })
    entries.forEach(([rid, r], idx) => {
      if (r?.['fallback-only'] || r?.guaranteed) return
      const pct = percentages[rid] ?? 0
      if (pct <= 0) return
      const color = SWATCH_COLORS[idx % SWATCH_COLORS.length]
      bar.append(el('div', {
        class: `prob-seg${this.selectedReward === rid ? ' active' : ''}`,
        style: `width:${pct}%;background:${color}`,
        title: `${rid}: ${pct.toFixed(2)}% (weight ${r.weight})`,
        onClick: () => { this.selectedReward = rid; this.store.emit() },
      }))
    })
    barCard.append(bar)
    wrap.append(barCard)

    // Split view: filterable list on left, inspector on right
    const split = el('div', { class: 'rewards-split' })
    const listCol = el('div', { style: 'display:flex;flex-direction:column;gap:8px' })
    const list = el('div', { class: 'reward-list' })

    const fillRewards = () => {
      const q = this.rewardFilter.trim().toLowerCase()
      const cards = []
      entries.forEach(([rid, r], idx) => {
        const title = plain(r?.['display-item']?.name || '') || rid
        const mat = String(r?.['display-item']?.material ?? 'diamond')
        if (q && !rid.toLowerCase().includes(q) && !title.toLowerCase().includes(q) && !mat.toLowerCase().includes(q)) return
        const pct = percentages[rid] ?? 0
        const color = SWATCH_COLORS[idx % SWATCH_COLORS.length]
        const tags = []
        if (r?.rarity && r.rarity !== 'default') tags.push(el('span', { class: 'tag accent' }, r.rarity))
        if (r?.guaranteed) tags.push(el('span', { class: 'tag good' }, 'GUARANTEED'))
        if (r?.['fallback-only']) tags.push(el('span', { class: 'tag warn' }, 'FALLBACK'))
        if (r?.redeem?.mode === 'ALWAYS' || r?.redeem?.voucher) tags.push(el('span', { class: 'tag' }, 'REDEEM'))
        if ((r?.commands?.length ?? 0) > 0) tags.push(el('span', { class: 'tag' }, `${r.commands.length} cmd`))

        cards.push(el('button', {
          class: `reward-card${this.selectedReward === rid ? ' active' : ''}`,
          onClick: () => { this.selectedReward = rid; this.store.emit() },
        },
          el('div', { class: 'reward-swatch', style: `background:${color}` }),
          el('div', { class: 'reward-icon' }, renderItemIcon(r?.['display-item'] ?? { material: 'diamond' }, 2, { contain: true })),
          el('div', { style: 'min-width:0' },
            el('div', { style: 'font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, title),
            el('div', { class: 'muted mono', style: 'font-size:11px;margin-bottom:3px' }, `${rid} · ${mat}`),
            el('div', {}, ...tags),
          ),
          el('div', { class: 'reward-pct' },
            el('div', {}, r?.guaranteed ? '100%' : `${pct.toFixed(pct < 1 ? 2 : 1)}%`),
            el('div', { class: 'muted', style: 'font-size:11px;font-weight:400' }, `w=${r?.weight ?? 0}`),
          ),
        ))
      })
      if (cards.length === 0) {
        list.replaceChildren(el('div', { class: 'muted', style: 'padding:8px;font-size:12px' }, 'No matching rewards.'))
      } else {
        list.replaceChildren(...cards)
      }
    }

    if (entries.length > 4) {
      listCol.append(el('input', {
        type: 'text',
        placeholder: 'Filter rewards by id, name, or material…',
        value: this.rewardFilter,
        onInput: (e) => {
          this.rewardFilter = e.target.value
          fillRewards()
        },
      }))
    }
    fillRewards()
    listCol.append(list)
    split.append(listCol)

    // Inspector
    const inspector = el('div', { class: 'card' })
    const rid = this.selectedReward
    if (rid && rewards[rid]) {
      const reward = rewards[rid]
      const head = el('div', { class: 'card-head' },
        el('h3', {}, `Reward: ${rid}`),
      )
      if (!readOnly) {
        const actions = el('div', { style: 'display:flex;gap:6px;flex-wrap:wrap' },
          el('button', {
            class: 'small',
            onClick: () => {
              let i = 2
              while (rewards[`${rid}_${i}`]) i++
              const nextId = `${rid}_${i}`
              mutate((c) => { c.rewards[nextId] = structuredClone(reward) })
              this.selectedReward = nextId
              this.onToast?.(`Duplicated reward as '${nextId}'`)
              this.store.emit()
            },
          }, 'Duplicate'),
          el('button', {
            class: 'small',
            onClick: async () => {
              const nextId = await promptModal({
                title: `Rename reward '${rid}'`,
                initialValue: rid,
                placeholder: 'reward_id',
                confirmText: 'Rename',
                validate: (v) => {
                  const clean = v.toLowerCase()
                  if (!ID_RE.test(clean)) return 'Use lowercase letters, digits, - or _.'
                  if (clean !== rid && rewards[clean]) return `Reward '${clean}' already exists.`
                  return null
                },
              })
              if (!nextId) return
              const clean = nextId.toLowerCase()
              if (clean === rid) return
              mutate((c) => {
                const rebuilt = {}
                for (const [k, v] of Object.entries(c.rewards)) rebuilt[k === rid ? clean : k] = v
                c.rewards = rebuilt
              })
              this.selectedReward = clean
              this.store.emit()
            },
          }, 'Rename'),
          el('button', {
            class: 'small danger',
            onClick: async () => {
              const ok = await confirmModal({
                title: `Delete reward '${rid}'?`,
                message: 'This removes the reward from the crate (you can undo with Ctrl+Z).',
                confirmText: 'Delete',
                danger: true,
              })
              if (!ok) return
              mutate((c) => { delete c.rewards[rid] })
              this.selectedReward = null
              this.store.emit()
            },
          }, 'Delete'),
        )
        head.append(actions)
      }
      inspector.append(head)
      inspector.append(this.itemPreviewBanner(reward['display-item'] ?? { material: 'diamond' }, 'Reward preview item'))
      inspector.append(renderObjectForm({
        schema: this.schema,
        typeName: 'RewardConfig',
        value: reward,
        readOnly,
        onChange: (nextReward) => mutate((c) => { c.rewards[rid] = nextReward }),
      }))
    } else {
      inspector.append(el('p', { class: 'muted' }, 'Select a reward on the left or click "+ Add Reward".'))
    }
    split.append(inspector)
    wrap.append(split)
    return wrap
  }

  renderEffects(crate, mutate, readOnly) {
    return el('div', { style: 'display:flex;flex-direction:column;gap:14px' },
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Ambient Effects')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'AmbientEffectsConfig',
          value: crate.ambient ?? { particles: [] },
          readOnly,
          onChange: (next) => mutate((c) => { c.ambient = next }),
        }),
      ),
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Default Opening Effects')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'OpenEffectsConfig',
          value: crate.effects ?? {},
          readOnly,
          onChange: (next) => mutate((c) => { c.effects = next }),
        }),
      ),
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Rarity Tiers')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'CrateConfig',
          value: crate,
          readOnly,
          onlyKeys: ['tiers'],
          onChange: (next) => mutate((c) => { c.tiers = next.tiers }),
        }),
      ),
    )
  }

  renderExtras(crate, mutate, readOnly) {
    return el('div', { style: 'display:flex;flex-direction:column;gap:14px' },
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'XaliMenu Overrides')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'CrateMenusConfig',
          value: crate.menus ?? {},
          readOnly,
          onChange: (next) => mutate((c) => { c.menus = next }),
        }),
      ),
      el('div', { class: 'card' },
        el('div', { class: 'card-head' }, el('h3', {}, 'Lucky Chest (Mining Spawns)')),
        renderObjectForm({
          schema: this.schema,
          typeName: 'CrateConfig',
          value: crate,
          readOnly,
          onlyKeys: ['lucky-chest'],
          onChange: (next) => mutate((c) => { c['lucky-chest'] = next['lucky-chest'] }),
        }),
      ),
    )
  }

  async createCrate() {
    const raw = await promptModal({
      title: 'Create New Crate',
      message: 'Choose a lowercase crate ID (letters, digits, - or _).',
      initialValue: 'my-crate',
      placeholder: 'my-crate',
      confirmText: 'Create',
      validate: (v) => {
        const id = v.toLowerCase()
        if (!ID_RE.test(id)) return 'Invalid crate ID. Use lowercase letters, digits, - or _.'
        if (this.store.crates[id]) return `Crate '${id}' already exists.`
        return null
      },
    })
    if (!raw) return
    const id = raw.toLowerCase()
    const crate = starterCrate()
    crate['display-name'] = `&d${id}`
    this.store.setCrate(id, crate)
    this.store.select('crate', id)
    this.onToast?.(`Created crate '${id}'`)
  }

  async duplicateCrate(id, crate) {
    const raw = await promptModal({
      title: `Duplicate '${id}'`,
      message: 'Enter an ID for the duplicated crate.',
      initialValue: `${id}_copy`,
      placeholder: `${id}_copy`,
      confirmText: 'Duplicate',
      validate: (v) => {
        const nextId = v.toLowerCase()
        if (!ID_RE.test(nextId)) return 'Invalid crate ID. Use lowercase letters, digits, - or _.'
        if (this.store.crates[nextId]) return `Crate '${nextId}' already exists.`
        return null
      },
    })
    if (!raw) return
    const nextId = raw.toLowerCase()
    this.store.setCrate(nextId, structuredClone(crate))
    this.store.select('crate', nextId)
    this.onToast?.(`Duplicated crate as '${nextId}'`)
  }

  async deleteCrate(id) {
    const ok = await confirmModal({
      title: `Delete crate '${id}'?`,
      message: 'If connected as a verified admin, this also deletes crates/' + id + '.yml on the server.',
      confirmText: 'Delete Crate',
      danger: true,
    })
    if (!ok) return
    await this.onDeleteRemote?.('crate', id)
    this.store.deleteCrate(id)
    this.onToast?.(`Deleted crate '${id}'`)
  }
}
