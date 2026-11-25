package com.arachnaid.item;

import com.arachnaid.ArachnaidPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

public final class ArachnaidItemFactory {

    private final ArachnaidPlugin plugin;
    private final NamespacedKey axeKey;
    private final NamespacedKey mythicLockKey;

    public ArachnaidItemFactory(ArachnaidPlugin plugin) {
        this.plugin = plugin;
        this.axeKey = new NamespacedKey(plugin, "arachnaid_axe");
        this.mythicLockKey = new NamespacedKey(plugin, "mythic_item");
    }

    public int getConfiguredSharpnessLevel() {
        return Math.max(1, plugin.getConfig().getInt("weapon.sharpness-level", 6));
    }

    public Component getOfficialDisplayName() {
        String nameRaw = plugin.getConfig().getString(
                "weapon.name",
                "<gradient:#990018:#FF2A4D><bold>ARACHNAID AXE</bold></gradient>"
        );
        return plugin.parse(nameRaw)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public String getOfficialPlainName() {
        return PlainTextComponentSerializer.plainText().serialize(getOfficialDisplayName());
    }

    public ItemStack createArachnaidAxe() {
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE, 1);
        applyOfficialMeta(axe, 0);
        return axe;
    }

    /**
     * Re-applies the canonical Mythic metadata (official name, Sharpness VI, custom_model_data, lore, PDC)
     * while preserving durability damage (used for safe anvil repair & anti-tamper enforcement).
     */
    public void applyOfficialMeta(ItemStack axe, int damage) {
        if (axe == null || axe.getType() != Material.NETHERITE_AXE) {
            return;
        }
        ItemMeta meta = axe.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.displayName(getOfficialDisplayName());

        List<String> loreRaw = plugin.getConfig().getStringList("weapon.lore");
        if (!loreRaw.isEmpty()) {
            List<Component> lore = loreRaw.stream()
                    .map(line -> plugin.parse(line)
                            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                    .toList();
            meta.lore(lore);
        }

        // Strip any unauthorized enchantments and enforce exact Sharpness VI
        for (Enchantment existing : List.copyOf(meta.getEnchants().keySet())) {
            meta.removeEnchant(existing);
        }
        meta.addEnchant(Enchantment.SHARPNESS, getConfiguredSharpnessLevel(), true);

        String modelId = plugin.getConfig().getString("weapon.custom-model-data", "arachnaid_axe");
        if (modelId != null && !modelId.isBlank()) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setStrings(List.of(modelId));
            meta.setCustomModelDataComponent(cmd);
        }

        if (meta instanceof Damageable damageable) {
            damageable.setDamage(Math.max(0, damage));
        }

        meta.getPersistentDataContainer().set(axeKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(mythicLockKey, PersistentDataType.BYTE, (byte) 1);
        axe.setItemMeta(meta);
    }

    public boolean isArachnaidAxe(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_AXE || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(axeKey, PersistentDataType.BYTE);
    }

    /**
     * Detects any mythic item marked by ArachnaidAxe or other Mythic plugins (DragonEgg, ClericsBag, etc.)
     * so none of them can ever be renamed in an anvil.
     */
    public boolean isAnyMythicItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        if (isArachnaidAxe(item)) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            String full = key.asString();
            if (full.equals("arachnaidaxe:mythic_item")
                    || full.equals("arachnaidaxe:arachnaid_axe")
                    || full.equals("dragonegg:mythic_dragon_egg")
                    || full.equals("clericsbag:clerics_bag")) {
                return true;
            }
        }
        return item.getType() == Material.DRAGON_EGG;
    }

    public boolean hasExactOfficialEnchants(ItemStack item) {
        if (!isArachnaidAxe(item)) {
            return false;
        }
        Map<Enchantment, Integer> enchants = item.getEnchantments();
        return enchants.size() == 1
                && Integer.valueOf(getConfiguredSharpnessLevel()).equals(enchants.get(Enchantment.SHARPNESS));
    }

    public boolean hasOfficialName(ItemStack item) {
        if (!isArachnaidAxe(item) || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.displayName() == null) {
            return false;
        }
        String actualPlain = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        return getOfficialPlainName().equals(actualPlain);
    }

    /**
     * Auto-upgrades older Arachnaid Axes from v1.0/v1.1 (missing Sharpness VI or old lore) cleanly in-place.
     */
    public void ensureUpToDate(ItemStack item) {
        if (!isArachnaidAxe(item)) {
            return;
        }
        if (hasExactOfficialEnchants(item) && hasOfficialName(item)) {
            return;
        }
        int damage = 0;
        if (item.getItemMeta() instanceof Damageable dmg) {
            damage = dmg.getDamage();
        }
        applyOfficialMeta(item, damage);
    }
}
