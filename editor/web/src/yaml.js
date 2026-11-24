// Clean, dependency-free YAML serializer & parser covering the subset used by XaliCrates configs
// (nested mappings, lists of scalars/objects, inline [] / {}, quoted strings, numbers, booleans, nulls, comments).

const NEEDS_QUOTE = /(?:^[\s&*!|>'"%@`?,-]|[\s:#]$|[:#]\s|\n|\r|\t|^$)/
const YAML_BOOL_OR_NULL = /^(?:true|false|yes|no|on|off|null|~)$/i
const YAML_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/

export function formatScalar(value) {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '0'
  const str = String(value)
  if (str.length === 0 || NEEDS_QUOTE.test(str) || YAML_BOOL_OR_NULL.test(str) || YAML_NUMBER.test(str)) {
    return JSON.stringify(str)
  }
  return str
}

/** Serializes a JS value (CrateConfig or AnimationConfig object) into clean 2-space indented YAML. */
export function dumpYaml(value, indent = 0) {
  const pad = ' '.repeat(indent)
  if (value === null || value === undefined || typeof value !== 'object') {
    return formatScalar(value)
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]'
    return value.map((item) => {
      if (item !== null && typeof item === 'object' && !Array.isArray(item)) {
        const keys = Object.keys(item).filter((k) => item[k] !== undefined)
        if (keys.length === 0) return `${pad}- {}`
        const firstKey = keys[0]
        const firstVal = item[firstKey]
        const lines = []
        if (isCompoundNonEmpty(firstVal)) {
          lines.push(`${pad}- ${formatKey(firstKey)}:`)
          lines.push(dumpYaml(firstVal, indent + 4))
        } else {
          lines.push(`${pad}- ${formatKey(firstKey)}: ${dumpYaml(firstVal, indent + 4)}`)
        }
        for (let i = 1; i < keys.length; i++) {
          const k = keys[i]
          const v = item[k]
          if (isCompoundNonEmpty(v)) {
            lines.push(`${pad}  ${formatKey(k)}:`)
            lines.push(dumpYaml(v, indent + 4))
          } else {
            lines.push(`${pad}  ${formatKey(k)}: ${dumpYaml(v, indent + 4)}`)
          }
        }
        return lines.join('\n')
      }
      if (Array.isArray(item) && item.length > 0) {
        return `${pad}-\n${dumpYaml(item, indent + 2)}`
      }
      return `${pad}- ${formatScalar(item)}`
    }).join('\n')
  }

  const keys = Object.keys(value).filter((k) => value[k] !== undefined)
  if (keys.length === 0) return '{}'
  return keys.map((k) => {
    const v = value[k]
    if (isCompoundNonEmpty(v)) {
      return `${pad}${formatKey(k)}:\n${dumpYaml(v, indent + 2)}`
    }
    return `${pad}${formatKey(k)}: ${dumpYaml(v, indent + 2)}`
  }).join('\n')
}

function isCompoundNonEmpty(v) {
  if (v === null || typeof v !== 'object') return false
  if (Array.isArray(v)) return v.length > 0
  return Object.keys(v).some((k) => v[k] !== undefined)
}

function formatKey(key) {
  if (/^[a-zA-Z0-9_-]+$/.test(key) && !YAML_BOOL_OR_NULL.test(key) && !YAML_NUMBER.test(key)) return key
  return JSON.stringify(key)
}

// ---- Parser

function stripComment(line) {
  let inSingle = false
  let inDouble = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (inDouble) {
      if (ch === '\\') i++
      else if (ch === '"') inDouble = false
    } else if (inSingle) {
      if (ch === "'" && line[i + 1] === "'") i++
      else if (ch === "'") inSingle = false
    } else {
      if (ch === '"') inDouble = true
      else if (ch === "'") inSingle = true
      else if (ch === '#' && (i === 0 || /\s/.test(line[i - 1]))) return line.slice(0, i)
    }
  }
  return line
}

function parseScalar(raw) {
  const s = raw.trim()
  if (s === '' || s === 'null' || s === '~') return null
  if (s === 'true') return true
  if (s === 'false') return false
  if (s === '[]') return []
  if (s === '{}') return {}
  if (s.startsWith('"') && s.endsWith('"') && s.length >= 2) {
    return JSON.parse(s)
  }
  if (s.startsWith("'") && s.endsWith("'") && s.length >= 2) {
    return s.slice(1, -1).replace(/''/g, "'")
  }
  if (s.startsWith('[') && s.endsWith(']')) {
    const inner = s.slice(1, -1).trim()
    if (!inner) return []
    return splitFlow(inner).map(parseScalar)
  }
  if (s.startsWith('{') && s.endsWith('}')) {
    const inner = s.slice(1, -1).trim()
    if (!inner) return {}
    const obj = {}
    for (const part of splitFlow(inner)) {
      const idx = findColon(part)
      if (idx < 0) throw new Error(`Invalid flow mapping entry: ${part}`)
      const k = parseKey(part.slice(0, idx))
      obj[k] = parseScalar(part.slice(idx + 1))
    }
    return obj
  }
  if (YAML_NUMBER.test(s)) return Number(s)
  return s
}

