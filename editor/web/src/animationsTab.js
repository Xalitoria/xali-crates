// Interactive 9xN custom stage-based animation editor with live Minecraft item icons,
// drag-painting, right-click erasing, stage reordering, quick grid tools, and live playback preview.

import { el, renderObjectForm } from './form.js'
import { confirmModal, promptModal } from './modal.js'
import { renderEnv, renderItemIcon } from './renderer.js'
import { starterAnimation } from './schema.js'
import { mcTextCanvas } from './shared/textpreview.js'

const ID_RE = /^[a-z0-9][a-z0-9_-]{0,63}$/

const SPECIAL_COLORS = {
  A: '#555a66',
  R: '#e2b453',
  RF: '#607d8b',
  RS: '#5fc27b',
  OS: '#4dd0e1',
  RR: '#ff9800',
  SR: '#f06292',
  NS: '#5b9dff',
}

const SPECIAL_PREVIEW_ITEMS = {
  R: { material: 'diamond' },
  RS: { material: 'ender_chest' },
  OS: { material: 'chest' },
  RR: { material: 'nether_star' },
  SR: { material: 'emerald' },
  NS: { material: 'arrow' },
}

function colorForSymbol(sym, fillers) {
  if (SPECIAL_COLORS[sym]) return SPECIAL_COLORS[sym]
  if (sym.startsWith('R')) return '#c89bff'
  const mat = String(fillers?.[sym]?.material ?? '').toLowerCase()
  if (mat.includes('purple')) return '#8e44ad'
  if (mat.includes('magenta')) return '#c2185b'
  if (mat.includes('red')) return '#c62828'
  if (mat.includes('blue') || mat.includes('cyan')) return '#1976d2'
  if (mat.includes('lime') || mat.includes('green')) return '#2e7d32'
  if (mat.includes('yellow') || mat.includes('gold')) return '#f9a825'
  if (mat.includes('orange')) return '#ef6c00'
  if (mat.includes('black')) return '#22252c'
  if (mat.includes('white')) return '#cfd8dc'
  return '#5c6bc0'
}

function previewItemForSymbol(sym, anim) {
  if (anim.fillers?.[sym]) return anim.fillers[sym]
  if (SPECIAL_PREVIEW_ITEMS[sym]) return SPECIAL_PREVIEW_ITEMS[sym]
  if (sym.startsWith('R') && sym.length > 1) return { material: 'gold_ingot' }
  return null
}

export class AnimationsTab {
  constructor({ schema, store, getInfo, onDeleteRemote, onToast }) {
    this.schema = schema
    this.store = store
    this.getInfo = getInfo
    this.onDeleteRemote = onDeleteRemote
    this.onToast = onToast
    this.stageIndex = 0
    this.brush = 'R'
    this.paintSym = null
    this.playing = false
    this.playTimer = null

    window.addEventListener('pointerup', () => { this.paintSym = null })
  }

  stopPlayback() {
    this.playing = false
    if (this.playTimer) {
      clearTimeout(this.playTimer)
      this.playTimer = null
    }
  }

