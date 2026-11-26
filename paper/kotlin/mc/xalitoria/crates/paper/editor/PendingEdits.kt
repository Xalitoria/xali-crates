package mc.xalitoria.crates.paper.editor

import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.CrateConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds edits submitted via a shared web editor link (`/xcrates share`) or an unverified IP until an admin
 * inspects and applies or drops them with `/xcrates edits`. Crates can execute console commands on reward win,
 * so untrusted edits record client IP, hold reason, and every embedded console command for admin review.
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
        val clientIp: String? = null,
        val reason: String = "shared link",
        val privileged: List<String> = [],
    )

    private val counter = AtomicInteger()
    private val edits = LinkedHashMap<Int, Edit>()

    @Synchronized
    fun addCrate(
        from: String,
        name: String,
        crate: CrateConfig,
        problems: List<String>,
        clientIp: String? = null,
        reason: String = "shared link",
        privileged: List<String> = [],
        now: Long = System.currentTimeMillis(),
    ): Edit {
        edits.values.removeIf { it.kind == Kind.CRATE && it.from == from && it.name == name }
        val edit = Edit(
            id = counter.incrementAndGet(),
            kind = Kind.CRATE,
            from = from,
            name = name,
            crate = crate,
            problems = problems,
            at = now,
            clientIp = clientIp,
            reason = reason,
            privileged = privileged,
        )
        edits[edit.id] = edit
        while (edits.size > limit) edits.remove(edits.keys.first())
        return edit
    }

    @Synchronized
    fun addAnimation(
        from: String,
        name: String,
        animation: AnimationConfig,
        problems: List<String>,
        clientIp: String? = null,
        reason: String = "shared link",
        now: Long = System.currentTimeMillis(),
    ): Edit {
        edits.values.removeIf { it.kind == Kind.ANIMATION && it.from == from && it.name == name }
        val edit = Edit(
            id = counter.incrementAndGet(),
            kind = Kind.ANIMATION,
            from = from,
            name = name,
            animation = animation,
            problems = problems,
            at = now,
            clientIp = clientIp,
            reason = reason,
        )
        edits[edit.id] = edit
        while (edits.size > limit) edits.remove(edits.keys.first())
        return edit
    }

    @Synchronized
    fun list(): List<Edit> = edits.values.toList()

    @Synchronized
    fun peek(id: Int): Edit? = edits[id]

    @Synchronized
    fun take(id: Int): Edit? = edits.remove(id)

    @Synchronized
    fun clear() = edits.clear()
}
