// Schema-driven form renderer for any TypeDef in schema.json, with live Minecraft text previews and registry datalists.

import { defaultForType, defaultObject, typeDef } from './schema.js'
import { renderMcHtml } from './mctext.js'

const MC_TEXT_KEYS = new Set(['name', 'display-name', 'inventory-title', 'entity-name', 'reward-line', 'value', 'lore', 'lines'])
const DATALIST_MAP = {
  material: 'reg-materials',
  block: 'reg-materials',
  sound: 'reg-sounds',
  type: 'reg-particles',
  entity: 'reg-entities',
}

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue
    if (k === 'class') node.className = v
    else if (k === 'html') node.innerHTML = v
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v)
    else if (k === 'checked' || k === 'disabled' || k === 'selected' || k === 'open') node[k] = Boolean(v)
    else if (k === 'value') node.value = v
    else node.setAttribute(k, String(v))
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue
    node.append(typeof child === 'string' || typeof child === 'number' ? document.createTextNode(String(child)) : child)
  }
  return node
}

/** Ensures global <datalist> elements exist for live server registries (materials, sounds, particles, entities). */
export function syncRegistryDatalists(registries) {
  const map = {
    'reg-materials': registries?.materials ?? [],
    'reg-sounds': registries?.sounds ?? [],
    'reg-particles': registries?.particles ?? [],
    'reg-entities': registries?.entities ?? [],
  }
  for (const [id, items] of Object.entries(map)) {
    let dl = document.getElementById(id)
    if (!dl) {
      dl = document.createElement('datalist')
      dl.id = id
      document.body.append(dl)
    }
    dl.innerHTML = ''
    for (const item of items) {
      const opt = document.createElement('option')
      opt.value = item
      dl.append(opt)
    }
  }
}

/**
 * Renders a schema-driven object form for `typeName`.
 * `onChange(nextObject)` is called whenever any field mutates.
 */