  render() {
    const info = this.getInfo()
    const readOnly = Boolean(info?.readOnly)
    const root = el('div', { class: 'workspace three-col' })

    // Left sidebar: animation list + symbol palette
    const left = el('aside', { class: 'sidebar' })
    const head = el('div', { class: 'side-head' }, el('h2', {}, 'Animations'))
    if (!readOnly) {
      head.append(el('button', { class: 'small primary', onClick: () => this.createAnimation() }, '+ New'))
    }
    left.append(head)

    const body = el('div', { class: 'side-body', 'data-scroll-key': 'anims-sidebar' })
    const ids = Object.keys(this.store.animations).sort()
    for (const id of ids) {
      const anim = this.store.animations[id]
      const active = this.store.active.kind === 'animation' && this.store.active.id === id
      const dirty = this.store.isDirty('animation', id)
      body.append(el('button', {
        class: `doc-row${active ? ' active' : ''}`,
        onClick: () => { this.stopPlayback(); this.stageIndex = 0; this.store.select('animation', id) },
      },
        el('div', {},
          el('div', { style: 'font-weight:600' }, id, dirty ? el('span', { class: 'dirty' }, ' •') : ''),
          el('div', { class: 'meta' }, `${anim?.['inventory-size'] ?? 27} slots · ${(anim?.stages ?? []).length} stages`),
        ),
      ))
    }

    const activeId = this.store.active.kind === 'animation' ? this.store.active.id : ids[0]
    const anim = activeId ? this.store.animations[activeId] : null

    if (anim) {
      if (this.store.active.kind !== 'animation' || this.store.active.id !== activeId) {
        this.store.active = { kind: 'animation', id: activeId }
      }
      body.append(el('div', { class: 'side-head', style: 'margin:8px -8px 4px;border-top:1px solid var(--line)' }, el('h3', {}, 'Symbol Brush')))
      const palette = el('div', { class: 'palette' })
      const syms = anim.symbols ?? {}
      const builtins = [
        [syms.air || 'A', 'Empty (Air)'],
        [syms['random-reward'] || 'R', 'Random Reward'],
        ...(syms['reward-symbols'] ?? ['R1']).map((s) => [s, 'Persistent Reward']),
        [syms['random-filler'] || 'RF', 'Random Filler'],
        [syms['select-reward'] || 'RS', 'Select Mystery'],
        [syms['open-selected-reward'] || 'OS', 'Open Selected'],
        [syms['reveal-reward'] || 'RR', 'Reveal Click'],
        [syms['selected-reveal-reward'] || 'SR', 'Revealed Slot'],
        [syms['next-stage'] || 'NS', 'Next Stage Button'],
      ]
      for (const [sym, label] of builtins) {
        palette.append(this.paletteButton(sym, label, anim))
      }
      for (const [sym, item] of Object.entries(anim.fillers ?? {})) {
        palette.append(this.paletteButton(sym, item?.material || 'filler', anim))
      }
      body.append(palette)
    }

    left.append(body)
    root.append(left)

    // Center: interactive 9xN grid + stage timeline
    const center = el('section', { class: 'content', 'data-scroll-key': 'anims-content' })
    if (!anim) {
      center.append(el('div', { class: 'card' },
        el('h3', {}, 'No custom animation selected'),
        el('p', { class: 'muted' }, 'Create a custom stage animation from the sidebar.'),
      ))
      root.append(center)
      root.append(el('aside', { class: 'sidebar right' }))
      return root
    }

    const mutate = (fn) => this.store.mutateActive(fn)
    const stages = anim.stages ?? []
    if (this.stageIndex >= stages.length) this.stageIndex = Math.max(0, stages.length - 1)
    const stage = stages[this.stageIndex]

    // Header bar with playback controls
    const header = el('div', { style: 'display:flex;align-items:center;gap:10px;flex-wrap:wrap' },
      el('div', {},
        el('div', { style: 'font-size:18px;font-weight:700' }, activeId),
        el('div', { class: 'muted mono', style: 'font-size:12px' }, `animations/${activeId}.yml`),
      ),
      el('div', { style: 'flex:1' }),
      el('button', {
        class: `small${this.playing ? ' danger' : ' primary'}`,
        onClick: () => this.togglePlay(anim),
      }, this.playing ? '■ Stop Preview' : '▶ Play Preview'),
    )
    if (!readOnly) {
      header.append(el('button', {
        class: 'small danger',
        onClick: async () => {
          const ok = await confirmModal({
            title: `Delete animation '${activeId}'?`,
            message: `This removes animations/${activeId}.yml.`,
            confirmText: 'Delete Animation',
            danger: true,
          })
          if (!ok) return
          this.stopPlayback()
          await this.onDeleteRemote?.('animation', activeId)
          this.store.deleteAnimation(activeId)
          this.onToast?.(`Deleted animation '${activeId}'`)
        },
      }, 'Delete'))
    }
    center.append(header)

    // Stage strip
    const stageCard = el('div', { class: 'card' })
    const strip = el('div', { class: 'stage-strip' })
    stages.forEach((st, i) => {
      const rep = st?.['repeat-stage'] > 0 ? ` ↺${st['repeat-stage']}×${st['repeat-times'] ?? 1}` : ''
      strip.append(el('button', {
        class: `stage-pill${i === this.stageIndex ? ' active' : ''}`,
        onClick: () => { this.stopPlayback(); this.stageIndex = i; this.store.emit() },
      }, `Stage ${i + 1}${rep}`))
    })
    if (!readOnly) {
      strip.append(el('button', {
        class: 'small',
        onClick: () => {
          const rows = Math.max(1, Math.floor((anim['inventory-size'] ?? 27) / 9))
          const air = anim.symbols?.air || 'A'
          const blankRow = Array(9).fill(air).join(',')
          mutate((a) => {
            a.stages = a.stages ?? []
            a.stages.push({ grid: Array(rows).fill(blankRow), 'stay-ticks': a['default-frame-ticks'] ?? 4, 'reset-rewards': [], 'reward-slots': [] })
          })
          this.stageIndex = (this.store.animations[activeId]?.stages?.length ?? 1) - 1
          this.store.emit()
        },
      }, '+ Add Stage'))
      if (stage) {
        if (this.stageIndex > 0) {
          strip.append(el('button', {
            class: 'small',
            title: 'Move stage earlier',
            onClick: () => {
              const idx = this.stageIndex
              mutate((a) => { ;[a.stages[idx - 1], a.stages[idx]] = [a.stages[idx], a.stages[idx - 1]] })
              this.stageIndex = idx - 1
              this.store.emit()
            },
          }, '◀'))
        }
        if (this.stageIndex < stages.length - 1) {
          strip.append(el('button', {
            class: 'small',
            title: 'Move stage later',
            onClick: () => {
              const idx = this.stageIndex
              mutate((a) => { ;[a.stages[idx + 1], a.stages[idx]] = [a.stages[idx], a.stages[idx + 1]] })
              this.stageIndex = idx + 1
              this.store.emit()
            },
          }, '▶'))
        }
        strip.append(el('button', {
          class: 'small',
          onClick: () => {
            mutate((a) => { a.stages.splice(this.stageIndex + 1, 0, structuredClone(stage)) })
            this.stageIndex++
            this.store.emit()
          },
        }, 'Duplicate'))
        if (stages.length > 1) {
          strip.append(el('button', {
            class: 'small danger',
            onClick: () => {
              mutate((a) => { a.stages.splice(this.stageIndex, 1) })
              this.stageIndex = Math.max(0, this.stageIndex - 1)
              this.store.emit()
            },
          }, 'Remove'))
        }
      }
    }
    stageCard.append(strip)

    // Interactive Minecraft chest slot grid
    if (stage) {
      const rows = Math.max(1, Math.min(6, Math.floor((anim['inventory-size'] ?? 27) / 9)))
      const air = anim.symbols?.air || 'A'
      const winSet = new Set(stage['reward-slots'] ?? [])

      if (stage['repeat-stage'] > 0) {
        stageCard.append(el('div', { class: 'muted' }, `Repeats Stage #${stage['repeat-stage']} (${stage['repeat-times'] ?? 1}×). Clear repeat-stage to edit a custom grid.`))
      } else {
        const normalizedGrid = Array.from({ length: rows }, (_, r) => {
          const line = stage.grid?.[r] ?? Array(9).fill(air).join(',')
          const cols = String(line).split(',').map((s) => s.trim())
          while (cols.length < 9) cols.push(air)
          return cols.slice(0, 9)
        })

        const applyGrid = (g) => {
          mutate((a) => { a.stages[this.stageIndex].grid = g.map((row) => row.join(',')) })
        }

        if (!readOnly) {
          stageCard.append(el('div', { class: 'row-inline' },
            el('span', { class: 'muted', style: 'font-size:12px' }, `Brush: "${this.brush}" · Left-drag paint · Right-click erase · Shift+click winning slot`),
            el('div', { style: 'flex:1' }),
            el('button', {
              class: 'small',
              onClick: () => {
                const g = normalizedGrid.map((row) => row.slice())
                for (let r = 0; r < rows; r++) {
                  for (let c = 0; c < 9; c++) {
                    if (r === 0 || r === rows - 1 || c === 0 || c === 8) g[r][c] = this.brush
                  }
                }
                applyGrid(g)
              },
            }, 'Fill Border'),
            el('button', {
              class: 'small',
              onClick: () => applyGrid(Array.from({ length: rows }, () => Array(9).fill(air))),
            }, 'Clear Grid'),
          ))
        }

        const chestTitle = anim['inventory-title'] || `Stage ${this.stageIndex + 1}`
        const chest = el('div', { class: 'mc-chest' },
          el('div', { class: 'mc-chest-title' }, mcTextCanvas(renderEnv.text, chestTitle, { scale: 2, color: '#3f3f3f', shadow: false })),
        )
        const gridEl = el('div', { class: 'mc-grid' })

        const paintSlot = (r, c, sym, shiftKey) => {
          if (readOnly) return
          const coord = `${r + 1},${c + 1}`
          mutate((a) => {
            const st = a.stages[this.stageIndex]
            if (shiftKey) {
              const list = new Set(st['reward-slots'] ?? [])
              if (list.has(coord)) list.delete(coord)
              else list.add(coord)
              st['reward-slots'] = [...list]
            } else {
              const g = normalizedGrid.map((row) => row.slice())
              g[r][c] = sym
              st.grid = g.map((row) => row.join(','))
            }
          })
        }

        for (let r = 0; r < rows; r++) {
          for (let c = 0; c < 9; c++) {
            const sym = normalizedGrid[r][c]
            const coord = `${r + 1},${c + 1}`
            const isWin = winSet.has(coord)
            const previewItem = sym === air ? null : previewItemForSymbol(sym, anim)
            const bg = sym === air ? '#8b8b8b' : (previewItem ? '#8b8b8b' : colorForSymbol(sym, anim.fillers))
            const slot = el('div', {
              class: `mc-slot${isWin ? ' win-slot' : ''}`,
              style: `background:${bg}`,
              title: `Row ${r + 1}, Col ${c + 1} (${sym})${isWin ? ' [WINNING SLOT]' : ''}`,
              onContextmenu: (e) => e.preventDefault(),
              onPointerdown: (e) => {
                e.preventDefault()
                const chosen = e.button === 2 ? air : this.brush
                if (!e.shiftKey) this.paintSym = chosen
                paintSlot(r, c, chosen, e.shiftKey)
              },
              onPointerenter: () => {
                if (this.paintSym !== null) paintSlot(r, c, this.paintSym, false)
              },
            },
              el('span', { class: 'slot-idx' }, String(r * 9 + c)),
              previewItem ? el('div', { class: 'mc-slot-icon' }, renderItemIcon(previewItem, 2, { contain: true })) : null,
              sym !== air ? el('span', { class: previewItem ? 'mc-slot-sym' : '' }, sym) : null,
            )
            gridEl.append(slot)
          }
        }
        chest.append(gridEl)
        stageCard.append(chest)
      }

      // Stage properties form
      stageCard.append(renderObjectForm({
        schema: this.schema,
        typeName: 'AnimationStage',
        value: stage,
        readOnly,
        excludeKeys: ['grid'],
        onChange: (nextStage) => mutate((a) => { a.stages[this.stageIndex] = { ...nextStage, grid: a.stages[this.stageIndex].grid } }),
      }))
    }
    center.append(stageCard)
    root.append(center)

    // Right sidebar: Animation global settings & fillers
    const right = el('aside', { class: 'sidebar right' })
    right.append(el('div', { class: 'side-head' }, el('h2', {}, 'Animation Settings')))
    const rBody = el('div', { class: 'side-body', style: 'padding:12px', 'data-scroll-key': 'anims-right' })
    rBody.append(renderObjectForm({
      schema: this.schema,
      typeName: 'AnimationConfig',
      value: anim,
      readOnly,
      excludeKeys: ['stages'],
      onChange: (next) => mutate((a) => {
        const keepStages = a.stages
        Object.assign(a, next, { stages: keepStages })
      }),
    }))
    right.append(rBody)
    root.append(right)

    return root
  }

