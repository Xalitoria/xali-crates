package mc.xalitoria.crates.paper.service

import com.destroystokyo.paper.profile.ProfileProperty
import com.xalitoria.util.message.toComponent
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.model.AttributeSpec
import mc.xalitoria.crates.paper.data.model.BannerPatternSpec
import mc.xalitoria.crates.paper.data.model.CrateItem
import mc.xalitoria.crates.paper.data.model.FireworkConfig
import mc.xalitoria.crates.paper.data.model.PotionEffectSpec
import mc.xalitoria.crates.paper.external.CustomItemHook
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.DyeColor
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.ShulkerBox
import org.bukkit.block.banner.Pattern
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.FireworkEffectMeta
import org.bukkit.inventory.meta.FireworkMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import java.util.UUID

/**
 * Builds Bukkit [ItemStack]s from [CrateItem] definitions and converts existing stacks back into [CrateItem]
 * (for drag-and-drop editing in the in-game crate editor). Tags crate and key items with PDC markers.
 */
class ItemFactory(private val plugin: XaliCrates) {
    val crateTagKey = NamespacedKey(plugin, "crate")
    val keyTagKey = NamespacedKey(plugin, "key")
    val rewardTagKey = NamespacedKey(plugin, "reward")

    fun build(
        spec: CrateItem,
        amountOverride: Int? = null,
        placeholders: Map<String, String> = emptyMap(),
        keepName: Boolean = true,
        keepLore: Boolean = true,
    ): ItemStack {
        val stack = resolveBaseStack(spec.material).clone()
        stack.amount = (amountOverride ?: spec.amount).coerceIn(1, 99)

        stack.editMeta { meta ->
            if (keepName && spec.name != null) {
                meta.customName(applyVars(spec.name, placeholders).toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
            } else if (!keepName) {
                meta.customName(null)
            }
            if (keepLore && spec.lore.isNotEmpty()) {
                meta.lore(spec.lore.map { applyVars(it, placeholders).toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
            } else if (!keepLore) {
                meta.lore(null)
            }
            if (spec.unbreakable) meta.isUnbreakable = true
            if (spec.glow) meta.setEnchantmentGlintOverride(true)
            if (spec.damage > 0 && meta is Damageable) meta.damage = spec.damage
            if (spec.customModelData > 0) meta.setCustomModelData(spec.customModelData)
            if (!spec.itemModel.isNullOrBlank()) {
                NamespacedKey.fromString(spec.itemModel)?.let { meta.itemModel = it }
            }

            if (spec.enchantments.isNotEmpty()) {
                val enchRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                for ([name, level] in spec.enchantments) {
                    val key = NamespacedKey.minecraft(normalizeKey(name))
                    val ench = enchRegistry.get(key) ?: continue
                    meta.addEnchant(ench, level, true)
                }
            }

            for (flagName in spec.flags) {
                runCatching { ItemFlag.valueOf(flagName.uppercase()) }.getOrNull()?.let { meta.addItemFlags(it) }
            }

            if (spec.color != null) {
                parseColor(spec.color)?.let { color ->
                    when (meta) {
                        is LeatherArmorMeta -> meta.setColor(color)
                        is PotionMeta -> meta.color = color
                    }
                }
            }

            if (spec.potionEffects.isNotEmpty() && meta is PotionMeta) {
                val potRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                for (eff in spec.potionEffects) {
                    val type = potRegistry.get(NamespacedKey.minecraft(normalizeKey(eff.effect))) ?: continue
                    meta.addCustomEffect(PotionEffect(type, eff.duration, eff.amplifier), true)
                }
            }

            if (spec.patterns.isNotEmpty() && meta is BannerMeta) {
                val patRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN)
                for (pat in spec.patterns) {
                    val patternType = patRegistry.get(NamespacedKey.minecraft(normalizeKey(pat.pattern))) ?: continue
                    val dye = runCatching { DyeColor.valueOf(pat.color.uppercase()) }.getOrDefault(DyeColor.WHITE)
                    meta.addPattern(Pattern(dye, patternType))
                }
            }

            if (spec.firework != null) {
                val effect = buildFireworkEffect(spec.firework)
                when (meta) {
                    is FireworkMeta -> {
                        meta.power = spec.firework.power.coerceIn(0, 4)
                        meta.addEffect(effect)
                    }
                    is FireworkEffectMeta -> meta.effect = effect
                }
            }

            if (spec.attributes.isNotEmpty()) {
                val attrRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)
                for ([idx, attrSpec] in spec.attributes.withIndex()) {
                    val attr = attrRegistry.get(NamespacedKey.minecraft(normalizeKey(attrSpec.attribute))) ?: continue
                    val op = runCatching { AttributeModifier.Operation.valueOf(attrSpec.operation.uppercase()) }
                        .getOrDefault(AttributeModifier.Operation.ADD_NUMBER)
                    val slot = attrSpec.slot?.let { parseSlotGroup(it) } ?: EquipmentSlotGroup.ANY
                    val mod = AttributeModifier(NamespacedKey(plugin, "attr_${idx}_${attrSpec.attribute.lowercase()}"), attrSpec.amount, op, slot)
                    meta.addAttributeModifier(attr, mod)
                }
            }
        }
        return stack
    }

    fun buildCrateItem(crateId: String, amount: Int = 1): ItemStack? {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return null
        val stack = build(crate.crateItem, amountOverride = amount)
        stack.editMeta { it.persistentDataContainer.set(crateTagKey, PersistentDataType.STRING, id) }
        return stack
    }

    fun buildKeyItem(crateId: String, amount: Int = 1): ItemStack? {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return null
        val stack = build(crate.key.item, amountOverride = amount)
        stack.editMeta { it.persistentDataContainer.set(keyTagKey, PersistentDataType.STRING, id) }
        return stack
    }

    fun buildRewardPreviewItem(crateId: String, rewardId: String): ItemStack? {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return null
        val reward = crate.rewards[rewardId] ?: return null
        val pct = RewardRoller.formatPercent(RewardRoller.probabilityPercent(crate, rewardId))
        val vars = mapOf(
            "%rewardname%" to rewardId,
            "%displayname%" to (reward.displayItem.name ?: rewardId),
            "%chance%" to pct,
            "%writtenchance%" to reward.weight.toString(),
            "%rarity%" to (crate.tiers[reward.rarity]?.displayName ?: reward.rarity),
        )
        val stack = build(reward.displayItem, placeholders = vars)
        stack.editMeta { it.persistentDataContainer.set(rewardTagKey, PersistentDataType.STRING, rewardId) }
        return stack
    }

    fun buildRewardGrantItem(crateId: String, rewardId: String): ItemStack? {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return null
        val reward = crate.rewards[rewardId] ?: return null
        val stack = build(
            reward.displayItem,
            keepName = reward.giveItem.keepName,
            keepLore = reward.giveItem.keepLore,
        )
        if (reward.shulkerContents.isNotEmpty() && stack.itemMeta is BlockStateMeta) {
            val meta = stack.itemMeta as BlockStateMeta
            val state = meta.blockState
            if (state is ShulkerBox) {
                for (childId in reward.shulkerContents) {
                    val child = crate.rewards[childId] ?: continue
                    state.inventory.addItem(build(child.displayItem, keepName = child.giveItem.keepName, keepLore = child.giveItem.keepLore))
                }
                meta.blockState = state
                stack.itemMeta = meta
            }
        }
        return stack
    }

    fun crateIdOf(stack: ItemStack?): String? {
        if (stack == null || stack.type.isAir) return null
        return stack.itemMeta?.persistentDataContainer?.get(crateTagKey, PersistentDataType.STRING)
    }

    fun keyCrateIdOf(stack: ItemStack?): String? {
        if (stack == null || stack.type.isAir) return null
        val tagged = stack.itemMeta?.persistentDataContainer?.get(keyTagKey, PersistentDataType.STRING)
        if (tagged != null) return tagged
        if (plugin.config.data.keys.requirePdcTag) return null
        // Legacy fallback: match by material + display name (+ optional lore/enchants)
        for ([id, crate] in plugin.crates) {
            if (crate.isMultiCrate || !crate.enabled) continue
            if (matchesLegacyKey(stack, id)) return id
        }
        return null
    }

    private fun matchesLegacyKey(stack: ItemStack, crateId: String): Boolean {
        val expected = buildKeyItem(crateId, 1) ?: return false
        if (stack.type != expected.type) return false
        val actualMeta = stack.itemMeta ?: return false
        val expectedMeta = expected.itemMeta ?: return false
        if (actualMeta.customName() != expectedMeta.customName()) return false
        if (plugin.config.data.keys.requireLore && actualMeta.lore() != expectedMeta.lore()) return false
        if (plugin.config.data.keys.requireEnchants && actualMeta.enchants != expectedMeta.enchants) return false
        return true
    }

    /** Captures a live Bukkit [ItemStack] into a [CrateItem] config object (used by the in-game admin editor). */
    fun fromItemStack(stack: ItemStack): CrateItem {
        val customId = CustomItemHook.identify(stack)
        val meta = stack.itemMeta
        val legacy = LegacyComponentSerializer.legacyAmpersand()
        val name = meta?.customName()?.let { legacy.serialize(it) }
        val lore = meta?.lore()?.map { legacy.serialize(it) } ?: []
        val enchants = meta?.enchants?.entries?.associate { it.key.key.key to it.value } ?: emptyMap()
        val flags = meta?.itemFlags?.map { it.name } ?: []
        val damage = (meta as? Damageable)?.damage ?: 0
        val cmd = if (meta != null && meta.hasCustomModelData()) meta.customModelData else 0
        val model = meta?.itemModel?.asString()
        val color = when (meta) {
            is LeatherArmorMeta -> String.format("#%02X%02X%02X", meta.color.red, meta.color.green, meta.color.blue)
            is PotionMeta -> meta.color?.let { String.format("#%02X%02X%02X", it.red, it.green, it.blue) }
            else -> null
        }
        val potions = (meta as? PotionMeta)?.customEffects?.map {
            PotionEffectSpec(it.type.key.key, it.duration, it.amplifier)
        } ?: []
        val patterns = (meta as? BannerMeta)?.patterns?.map {
            BannerPatternSpec(it.pattern.key.key, it.color.name)
        } ?: []
        return CrateItem(
            material = customId ?: stack.type.key.key,
            amount = stack.amount.coerceIn(1, 99),
            name = name,
            lore = lore,
            glow = meta?.hasEnchantmentGlintOverride() == true && meta.enchantmentGlintOverride,
            damage = damage,
            customModelData = cmd,
            itemModel = model,
            unbreakable = meta?.isUnbreakable == true,
            enchantments = enchants,
            flags = flags,
            color = color,
            potionEffects = potions,
            patterns = patterns,
        )
    }

    private fun resolveBaseStack(spec: String): ItemStack {
        CustomItemHook.resolve(spec)?.let { return it }
        val lower = spec.lowercase()
        if (lower.startsWith("head:")) {
            val owner = spec.substringAfter(':')
            return ItemStack(Material.PLAYER_HEAD).apply {
                editMeta(SkullMeta::class.java) { meta ->
                    meta.owningPlayer = Bukkit.getOfflinePlayer(owner)
                }
            }
        }
        if (lower.startsWith("texture:")) {
            val base64 = spec.substringAfter(':')
            return ItemStack(Material.PLAYER_HEAD).apply {
                editMeta(SkullMeta::class.java) { meta ->
                    val profile = Bukkit.createProfile(UUID.randomUUID())
                    profile.setProperty(ProfileProperty("textures", base64))
                    meta.playerProfile = profile
                }
            }
        }
        val cleaned = spec.substringBefore(';').trim()
        val mat = Material.matchMaterial(cleaned) ?: Material.matchMaterial(cleaned.removePrefix("minecraft:")) ?: Material.CHEST
        return ItemStack(if (mat.isItem) mat else Material.CHEST)
    }

    private fun applyVars(text: String, vars: Map<String, String>): String {
        var out = text
        for ([k, v] in vars) out = out.replace(k, v)
        return out
    }

    companion object {
        fun normalizeKey(raw: String): String =
            raw.trim().lowercase().removePrefix("minecraft:").replace(' ', '_')

        fun parseColor(text: String): Color? {
            val clean = text.trim()
            if (clean.startsWith("#") && clean.length == 7) {
                val rgb = clean.substring(1).toIntOrNull(16) ?: return null
                return Color.fromRGB(rgb)
            }
            if (clean.length == 6 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return Color.fromRGB(clean.toInt(16))
            }
            return when (clean.uppercase()) {
                "WHITE" -> Color.WHITE
                "SILVER" -> Color.SILVER
                "GRAY", "GREY" -> Color.GRAY
                "BLACK" -> Color.BLACK
                "RED" -> Color.RED
                "MAROON" -> Color.MAROON
                "YELLOW" -> Color.YELLOW
                "OLIVE" -> Color.OLIVE
                "LIME" -> Color.LIME
                "GREEN" -> Color.GREEN
                "AQUA", "CYAN" -> Color.AQUA
                "TEAL" -> Color.TEAL
                "BLUE" -> Color.BLUE
                "NAVY" -> Color.NAVY
                "FUCHSIA", "MAGENTA" -> Color.FUCHSIA
                "PURPLE" -> Color.PURPLE
                "ORANGE" -> Color.ORANGE
                else -> runCatching { DyeColor.valueOf(clean.uppercase()).color }.getOrNull()
            }
        }

        fun buildFireworkEffect(cfg: FireworkConfig): FireworkEffect {
            val type = runCatching { FireworkEffect.Type.valueOf(cfg.shape.uppercase()) }.getOrDefault(FireworkEffect.Type.STAR)
            val colors = cfg.colors.mapNotNull(::parseColor).ifEmpty { listOf(Color.WHITE) }
            val fades = cfg.fadeColors.mapNotNull(::parseColor)
            return FireworkEffect.builder()
                .with(type)
                .withColor(colors)
                .withFade(fades)
                .flicker(cfg.flicker)
                .trail(cfg.trail)
                .build()
        }

        private fun parseSlotGroup(name: String): EquipmentSlotGroup = when (name.uppercase()) {
            "HAND", "MAINHAND" -> EquipmentSlotGroup.MAINHAND
            "OFF_HAND", "OFFHAND" -> EquipmentSlotGroup.OFFHAND
            "HEAD" -> EquipmentSlotGroup.HEAD
            "CHEST" -> EquipmentSlotGroup.CHEST
            "LEGS" -> EquipmentSlotGroup.LEGS
            "FEET" -> EquipmentSlotGroup.FEET
            "ARMOR" -> EquipmentSlotGroup.ARMOR
            else -> EquipmentSlotGroup.ANY
        }
    }
}