export function renderObjectForm({ schema, typeName, value, onChange, readOnly = false, excludeKeys = [], onlyKeys = null }) {
  const def = typeDef(schema, typeName)
  const container = el('div', { class: 'form-object' })
  if (!def) return container
  const obj = value && typeof value === 'object' ? value : {}
  const skip = new Set(excludeKeys)
  const allow = onlyKeys ? new Set(onlyKeys) : null

  for (const field of def.fields) {
    if (skip.has(field.key)) continue
    if (allow && !allow.has(field.key)) continue
    const fieldVal = obj[field.key]

    // Nested object -> collapsible details section
    if (field.type.kind === 'object') {
      const isNullable = field.nullable
      const isPresent = fieldVal !== null && fieldVal !== undefined
      const details = el('details', { class: 'nested', open: isPresent && !isNullable })
      const summary = el('summary', {}, field.key, isNullable && !isPresent ? el('span', { class: 'muted' }, ' (not set)') : '')
      details.append(summary)
      const body = el('div', { class: 'nested-body' })
      if (field.doc) body.append(el('div', { class: 'muted', style: 'font-size:12px' }, field.doc))
      if (isNullable && !isPresent) {
        if (!readOnly) {
          body.append(el('button', {
            class: 'small',
            onClick: () => onChange({ ...obj, [field.key]: defaultObject(schema, field.type.type) }),
          }, `+ Configure ${field.key}`))
        }
      } else {
        if (isNullable && !readOnly) {
          body.append(el('button', {
            class: 'small danger',
            style: 'align-self:start',
            onClick: () => onChange({ ...obj, [field.key]: null }),
          }, `Clear ${field.key}`))
        }
        body.append(renderObjectForm({
          schema,
          typeName: field.type.type,
          value: fieldVal ?? defaultObject(schema, field.type.type),
          readOnly,
          onChange: (nextSub) => onChange({ ...obj, [field.key]: nextSub }),
        }))
      }
      details.append(body)
      container.append(details)
      continue
    }

    const row = el('div', { class: 'field' })
    row.append(el('label', {}, field.key))
    const ctrl = el('div', { class: 'control' })

    const setField = (next) => onChange({ ...obj, [field.key]: next })

    if (field.type.kind === 'boolean') {
      if (field.nullable) {
        const sel = el('select', {
          disabled: readOnly,
          onChange: (e) => setField(e.target.value === '' ? null : e.target.value === 'true'),
        },
          el('option', { value: '', selected: fieldVal === null || fieldVal === undefined }, '(inherit / default)'),
          el('option', { value: 'true', selected: fieldVal === true }, 'true'),
          el('option', { value: 'false', selected: fieldVal === false }, 'false'),
        )
        ctrl.append(sel)
      } else {
        ctrl.append(el('input', {
          type: 'checkbox',
          checked: Boolean(fieldVal ?? field.default ?? false),
          disabled: readOnly,
          onChange: (e) => setField(e.target.checked),
        }))
      }
    } else if (field.type.kind === 'enum') {
      const sel = el('select', {
        disabled: readOnly,
        onChange: (e) => setField(e.target.value === '' ? null : e.target.value),
      })
      if (field.nullable) {
        sel.append(el('option', { value: '', selected: !fieldVal }, '(inherit / default)'))
      }
      for (const opt of field.type.values) {
        sel.append(el('option', { value: opt, selected: (fieldVal ?? field.default) === opt }, opt))
      }
      ctrl.append(sel)
    } else if (field.type.kind === 'int' || field.type.kind === 'number') {
      ctrl.append(el('input', {
        type: 'number',
        step: field.type.kind === 'int' ? '1' : 'any',
        min: field.min ?? undefined,
        max: field.max ?? undefined,
        value: fieldVal ?? field.default ?? '',
        disabled: readOnly,
        onChange: (e) => {
          const raw = e.target.value.trim()
          if (raw === '' && field.nullable) setField(null)
          else setField(field.type.kind === 'int' ? parseInt(raw || '0', 10) : parseFloat(raw || '0'))
        },
      }))
    } else if (field.type.kind === 'string') {
      const listId = typeName === 'ParticleConfig' && field.key === 'type' ? 'reg-particles' : DATALIST_MAP[field.key]
      const input = el('input', {
        type: 'text',
        value: fieldVal ?? (field.nullable ? '' : (field.default ?? '')),
        placeholder: field.nullable ? '(not set)' : '',
        list: listId,
        disabled: readOnly,
        onChange: (e) => {
          const v = e.target.value
          setField(field.nullable && v === '' ? null : v)
        },
      })
      ctrl.append(input)
      if (MC_TEXT_KEYS.has(field.key) && fieldVal) {
        ctrl.append(el('div', { class: 'mc-preview', html: renderMcHtml(fieldVal) }))
      }
    } else if (field.type.kind === 'list') {
      ctrl.append(renderListEditor({
        schema,
        fieldKey: field.key,
        itemType: field.type.of,
        items: Array.isArray(fieldVal) ? fieldVal : [],
        readOnly,
        onChange: setField,
      }))
    } else if (field.type.kind === 'map') {
      ctrl.append(renderMapEditor({
        schema,
        valueType: field.type.of,
        map: fieldVal && typeof fieldVal === 'object' ? fieldVal : {},
        readOnly,
        onChange: setField,
      }))
    } else {
      ctrl.append(el('input', {
        type: 'text',
        value: fieldVal ?? '',
        disabled: readOnly,
        onChange: (e) => setField(e.target.value),
      }))
    }

    row.append(ctrl)
    if (field.doc) row.append(el('div', { class: 'hint' }, field.doc))
    container.append(row)
  }

  return container
}