  paletteButton(sym, label, anim) {
    const item = previewItemForSymbol(sym, anim)
    return el('button', {
      class: `palette-btn${this.brush === sym ? ' active' : ''}`,
      onClick: () => { this.brush = sym; this.store.emit() },
    },
      item
        ? el('span', { class: 'palette-chip', style: 'background:#313643' }, renderItemIcon(item, 1.25, { contain: true }))
        : el('span', { class: 'palette-chip', style: `background:${colorForSymbol(sym, anim.fillers)}` }, sym),
      el('span', { style: 'font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, `${sym} · ${label}`),
    )
  }

  togglePlay(anim) {
    if (this.playing) {
      this.stopPlayback()
      this.store.emit()
      return
    }
    const stages = anim.stages ?? []
    if (stages.length === 0) return
    this.playing = true
    const step = () => {
      if (!this.playing) return
      const st = stages[this.stageIndex]
      const ticks = Math.max(2, st?.['stay-ticks'] > 0 ? st['stay-ticks'] : (anim['default-frame-ticks'] ?? 4))
      this.store.emit()
      this.playTimer = setTimeout(() => {
        if (!this.playing) return
        this.stageIndex = (this.stageIndex + 1) % stages.length
        step()
      }, ticks * 50)
    }
    step()
  }

  async createAnimation() {
    const raw = await promptModal({
      title: 'Create New Animation',
      message: 'Choose a lowercase animation ID (letters, digits, - or _).',
      initialValue: 'my-anim',
      placeholder: 'my-anim',
      confirmText: 'Create',
      validate: (v) => {
        const id = v.toLowerCase()
        if (!ID_RE.test(id)) return 'Invalid animation ID. Use lowercase letters, digits, - or _.'
        if (this.store.animations[id]) return `Animation '${id}' already exists.`
        return null
      },
    })
    if (!raw) return
    const id = raw.toLowerCase()
    this.store.setAnimation(id, starterAnimation())
    this.stageIndex = 0
    this.store.select('animation', id)
    this.onToast?.(`Created animation '${id}'`)
  }
}
