// Reactive document store holding crates, custom animations, active selection, dirty flags, and undo/redo history.

export class EditorStore {
  constructor() {
    this.crates = {}
    this.animations = {}
    this.dirtyCrates = new Set()
    this.dirtyAnimations = new Set()
    this.active = { kind: 'crate', id: null }
    this.undoStack = []
    this.redoStack = []
    this.listeners = new Set()
  }

  subscribe(fn) {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  emit() {
    for (const fn of this.listeners) fn(this)
  }

  snapshot() {
    return {
      crates: structuredClone(this.crates),
      animations: structuredClone(this.animations),
      active: { ...this.active },
    }
  }

  pushUndo() {
    this.undoStack.push(this.snapshot())
    if (this.undoStack.length > 80) this.undoStack.shift()
    this.redoStack.length = 0
  }

  canUndo() { return this.undoStack.length > 0 }
  canRedo() { return this.redoStack.length > 0 }

  undo() {
    if (!this.canUndo()) return false
    this.redoStack.push(this.snapshot())
    const prev = this.undoStack.pop()
    this.crates = prev.crates
    this.animations = prev.animations
    this.active = prev.active
    this.markActiveDirty()
    this.emit()
    return true
  }

  redo() {
    if (!this.canRedo()) return false
    this.undoStack.push(this.snapshot())
    const next = this.redoStack.pop()
    this.crates = next.crates
    this.animations = next.animations
    this.active = next.active
    this.markActiveDirty()
    this.emit()
    return true
  }

  markActiveDirty() {
    if (!this.active.id) return
    if (this.active.kind === 'crate') this.dirtyCrates.add(this.active.id)
    else this.dirtyAnimations.add(this.active.id)
  }

  setCrate(id, data, { markDirty = true, recordUndo = true } = {}) {
    if (recordUndo) this.pushUndo()
    this.crates[id] = structuredClone(data)
    if (markDirty) this.dirtyCrates.add(id)
    else this.dirtyCrates.delete(id)
    this.emit()
  }

  setAnimation(id, data, { markDirty = true, recordUndo = true } = {}) {
    if (recordUndo) this.pushUndo()
    this.animations[id] = structuredClone(data)
    if (markDirty) this.dirtyAnimations.add(id)
    else this.dirtyAnimations.delete(id)
    this.emit()
  }

  deleteCrate(id) {
    this.pushUndo()
    delete this.crates[id]
    this.dirtyCrates.delete(id)
    if (this.active.kind === 'crate' && this.active.id === id) {
      this.active.id = Object.keys(this.crates)[0] ?? null
    }
    this.emit()
  }

  deleteAnimation(id) {
    this.pushUndo()
    delete this.animations[id]
    this.dirtyAnimations.delete(id)
    if (this.active.kind === 'animation' && this.active.id === id) {
      this.active.id = Object.keys(this.animations)[0] ?? null
    }
    this.emit()
  }

  select(kind, id) {
    this.active = { kind, id }
    this.emit()
  }

  activeDocument() {
    if (!this.active.id) return null
    return this.active.kind === 'crate'
      ? this.crates[this.active.id] ?? null
      : this.animations[this.active.id] ?? null
  }

  mutateActive(mutator) {
    const { kind, id } = this.active
    if (!id) return
    const current = this.activeDocument()
    if (!current) return
    this.pushUndo()
    const copy = structuredClone(current)
    mutator(copy)
    if (kind === 'crate') {
      this.crates[id] = copy
      this.dirtyCrates.add(id)
    } else {
      this.animations[id] = copy
      this.dirtyAnimations.add(id)
    }
    this.emit()
  }

  isDirty(kind, id) {
    return kind === 'crate' ? this.dirtyCrates.has(id) : this.dirtyAnimations.has(id)
  }

  clearDirty(kind, id) {
    if (kind === 'crate') this.dirtyCrates.delete(id)
    else this.dirtyAnimations.delete(id)
    this.emit()
  }
}