function renderListEditor({ schema, fieldKey, itemType, items, readOnly, onChange }) {
  const box = el('div', { class: 'list-box' })
  items.forEach((item, idx) => {
    const row = el('div', { class: 'list-row' })
    const updateAt = (next) => {
      const copy = items.slice()
      copy[idx] = next
      onChange(copy)
    }
    if (itemType.kind === 'object') {
      const card = el('details', { class: 'nested', open: items.length <= 3 })
      card.append(el('summary', {}, `#${idx + 1} ${item?.type ?? item?.sound ?? item?.material ?? itemType.type}`))
      const body = el('div', { class: 'nested-body' })
      body.append(renderObjectForm({ schema, typeName: itemType.type, value: item, readOnly, onChange: updateAt }))
      card.append(body)
      row.append(card)
    } else if (itemType.kind === 'int' || itemType.kind === 'number') {
      row.append(el('input', {
        type: 'number',
        value: item ?? 0,
        disabled: readOnly,
        onChange: (e) => updateAt(Number(e.target.value)),
      }))
    } else {
      const wrap = el('div', { style: 'display:flex;flex-direction:column;gap:3px' })
      wrap.append(el('input', {
        type: 'text',
        value: item ?? '',
        disabled: readOnly,
        onChange: (e) => updateAt(e.target.value),
      }))
      if (MC_TEXT_KEYS.has(fieldKey) && item) {
        wrap.append(el('div', { class: 'mc-preview', html: renderMcHtml(item) }))
      }
      row.append(wrap)
    }
    if (!readOnly) {
      row.append(el('button', {
        class: 'icon danger',
        title: 'Remove',
        onClick: () => onChange(items.filter((_, i) => i !== idx)),
      }, '×'))
    }
    box.append(row)
  })
  if (!readOnly) {
    box.append(el('button', {
      class: 'small',
      style: 'align-self:start',
      onClick: () => onChange([...items, defaultForType(schema, itemType)]),
    }, `+ Add ${fieldKey}`))
  }
  return box
}

function renderMapEditor({ schema, valueType, map, readOnly, onChange }) {
  const box = el('div', { class: 'map-box' })
  for (const [k, v] of Object.entries(map)) {
    const entry = el('div', { class: 'map-entry' })
    const head = el('div', { class: 'map-entry-head' })
    head.append(el('input', {
      type: 'text',
      value: k,
      disabled: readOnly,
      onChange: (e) => {
        const nk = e.target.value.trim()
        if (!nk || nk === k) return
        const next = {}
        for (const [ek, ev] of Object.entries(map)) next[ek === k ? nk : ek] = ev
        onChange(next)
      },
    }))
    if (!readOnly) {
      head.append(el('button', {
        class: 'small danger',
        onClick: () => {
          const next = { ...map }
          delete next[k]
          onChange(next)
        },
      }, 'Delete'))
    }
    entry.append(head)
    if (valueType.kind === 'object') {
      entry.append(renderObjectForm({
        schema,
        typeName: valueType.type,
        value: v,
        readOnly,
        onChange: (nextVal) => onChange({ ...map, [k]: nextVal }),
      }))
    } else {
      entry.append(el('input', {
        type: valueType.kind === 'int' || valueType.kind === 'number' ? 'number' : 'text',
        value: v ?? '',
        disabled: readOnly,
        onChange: (e) => onChange({
          ...map,
          [k]: valueType.kind === 'int' ? parseInt(e.target.value || '0', 10)
            : valueType.kind === 'number' ? parseFloat(e.target.value || '0')
            : e.target.value,
        }),
      }))
    }
    box.append(entry)
  }
  if (!readOnly) {
    box.append(el('button', {
      class: 'small',
      style: 'align-self:start',
      onClick: () => {
        let idx = Object.keys(map).length + 1
        while (map[`entry_${idx}`] !== undefined) idx++
        onChange({ ...map, [`entry_${idx}`]: defaultForType(schema, valueType) })
      },
    }, '+ Add Entry'))
  }
  return box
}
