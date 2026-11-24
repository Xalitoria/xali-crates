// Live synchronized Raw YAML tab for inspecting, copying, downloading, or editing the active crate/animation YAML.

import { el } from './form.js'
import { dumpYaml, parseYaml } from './yaml.js'

export class RawTab {
  constructor({ store, getInfo }) {
    this.store = store
    this.getInfo = getInfo
    this.parseError = null
  }

  render() {
    const readOnly = Boolean(this.getInfo()?.readOnly)
    const doc = this.store.activeDocument()
    const { kind, id } = this.store.active
    const wrap = el('div', { class: 'raw-wrap' })

    if (!doc || !id) {
      wrap.append(el('p', { class: 'muted' }, 'Select a crate or animation first.'))
      return wrap
    }

    const yamlText = dumpYaml(doc) + '\n'
    const textarea = el('textarea', {
      class: `raw-yaml${this.parseError ? ' bad' : ''}`,
      spellcheck: 'false',
      disabled: readOnly,
    })
    textarea.value = yamlText

    const status = el('span', { class: this.parseError ? 'bad' : 'muted' }, this.parseError || `${kind}s/${id}.yml`)

    const toolbar = el('div', { class: 'raw-toolbar' },
      el('strong', {}, `${kind === 'crate' ? 'Crate' : 'Animation'}: ${id}`),
      el('div', { style: 'flex:1' }),
      status,
      el('button', {
        class: 'small',
        onClick: async () => {
          await navigator.clipboard?.writeText(textarea.value)
          status.textContent = 'Copied YAML to clipboard!'
        },
      }, 'Copy YAML'),
      el('button', {
        class: 'small',
        onClick: () => {
          const blob = new Blob([textarea.value], { type: 'text/yaml;charset=utf-8' })
          const url = URL.createObjectURL(blob)
          const a = document.createElement('a')
          a.href = url
          a.download = `${id}.yml`
          a.click()
          URL.revokeObjectURL(url)
        },
      }, 'Download .yml'),
    )

    if (!readOnly) {
      toolbar.append(el('button', {
        class: 'small primary',
        onClick: () => {
          try {
            const parsed = parseYaml(textarea.value)
            if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
              throw new Error('YAML root must be a mapping')
            }
            this.parseError = null
            if (kind === 'crate') this.store.setCrate(id, parsed)
            else this.store.setAnimation(id, parsed)
          } catch (e) {
            this.parseError = `YAML parse error: ${e.message}`
            this.store.emit()
          }
        },
      }, 'Apply YAML'))
    }

    wrap.append(toolbar, textarea)
    return wrap
  }
}
