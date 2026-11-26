package mc.xalitoria.crates.paper.external

import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.service.RewardRoller
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * PlaceholderAPI expansion `%xcrates_...%` (matching and extending all SpecializedCrates placeholders).
 *
 * - `%xcrates_placed%` — total placed crates
 * - `%xcrates_virtual_keys_total%` — player's total virtual keys across all crates
 * - `%xcrates_virtual_keys_<crate>%` — player's virtual keys for `<crate>`
 * - `%xcrates_virtual_crates_<crate>%` — player's virtual crates for `<crate>`
 * - `%xcrates_cooldown_<crate>%` — formatted remaining cooldown or `-`
 * - `%xcrates_last_player_<crate>%` — last player to open `<crate>`
 * - `%xcrates_last_reward_<crate>%` — last reward id won from `<crate>`
 * - `%xcrates_last_reward_name_<crate>%` — last reward display name won from `<crate>`
 * - `%xcrates_last_crate_opened%` — player's most recently opened crate
 * - `%xcrates_last_crate_opened_rewards%` — rewards from player's most recent opening
 * - `%xcrates_crate_name_<crate>%` — formatted crate display name (for XaliMenu titles/items)
 * - `%xcrates_reward_name_<crate>_<reward>%` — formatted reward display name
 * - `%xcrates_reward_chance_<crate>_<reward>%` — formatted reward chance percent
 */
class CratesExpansion(private val plugin: XaliCrates) : PlaceholderExpansion() {
    override fun getIdentifier() = "xcrates"
    override fun getAuthor() = "audizian"
    override fun getVersion() = plugin.pluginMeta.version
    override fun persist() = true

    override fun onRequest(offline: OfflinePlayer?, params: String): String? {
        val lower = params.lowercase()
        val player = offline?.player
        return when {
            lower == "placed" || lower == "placedcrates" -> plugin.placed.count().toString()
            lower == "virtual_keys_total" || lower == "total_virtual_keys" ->
                if (offline == null) "0" else plugin.keys.totalVirtualKeys(offline.uniqueId).toString()
            lower.startsWith("virtual_keys_") -> {
                val id = lower.removePrefix("virtual_keys_")
                if (offline == null) "0" else plugin.keys.cachedBalance(offline.uniqueId, id).keys.toString()
            }
            lower.startsWith("virtual_crates_") -> {
                val id = lower.removePrefix("virtual_crates_")
                if (offline == null) "0" else plugin.keys.cachedBalance(offline.uniqueId, id).crates.toString()
            }
            lower.startsWith("cooldown_") -> {
                val id = lower.removePrefix("cooldown_")
                if (player == null) "-" else {
                    val sec = plugin.openService.remainingCooldownSeconds(player, id, null)
                    if (sec <= 0L) "-" else plugin.messageConfig.data.words.duration(sec)
                }
            }
            lower == "last_crate_opened" ->
                offline?.let { plugin.openService.cachedHistory(it.uniqueId).firstOrNull()?.crateId } ?: "None"
            lower == "last_crate_opened_rewards" ->
                offline?.let { plugin.openService.cachedHistory(it.uniqueId).firstOrNull()?.rewards?.joinToString(", ") } ?: "None"
            lower.startsWith("last_player_") -> {
                val id = lower.removePrefix("last_player_")
                plugin.openService.lastOpenedBy(id) ?: "Nobody"
            }
            lower.startsWith("last_reward_name_") -> {
                val id = lower.removePrefix("last_reward_name_")
                plugin.openService.lastRewardName(id) ?: "Nothing"
            }
            lower.startsWith("last_reward_") -> {
                val id = lower.removePrefix("last_reward_")
                plugin.openService.lastRewardId(id) ?: "Nothing"
            }
            lower.startsWith("crate_name_") -> {
                val id = lower.removePrefix("crate_name_")
                val crate = plugin.crates[id] ?: return ""
                plugin.displayNameOf(id, crate)
            }
            lower.startsWith("reward_name_") -> {
                val [crateId, rewardId] = splitCrateReward(params.substring("reward_name_".length)) ?: return ""
                val reward = plugin.crates[crateId]?.rewards?.get(rewardId) ?: return ""
                reward.displayItem.name ?: rewardId
            }
            lower.startsWith("reward_chance_") -> {
                val [crateId, rewardId] = splitCrateReward(params.substring("reward_chance_".length)) ?: return ""
                val crate = plugin.crates[crateId] ?: return ""
                RewardRoller.formatPercent(RewardRoller.probabilityPercent(crate, rewardId))
            }
            else -> null
        }
    }

    private fun splitCrateReward(rest: String): Pair<String, String>? {
        val exactCrate = plugin.crates.keys.sortedByDescending { it.length }.firstOrNull { rest.lowercase().startsWith("${it}_") }
        if (exactCrate != null) return exactCrate to rest.substring(exactCrate.length + 1)
        val idx = rest.indexOf('_')
        if (idx <= 0) return null
        return rest.substring(0, idx).lowercase() to rest.substring(idx + 1)
    }

    companion object {
        fun parse(player: Player, text: String): String = PlaceholderAPI.setPlaceholders(player, text)
    }
}
