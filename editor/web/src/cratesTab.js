// Dedicated visual editor tab for XaliCrates crate configurations (crates/<id>.yml).

import { el, renderObjectForm } from './form.js'
import { rewardProbabilities } from './check.js'
import { renderMcHtml, stripMc } from './mctext.js'
import { defaultObject, starterCrate } from './schema.js'
import { renderItemIcon } from './renderer.js'

const SWATCH_COLORS = ['#b87cff', '#5b9dff', '#5fc27b', '#e2b453', '#e5675f', '#4dd0e1', '#f06292', '#aed581', '#ffb74d', '#90a4ae']

export class CratesTab {
  constructor({ schema, store, getInfo, onDeleteRemote }) {
    this.schema = schema
    this.store = store
    this.getInfo = getInfo
    this.onDeleteRemote = onDeleteRemote
    this.subtab = 'overview'
    this.selectedReward = null
    this.filter = ''
  }

  render() {
    const info = this.getInfo()
    const readOnly = Boolean(info?.readOnly)
    const root = el('div', { class: 'workspace' })

    // Left sidebar: crate list
    const sidebar = el('aside', { class: 'sidebar' })
    const head = el('div', { class: 'side-head' }, el('h2', {}, 'Crates'))
    if (!readOnly) {
      head.append(el('button', {
        class: 'small primary',
        onClick: () => this.createCrate(),
      }, '+ New'))
    }
    sidebar.append(head)

    const body = el('div', { class: 'side-body' })
    body.append(el('input', {
      type: 'text',
      placeholder: 'Filter crates…',
      value: this.filter,
      onInput: (e) => { this.filter = e.target.value.toLowerCase(); this.store.emit() },
    }))

    const ids = Object.keys(this.store.crates).sort().filter((id) => !this.filter || id.includes(this.filter))
    for (const id of ids) {
      const crate = this.store.crates[id]
      const active = this.store.active.kind === 'crate' && this.store.active.id === id
      const dirty = this.store.isDirty('crate', id)
      const isMulti = crate?.['multi-crate']?.enabled === true
      const rewardCount = Object.keys(crate?.rewards ?? {}).length
      body.append(el('button', {
        class: `doc-row${active ? ' active' : ''}`,
        onClick: () => { this.selectedReward = null; this.store.select('crate', id) },
      },
        el('div', {},
          el('div', { style: 'font-weight:600' }, id, dirty ? el('span', { class: 'dirty' }, ' •') : ''),
          el('div', { class: 'meta' }, isMulti ? 'Multi-Crate' : `${crate?.obtain ?? 'STATIC'} · ${rewardCount} rewards`),
        ),
        el('span', { class: `tag${crate?.enabled === false ? ' warn' : ' good'}` }, crate?.enabled === false ? 'OFF' : 'ON'),
      ))
    }
    sidebar.append(body)
    root.append(sidebar)

    // Main crate editor content
    const main = el('section', { class: 'content' })
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
    const banner = el('div', { style: 'display:flex;align-items:center;gap:12px;flex-wrap:wrap' },
      el('div', {},
        el('div', { style: 'font-size:18px;font-weight:700', html: renderMcHtml(crate['display-name'] || activeId) }),
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

    const openingCard = el('div', { class: 'card' },
      el('div', { class: 'card-head' },
        el('h3', {}, 'Opening & Animation'),
        el('span', { class: 'muted', style: 'font-size:12px' }, `Available animations: ${allAnims.join(', ')}`),
      ),
      renderObjectForm({
        schema: this.schema,
        typeName: 'OpeningConfig',
        value: crate.opening ?? {},
        readOnly,
        onChange: (next) => mutate((c) => { c.opening = next }),
      }),
    )
    wrap.append(openingCard)

    const grid = el('div', { class: 'grid-2' },
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
    )
    wrap.append(grid)

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

  renderItemsAndHologram(crate, mutate, readOnly) {
    const wrap = el('div', { style: 'display:flex;flex-direction:column;gap:14px' })

    // Live Hologram Preview
    const holoLines = crate.hologram?.lines ?? []
    const holoBox = el('div', { class: 'holo-preview' })
    if (crate.hologram?.enabled !== false && holoLines.length > 0) {
      for (const line of holoLines) {
        holoBox.append(el('div', { class: 'holo-line', html: renderMcHtml(line) }))
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
        class: 'prob-seg',
        style: `width:${pct}%;background:${color}`,
        title: `${rid}: ${pct.toFixed(2)}% (weight ${r.weight})`,
        onClick: () => { this.selectedReward = rid; this.store.emit() },
      }))
    })
    barCard.append(bar)
    wrap.append(barCard)

    // Split view: list on left, inspector on right
    const split = el('div', { class: 'rewards-split' })
    const list = el('div', { class: 'reward-list' })
    entries.forEach(([rid, r], idx) => {
      const pct = percentages[rid] ?? 0
      const color = SWATCH_COLORS[idx % SWATCH_COLORS.length]
      const title = stripMc(r?.['display-item']?.name || '') || rid
      const tags = []
      if (r?.rarity && r.rarity !== 'default') tags.push(el('span', { class: 'tag accent' }, r.rarity))
      if (r?.guaranteed) tags.push(el('span', { class: 'tag good' }, 'GUARANTEED'))
      if (r?.['fallback-only']) tags.push(el('span', { class: 'tag warn' }, 'FALLBACK'))
      if (r?.redeem?.mode === 'ALWAYS' || r?.redeem?.voucher) tags.push(el('span', { class: 'tag' }, 'REDEEM'))
      if ((r?.commands?.length ?? 0) > 0) tags.push(el('span', { class: 'tag' }, `${r.commands.length} cmd`))

      list.append(el('button', {
        class: `reward-card${this.selectedReward === rid ? ' active' : ''}`,
        onClick: () => { this.selectedReward = rid; this.store.emit() },
      },
        el('div', { class: 'reward-swatch', style: `background:${color}` }),
        el('div', { class: 'reward-icon' }, renderItemIcon(r?.['display-item'] ?? { material: 'diamond' }, 2, { contain: true })),
        el('div', { style: 'min-width:0' },
          el('div', { style: 'font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, title),
          el('div', { class: 'muted mono', style: 'font-size:11px;margin-bottom:3px' }, `${rid} · ${r?.['display-item']?.material ?? 'diamond'}`),
          el('div', {}, ...tags),
        ),
        el('div', { class: 'reward-pct' },
          el('div', {}, r?.guaranteed ? '100%' : `${pct.toFixed(pct < 1 ? 2 : 1)}%`),
          el('div', { class: 'muted', style: 'font-size:11px;font-weight:400' }, `w=${r?.weight ?? 0}`),
        ),
      ))
    })
    split.append(list)

    // Inspector
    const inspector = el('div', { class: 'card' })
    const rid = this.selectedReward
    if (rid && rewards[rid]) {
      const reward = rewards[rid]
      const head = el('div', { class: 'card-head' },
        el('h3', {}, `Reward: ${rid}`),
      )
      if (!readOnly) {
        const actions = el('div', { style: 'display:flex;gap:6px' },
          el('button', {
            class: 'small',
            onClick: () => {
              const nextId = prompt('New reward ID:', rid)?.trim().toLowerCase()
              if (!nextId || nextId === rid || rewards[nextId]) return
              mutate((c) => {
                const rebuilt = {}
                for (const [k, v] of Object.entries(c.rewards)) rebuilt[k === rid ? nextId : k] = v
                c.rewards = rebuilt
              })
              this.selectedReward = nextId
              this.store.emit()
            },
          }, 'Rename'),
          el('button', {
            class: 'small danger',
            onClick: () => {
              mutate((c) => { delete c.rewards[rid] })
              this.selectedReward = null
              this.store.emit()
            },
          }, 'Delete'),
        )
        head.append(actions)
      }
      inspector.append(head)
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

  createCrate() {
    const raw = prompt('New crate ID (lowercase letters, digits, _ or -):', 'my-crate')
    if (!raw) return
    const id = raw.trim().toLowerCase()
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(id)) {
      alert('Invalid crate ID. Use lowercase letters, numbers, - or _.')
      return
    }
    if (this.store.crates[id]) {
      alert(`Crate '${id}' already exists.`)
      return
    }
    const crate = starterCrate()
    crate['display-name'] = `&d${id}`
    this.store.setCrate(id, crate)
    this.store.select('crate', id)
  }

  duplicateCrate(id, crate) {
    const raw = prompt('Duplicate crate as ID:', `${id}_copy`)
    if (!raw) return
    const nextId = raw.trim().toLowerCase()
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(nextId) || this.store.crates[nextId]) {
      alert('Invalid or duplicate crate ID.')
      return
    }
    this.store.setCrate(nextId, structuredClone(crate))
    this.store.select('crate', nextId)
  }

  async deleteCrate(id) {
    if (!confirm(`Delete crate '${id}'?`)) return
    await this.onDeleteRemote?.('crate', id)
    this.store.deleteCrate(id)
  }
}
