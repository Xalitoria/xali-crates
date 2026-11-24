package mc.xalitoria.crates.paper.editor

import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.CrateConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds edits submitted via a shared web editor link (`/xcrates share`) until an admin reviews and applies or drops
 * them with `/xcrates edits`. Crates can execute console commands on reward win, so untrusted shared edits are never
 * written automatically.
 */
internal class PendingEdits(private val limit: Int = 50) {
    enum class Kind { CRATE, ANIMATION }

    data class Edit(
        val id: Int,
        val kind: Kind,
        val from: String,
        val name: String,
        val crate: CrateConfig? = null,
        val animation: AnimationConfig? = null,
        val problems: List<String>,
        val at: Long,
    )

    private val counter = AtomicInteger()
    private val edits = LinkedHashMap<Int, Edit>()

    @Synchronized
    fun addCrate(from: String, name: String, crate: CrateConfig, problems: List<String>, now: Long = System.currentTimeMillis()): Edit {
        edits.values.removeIf { it.kind == Kind.CRATE && it.from == from && it.name == name }
        val edit = Edit(counter.incrementAndGet(), Kind.CRATE, from, name, crate = crate, problems = problems, at = now)
        edits[edit.id] = edit
        while (edits.size > limit) edits.remove(edits.keys.first())
        return edit
    }

    @Synchronized
    fun addAnimation(from: String, name: String, animation: AnimationConfig, problems: List<String>, now: Long = System.currentTimeMillis()): Edit {
        edits.values.removeIf { it.kind == Kind.ANIMATION && it.from == from && it.name == name }
        val edit = Edit(counter.incrementAndGet(), Kind.ANIMATION, from, name, animation = animation, problems = problems, at = now)
        edits[edit.id] = edit
        while (edits.size > limit) edits.remove(edits.keys.first())
        return edit
    }

    @Synchronized
    fun list(): List<Edit> = edits.values.toList()

    @Synchronized
    fun take(id: Int): Edit? = edits.remove(id)

    @Synchronized
    fun clear() = edits.clear()
}
