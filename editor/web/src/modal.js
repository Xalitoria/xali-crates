// Accessible in-app modal dialogs replacing browser prompt()/confirm()/alert().

import { el } from './form.js'

function mountBackdrop(dialog, onCancel) {
  const backdrop = el('div', { class: 'modal-backdrop', role: 'dialog', 'aria-modal': 'true' }, dialog)
  const onKey = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault()
      cleanup()
      onCancel()
    }
  }
  const cleanup = () => {
    window.removeEventListener('keydown', onKey)
    backdrop.remove()
  }
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) {
      cleanup()
      onCancel()
    }
  })
  window.addEventListener('keydown', onKey)
  document.body.append(backdrop)
  return cleanup
}

/**
 * Prompts the user for a single text value with optional inline validation.
 * Resolves to the trimmed string, or `null` if cancelled.
 */
export function promptModal({
  title,
  message = '',
  initialValue = '',
  placeholder = '',
  confirmText = 'Confirm',
  validate = null,
}) {
  return new Promise((resolve) => {
    const input = el('input', {
      type: 'text',
      value: initialValue,
      placeholder,
      style: 'width:100%',
    })
    const errEl = el('div', { class: 'modal-error', hidden: true })

    let cleanup = () => {}
    const submit = () => {
      const value = input.value.trim()
      if (validate) {
        const problem = validate(value)
        if (problem) {
          errEl.textContent = problem
          errEl.hidden = false
          input.focus()
          return
        }
      }
      cleanup()
      resolve(value)
    }

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); submit() }
    })
    input.addEventListener('input', () => { errEl.hidden = true })

    const dialog = el('div', { class: 'modal-card' },
      el('h3', {}, title),
      message ? el('p', { class: 'muted' }, message) : null,
      input,
      errEl,
      el('div', { class: 'modal-actions' },
        el('button', { class: 'small', onClick: () => { cleanup(); resolve(null) } }, 'Cancel'),
        el('button', { class: 'small primary', onClick: submit }, confirmText),
      ),
    )

    cleanup = mountBackdrop(dialog, () => resolve(null))
    queueMicrotask(() => {
      input.focus()
      input.select()
    })
  })
}

/**
 * Displays a confirmation dialog and resolves to `true` (confirmed) or `false` (cancelled).
 */
export function confirmModal({
  title,
  message,
  confirmText = 'Confirm',
  danger = false,
}) {
  return new Promise((resolve) => {
    let cleanup = () => {}
    const confirmBtn = el('button', {
      class: `small ${danger ? 'danger-solid' : 'primary'}`,
      onClick: () => { cleanup(); resolve(true) },
    }, confirmText)

    const dialog = el('div', { class: 'modal-card' },
      el('h3', {}, title),
      el('p', { class: 'muted' }, message),
      el('div', { class: 'modal-actions' },
        el('button', { class: 'small', onClick: () => { cleanup(); resolve(false) } }, 'Cancel'),
        confirmBtn,
      ),
    )

    cleanup = mountBackdrop(dialog, () => resolve(false))
    queueMicrotask(() => confirmBtn.focus())
  })
}