function splitFlow(text) {
  const out = []
  let cur = ''
  let depth = 0
  let q = null
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (q) {
      cur += ch
      if (q === '"' && ch === '\\') { cur += text[++i] ?? '' }
      else if (ch === q) q = null
    } else if (ch === '"' || ch === "'") {
      q = ch
      cur += ch
    } else if (ch === '[' || ch === '{') {
      depth++
      cur += ch
    } else if (ch === ']' || ch === '}') {
      depth--
      cur += ch
    } else if (ch === ',' && depth === 0) {
      out.push(cur)
      cur = ''
    } else {
      cur += ch
    }
  }
  if (cur.trim()) out.push(cur)
  return out
}

function findColon(text) {
  let q = null
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (q) {
      if (q === '"' && ch === '\\') i++
      else if (ch === q) q = null
    } else if (ch === '"' || ch === "'") {
      q = ch
    } else if (ch === ':' && (i === text.length - 1 || /\s/.test(text[i + 1]))) {
      return i
    }
  }
  return -1
}

function parseKey(raw) {
  const s = raw.trim()
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    return String(parseScalar(s))
  }
  return s
}

/** Parses a YAML string into plain JS objects, arrays and primitives. Throws on syntax error. */
export function parseYaml(text) {
  const rawLines = String(text ?? '').replace(/\r\n/g, '\n').split('\n')
  const lines = []
  for (let idx = 0; idx < rawLines.length; idx++) {
    const cleaned = stripComment(rawLines[idx])
    if (!cleaned.trim()) continue
    const indent = cleaned.length - cleaned.trimStart().length
    lines.push({ lineNo: idx + 1, indent, text: cleaned.trim() })
  }
  if (lines.length === 0) return {}

  let pos = 0

  function parseBlock(minIndent) {
    if (pos >= lines.length || lines[pos].indent < minIndent) return null
    if (lines[pos].text === '-' || lines[pos].text.startsWith('- ')) {
      return parseList(lines[pos].indent)
    }
    return parseMap(lines[pos].indent)
  }

  function parseList(listIndent) {
    const arr = []
    while (pos < lines.length && lines[pos].indent === listIndent && (lines[pos].text === '-' || lines[pos].text.startsWith('- '))) {
      const entry = lines[pos]
      const rest = entry.text.slice(1).trim()
      pos++
      if (!rest) {
        arr.push(pos < lines.length && lines[pos].indent > listIndent ? parseBlock(lines[pos].indent) : null)
      } else {
        const colon = findColon(rest)
        if (colon >= 0 && !rest.startsWith('"') && !rest.startsWith("'") && !rest.startsWith('[') && !rest.startsWith('{')) {
          // Inline object item inside list: `- key: value`
          const obj = {}
          const k = parseKey(rest.slice(0, colon))
          const valPart = rest.slice(colon + 1).trim()
          const itemIndent = listIndent + 2
          if (valPart === '' || valPart === '|' || valPart === '>') {
            obj[k] = pos < lines.length && lines[pos].indent > itemIndent ? parseBlock(lines[pos].indent) : (valPart ? '' : null)
          } else {
            obj[k] = parseScalar(valPart)
          }
          while (pos < lines.length && lines[pos].indent >= itemIndent) {
            if (lines[pos].indent > itemIndent) break
            const sub = lines[pos]
            const c = findColon(sub.text)
            if (c < 0) throw new Error(`Line ${sub.lineNo}: expected 'key: value', got '${sub.text}'`)
            const sk = parseKey(sub.text.slice(0, c))
            const sv = sub.text.slice(c + 1).trim()
            pos++
            if (sv === '') {
              obj[sk] = pos < lines.length && lines[pos].indent > itemIndent ? parseBlock(lines[pos].indent) : null
            } else {
              obj[sk] = parseScalar(sv)
            }
          }
          arr.push(obj)
        } else {
          arr.push(parseScalar(rest))
        }
      }
    }
    return arr
  }

  function parseMap(mapIndent) {
    const obj = {}
    while (pos < lines.length && lines[pos].indent === mapIndent) {
      const entry = lines[pos]
      if (entry.text === '-' || entry.text.startsWith('- ')) break
      const colon = findColon(entry.text)
      if (colon < 0) throw new Error(`Line ${entry.lineNo}: expected 'key: value', got '${entry.text}'`)
      const key = parseKey(entry.text.slice(0, colon))
      const valPart = entry.text.slice(colon + 1).trim()
      pos++
      if (valPart === '') {
        if (pos < lines.length && lines[pos].indent > mapIndent) {
          obj[key] = parseBlock(lines[pos].indent)
        } else if (pos < lines.length && lines[pos].indent === mapIndent && (lines[pos].text === '-' || lines[pos].text.startsWith('- '))) {
          obj[key] = parseList(mapIndent)
        } else {
          obj[key] = null
        }
      } else {
        obj[key] = parseScalar(valPart)
      }
    }
    return obj
  }

  return parseBlock(lines[0].indent)
}
