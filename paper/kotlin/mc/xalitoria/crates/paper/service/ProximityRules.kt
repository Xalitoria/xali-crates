package mc.xalitoria.crates.paper.service

/** The pure rules of [ProximityHider]: which players share a crate's hide radius. No server needed. */
object ProximityRules {
    /** A placed crate that hides players; [key] tells it from the others. */
    data class Spot(val key: String, val world: String, val x: Double, val y: Double, val z: Double, val radius: Double)

    /** A radius of zero or less (or one that is not a number) turns hiding off. */
    fun active(radius: Double): Boolean = radius > 0.0 && radius.isFinite()

    /** The keys of the [spots] a player at the given place stands within the radius of (same world, straight-line distance). */
    fun within(spots: List<Spot>, world: String, x: Double, y: Double, z: Double): Set<String> {
        var found: MutableSet<String>? = null
        for (spot in spots) {
            if (spot.world != world || !active(spot.radius)) continue
            val dx = x - spot.x
            val dy = y - spot.y
            val dz = z - spot.z
            if (dx * dx + dy * dy + dz * dz <= spot.radius * spot.radius) {
                (found ?: HashSet<String>().also { found = it }).add(spot.key)
            }
        }
        return found ?: emptySet()
    }

    /** Two players hide from each other when they stand within the radius of one same crate. */
    fun hidesFrom(mine: Set<String>, theirs: Set<String>): Boolean {
        if (mine.isEmpty() || theirs.isEmpty()) return false
        val [small, large] = if (mine.size <= theirs.size) mine to theirs else theirs to mine
        return small.any { it in large }
    }
}
