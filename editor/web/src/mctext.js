// Renders Minecraft legacy (& / §), hex (&#RRGGBB / {#RRGGBB}), and gradient ({#a0a>}...{#fff<}) codes to HTML spans.

const LEGACY_COLORS = {
  '0': '#000000', '1': '#0000aa', '2': '#00aa00', '3': '#00aaaa',
  '4': '#aa0000', '5': '#aa00aa', '6': '#ffaa00', '7': '#aaaaaa',
  '8': '#555555', '9': '#5555ff', a: '#55ff55', b: '#55ffff',
  c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff',
}

function expandHex(hex) {
  const clean = hex.replace('#', '')
  if (clean.length === 3) return '#' + clean.split('').map((c) => c + c).join('')
  return '#' + clean
}

function hexToRgb(hex) {
  const full = expandHex(hex).slice(1)
  return [
    parseInt(full.slice(0, 2), 16),
    parseInt(full.slice(2, 4), 16),
    parseInt(full.slice(4, 6), 16),
  ]
}

function lerpColor(a, b, t) {
  const r = Math.round(a[0] + (b[0] - a[0]) * t)
  const g = Math.round(a[1] + (b[1] - a[1]) * t)
  const bl = Math.round(a[2] + (b[2] - a[2]) * t)
  return `rgb(${r},${g},${bl})`
}

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** Strips formatting codes for plain-text previews and sorting. */
export function stripMc(text) {
  return String(text ?? '')
    .replace(/\{#[0-9a-fA-F]{3,6}[<>]?\}/g, '')
    .replace(/[&§]#[0-9a-fA-F]{6}/g, '')
    .replace(/[&§][0-9a-fk-orA-FK-OR]/g, '')
}

/** Converts a formatted Minecraft string into an HTML string of styled <span>s. */
export function renderMcHtml(text) {
  const raw = String(text ?? '')
  // Expand Xali gradient syntax `{#a0a>}text{#fff<}` into per-character inline hex spans.
  const expanded = raw.replace(/\{#([0-9a-fA-F]{3,6})>\}([\s\S]*?)\{#([0-9a-fA-F]{3,6})<\}/g, (_, startHex, inner, endHex) => {
    const a = hexToRgb(startHex)
    const b = hexToRgb(endHex)
    const chars = Array.from(inner)
    let visibleCount = 0
    for (let i = 0; i < chars.length; i++) {
      if ((chars[i] === '&' || chars[i] === '§') && i + 1 < chars.length) { i++; continue }
      visibleCount++
    }
    let out = ''
    let vi = 0
    for (let i = 0; i < chars.length; i++) {
      if ((chars[i] === '&' || chars[i] === '§') && i + 1 < chars.length) {
        out += chars[i] + chars[i + 1]
        i++
        continue
      }
      const t = visibleCount <= 1 ? 0 : vi / (visibleCount - 1)
      const c = lerpColor(a, b, t)
      out += `\u0001${c}\u0002${chars[i]}`
      vi++
    }
    return out
  })

  let color = '#ffffff'
  let bold = false
  let italic = false
  let underline = false
  let strike = false
  let out = ''
  let buf = ''

  function flush() {
    if (!buf) return
    const styles = [`color:${color}`]
    if (bold) styles.push('font-weight:700')
    if (italic) styles.push('font-style:italic')
    const decs = []
    if (underline) decs.push('underline')
    if (strike) decs.push('line-through')
    if (decs.length) styles.push(`text-decoration:${decs.join(' ')}`)
    out += `<span style="${styles.join(';')}">${escapeHtml(buf)}</span>`
    buf = ''
  }

  for (let i = 0; i < expanded.length; i++) {
    const ch = expanded[i]
    if (ch === '\u0001') {
      const end = expanded.indexOf('\u0002', i)
      if (end > i) {
        flush()
        color = expanded.slice(i + 1, end)
        i = end
        continue
      }
    }
    if ((ch === '&' || ch === '§') && i + 1 < expanded.length) {
      const next = expanded[i + 1]
      if (next === '#' && /^[0-9a-fA-F]{6}/.test(expanded.slice(i + 2, i + 8))) {
        flush()
        color = '#' + expanded.slice(i + 2, i + 8)
        bold = italic = underline = strike = false
        i += 7
        continue
      }
      const lower = next.toLowerCase()
      if (LEGACY_COLORS[lower]) {
        flush()
        color = LEGACY_COLORS[lower]
        bold = italic = underline = strike = false
        i++
        continue
      }
      if (lower === 'l') { flush(); bold = true; i++; continue }
      if (lower === 'o') { flush(); italic = true; i++; continue }
      if (lower === 'n') { flush(); underline = true; i++; continue }
      if (lower === 'm') { flush(); strike = true; i++; continue }
      if (lower === 'r') { flush(); color = '#ffffff'; bold = italic = underline = strike = false; i++; continue }
      if (lower === 'k') { i++; continue }
    }
    buf += ch
  }
  flush()
  return out
}
