package mc.xalitoria.crates.paper.service

import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.RewardConfig
import java.math.BigDecimal
import java.text.DecimalFormat
import kotlin.random.Random

/** One resolved reward paired with its id in the crate. */
data class NamedReward(val id: String, val config: RewardConfig)

/**
 * Pure reward rolling, probability calculation, repeat-prevention, fallback resolution, and `%amountX-Y%` expansion.
 * Platform-free so unit tests verify every rule directly without a server.
 */
object RewardRoller {
    private val AMOUNT_RANGE = Regex("""%amount(\d+)-(\d+)%""")

    /** All rewards eligible for random rolling (excludes `fallbackOnly` and `guaranteed`). */
    fun rollable(crate: CrateConfig): List<NamedReward> =
        crate.rewards.entries
            .filter { !it.value.fallbackOnly && !it.value.guaranteed && it.value.weight > 0.0 }
            .map { NamedReward(it.key, it.value) }

    /** All guaranteed rewards that are always granted alongside rolled rewards. */
    fun guaranteed(crate: CrateConfig): List<NamedReward> =
        crate.rewards.entries
            .filter { it.value.guaranteed && !it.value.fallbackOnly }
            .map { NamedReward(it.key, it.value) }

    /** Sum of weights of all rollable rewards in [crate]. */
    fun totalWeight(crate: CrateConfig): Double = rollable(crate).sumOf { it.config.weight }

    /** Exact percentage probability (0.0 to 100.0) of rolling [rewardId] in [crate]. */
    fun probabilityPercent(crate: CrateConfig, rewardId: String): Double {
        val reward = crate.rewards[rewardId] ?: return 0.0
        if (reward.guaranteed) return 100.0
        if (reward.fallbackOnly || reward.weight <= 0.0) return 0.0
        val total = totalWeight(crate)
        return if (total <= 0.0) 0.0 else (reward.weight / total) * 100.0
    }

    /** Formats a percentage with up to two decimals (or enough significant digits for sub-1% probabilities). */
    fun formatPercent(percent: Double): String {
        if (percent <= 0.0) return "0"
        if (percent >= 1.0) return DecimalFormat("#.##").format(percent)
        val plain = BigDecimal.valueOf(percent).toPlainString()
        val dot = plain.indexOf('.')
        if (dot < 0) return plain
        var pattern = "#."
        for (i in (dot + 1) until plain.length) {
            pattern += "#"
            if (plain[i] != '0') break
        }
        return DecimalFormat("$pattern#").format(percent)
    }

    /**
     * Rolls one reward from [crate], respecting `repeat.chance` and `repeat.max` relative to [previous] rewards
     * already selected in the same opening.
     */
    fun roll(crate: CrateConfig, previous: List<NamedReward> = [], random: Random = Random.Default): NamedReward? {
        val pool = rollable(crate)
        if (pool.isEmpty()) return null
        val counts = previous.groupingBy { it.id }.eachCount()
        val eligible = pool.filter { candidate ->
            val seen = counts[candidate.id] ?: 0
            if (seen == 0) true
            else {
                val repeat = candidate.config.repeat
                if (repeat.max <= 0 || seen >= repeat.max || repeat.chance <= 0.0) false
                else repeat.chance >= 100.0 || random.nextDouble(0.0, 100.0) < repeat.chance
            }
        }.ifEmpty { pool }

        val sum = eligible.sumOf { it.config.weight }
        if (sum <= 0.0) return eligible.firstOrNull()
        val target = random.nextDouble(0.0, sum)
        var acc = 0.0
        for (item in eligible) {
            acc += item.config.weight
            if (target <= acc) return item
        }
        return eligible.last()
    }

    /**
     * Resolves permission-based fallback replacement. [hasPermission] tests whether the player holds a permission node.
     * Guards against cycles by tracking visited reward ids.
     */
    fun resolveFallback(
        crate: CrateConfig,
        won: NamedReward,
        hasPermission: (String) -> Boolean,
    ): Pair<NamedReward, Boolean> {
        var current = won
        var replaced = false
        val visited = HashSet<String>()
        while (visited.add(current.id)) {
            val fb = current.config.fallback ?: break
            if (fb.bypassPermission.isNotBlank() && hasPermission(fb.bypassPermission)) break
            if (!hasPermission(fb.permission)) break
            val next = crate.rewards[fb.reward] ?: break
            current = NamedReward(fb.reward, next)
            replaced = true
        }
        return current to replaced
    }

    /** Expands `%amountX-Y%` tokens in a command into random integers within `[min(X,Y), max(X,Y)]` inclusive. */
    fun expandRandomAmounts(text: String, random: Random = Random.Default): String =
        AMOUNT_RANGE.replace(text) { match ->
            val a = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val b = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val low = minOf(a, b)
            val high = maxOf(a, b)
            val picked = if (low == high) low else random.nextInt(low, high + 1)
            picked.toString()
        }
}
