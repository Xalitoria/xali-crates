// Client-side validation mirroring mc.xalitoria.crates.paper.data.model.Validation.

export const ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/
const DURATION_PART = /^\s*(?:\d+\s*(?:ms|s|m|h|d)\s*)+$/i

export function isValidDuration(text) {
  if (!text || typeof text !== 'string') return false
  const t = text.trim()
  return t === '0' || DURATION_PART.test(t)
}

/** Computes probability percentage (0..100) for every reward in a crate. */
export function rewardProbabilities(crate) {
  const rewards = crate?.rewards ?? {}
  let totalWeight = 0
  for (const r of Object.values(rewards)) {
    if (!r?.['fallback-only'] && !r?.guaranteed && Number(r?.weight ?? 0) > 0) {
      totalWeight += Number(r.weight)
    }
  }
  const out = {}
  for (const [id, r] of Object.entries(rewards)) {
    if (r?.guaranteed) out[id] = 100
    else if (r?.['fallback-only'] || Number(r?.weight ?? 0) <= 0 || totalWeight <= 0) out[id] = 0
    else out[id] = (Number(r.weight) / totalWeight) * 100
  }
  return { totalWeight, percentages: out }
}

/** Validates one CrateConfig object and returns `{ path, message, severity }[]`. */
export function validateCrate(id, crate, { builtinAnimations = [], knownAnimations = [], knownCrates = [] } = {}) {
  const problems = []
  const err = (path, message) => problems.push({ path, message, severity: 'ERROR' })
  const warn = (path, message) => problems.push({ path, message, severity: 'WARNING' })

  if (!ID_PATTERN.test(id)) err('id', 'Crate id must match [a-z0-9][a-z0-9_-]{0,63}')
  if (!crate || typeof crate !== 'object') {
    err('root', 'Crate document must be an object')
    return problems
  }

  const opening = crate.opening ?? {}
  if (opening['required-slots'] !== undefined && (opening['required-slots'] < -1 || opening['required-slots'] > 36)) {
    err('opening.required-slots', 'Must be between -1 and 36')
  }
  if (crate.cooldown?.duration && !isValidDuration(crate.cooldown.duration)) {
    err('cooldown.duration', `Invalid duration '${crate.cooldown.duration}' (expected e.g. 30s, 5m, 1h)`)
  }
  if (crate.hologram?.['reward-duration'] && !isValidDuration(crate.hologram['reward-duration'])) {
    err('hologram.reward-duration', `Invalid duration '${crate.hologram['reward-duration']}'`)
  }
  if (crate.display?.kind === 'ENTITY' && !crate.display?.entity) {
    err('display.entity', 'Entity type is required when display.kind is ENTITY')
  }

  const isMulti = crate['multi-crate']?.enabled === true
  if (isMulti) {
    const children = crate['multi-crate']?.crates ?? []
    children.forEach((child, i) => {
      if (String(child).toLowerCase() === id.toLowerCase()) {
        err(`multi-crate.crates[${i}]`, 'A multi-crate cannot reference itself')
      } else if (knownCrates.length > 0 && !knownCrates.includes(String(child).toLowerCase())) {
        warn(`multi-crate.crates[${i}]`, `References unknown crate '${child}'`)
      }
    })
    return problems
  }

  const anim = String(opening.animation ?? 'csgo').toLowerCase()
  const allAnims = new Set([...builtinAnimations, ...knownAnimations])
  if (allAnims.size > 0 && !allAnims.has(anim)) {
    warn('opening.animation', `Unknown animation '${opening.animation}'`)
  }

  const rewards = crate.rewards ?? {}
  const rewardIds = Object.keys(rewards)
  if (rewardIds.length === 0) {
    warn('rewards', 'Crate has no rewards configured')
  } else {
    let totalWeight = 0
    for (const [rid, reward] of Object.entries(rewards)) {
      const base = `rewards.${rid}`
      const w = Number(reward?.weight ?? 0)
      if (Number.isNaN(w) || w < 0) err(`${base}.weight`, 'Weight must not be negative')
      else if (!reward?.['fallback-only'] && !reward?.guaranteed) totalWeight += w

      if (reward?.fallback?.reward) {
        if (!rewards[reward.fallback.reward]) {
          err(`${base}.fallback.reward`, `References unknown reward '${reward.fallback.reward}'`)
        }
        if (!reward.fallback.permission) {
          warn(`${base}.fallback.permission`, 'Fallback has no permission node set')
        }
      }
      for (const linked of reward?.['linked-rewards'] ?? []) {
        if (!rewards[linked]) warn(`${base}.linked-rewards`, `References unknown reward '${linked}'`)
      }
      for (const sh of reward?.['shulker-contents'] ?? []) {
        if (!rewards[sh]) warn(`${base}.shulker-contents`, `References unknown reward '${sh}'`)
      }
      if (reward?.rarity && reward.rarity !== 'default' && crate.tiers && !crate.tiers[reward.rarity]) {
        warn(`${base}.rarity`, `Rarity '${reward.rarity}' is not defined in tiers`)
      }
    }
    if (totalWeight <= 0 && !Object.values(rewards).some((r) => r?.guaranteed)) {
      err('rewards', 'Total weight of rollable rewards must be > 0 (or define at least one guaranteed reward)')
    }
  }

  return problems
}

/** Validates one AnimationConfig object and returns `{ path, message, severity }[]`. */
export function validateAnimation(id, anim) {
  const problems = []
  const err = (path, message) => problems.push({ path, message, severity: 'ERROR' })
  const warn = (path, message) => problems.push({ path, message, severity: 'WARNING' })

  if (!ID_PATTERN.test(id)) err('id', 'Animation id must match [a-z0-9][a-z0-9_-]{0,63}')
  if (!anim || typeof anim !== 'object') {
    err('root', 'Animation document must be an object')
    return problems
  }
  const size = Number(anim['inventory-size'] ?? 27)
  if (size < 9 || size > 54 || size % 9 !== 0) {
    err('inventory-size', 'Must be a multiple of 9 between 9 and 54')
  }
  const expectedRows = Math.floor(size / 9)
  const stages = anim.stages ?? []
  if (!Array.isArray(stages) || stages.length === 0) {
    err('stages', 'Animation must define at least one stage')
    return problems
  }

  stages.forEach((stage, i) => {
    const base = `stages[${i}]`
    const stay = Number(stage?.['stay-ticks'] ?? 0)
    if (stay < -1 || stay > 12000) err(`${base}.stay-ticks`, 'Must be between -1 and 12000')
    const rep = Number(stage?.['repeat-stage'] ?? 0)
    if (rep > 0) {
      if (rep === i + 1) err(`${base}.repeat-stage`, 'A stage cannot repeat itself')
      else if (rep > stages.length) err(`${base}.repeat-stage`, `Stage #${rep} does not exist`)
    } else {
      const grid = stage?.grid ?? []
      if (grid.length === 0) {
        warn(`${base}.grid`, 'Stage has an empty grid and does not repeat another stage')
      } else {
        if (grid.length > expectedRows) {
          warn(`${base}.grid`, `Grid has ${grid.length} rows but inventory-size (${size}) holds ${expectedRows}`)
        }
        grid.forEach((row, r) => {
          const cols = String(row).split(',').map((s) => s.trim())
          if (cols.length !== 9) {
            err(`${base}.grid[${r}]`, `Expected 9 comma-separated columns, found ${cols.length}`)
          }
        })
      }
    }
  })

  return problems
}
