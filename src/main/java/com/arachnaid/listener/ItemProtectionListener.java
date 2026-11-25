package com.arachnaid.listener;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

public final class ItemProtectionListener implements Listener {

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;

    public ItemProtectionListener(ArachnaidPlugin plugin, ArachnaidItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (itemFactory.isArachnaidAxe(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (itemFactory.isArachnaidAxe(event.getItem())) {
            event.setCancelled(true);
            event.getEnchanter().sendActionBar(plugin.rawMsg("enchant-blocked"));
        }
    }

    /**
     * Blocks grinding Sharpness VI off the Arachnaid Axe.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        GrindstoneInventory inv = event.getInventory();
        if (itemFactory.isArachnaidAxe(inv.getUpperItem()) || itemFactory.isArachnaidAxe(inv.getLowerItem())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstoneResultClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof GrindstoneInventory inv)) {
            return;
        }
        if (event.getRawSlot() == 2
                && (itemFactory.isArachnaidAxe(inv.getUpperItem()) || itemFactory.isArachnaidAxe(inv.getLowerItem()))) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendActionBar(plugin.rawMsg("enchant-blocked"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        for (ItemStack item : event.getInventory().getContents()) {
            if (itemFactory.isArachnaidAxe(item)) {
                event.setResult(null);
                return;
            }
        }
    }

    /**
     * Enforces TWO strict Mythic rules in Anvils:
     * 1. NO Mythic item (Arachnaid Axe, Dragon Egg, Cleric's Bag, etc.) can EVER be renamed.
     * 2. Arachnaid Axe cannot receive extra enchants; optional pure Netherite Ingot repair keeps
     *    the exact official Mythic name + Sharpness VI intact.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getFirstItem();
        ItemStack second = inv.getSecondItem();

        boolean firstMythic = itemFactory.isAnyMythicItem(first);
        boolean secondMythic = itemFactory.isAnyMythicItem(second);
        if (!firstMythic && !secondMythic) {
            return;
        }

        // Pure rename attempt (nothing in slot 2) -> always block for ALL mythic items
        if (second == null || second.getType().isAir()) {
            event.setResult(null);
            return;
        }

        // Handle Arachnaid Axe specifically
        if (itemFactory.isArachnaidAxe(first)) {
            boolean allowRepair = plugin.getConfig().getBoolean("weapon.allow-netherite-repair", true);
            if (allowRepair && second.getType() == Material.NETHERITE_INGOT) {
                ItemStack result = event.getResult();
                if (result == null) {
                    return;
                }
                // Check if player tried typing a different name while repairing -> lock official name
                int newDamage = 0;
                if (result.getItemMeta() instanceof Damageable dmg) {
                    newDamage = dmg.getDamage();
                }
                ItemStack cleanRepair = result.clone();
                itemFactory.applyOfficialMeta(cleanRepair, newDamage);
                event.setResult(cleanRepair);
                return;
            }
            // Any enchanted book, another axe, or non-ingot -> hard block
            event.setResult(null);
            return;
        }

        if (itemFactory.isArachnaidAxe(second)) {
            event.setResult(null);
            return;
        }

        // For any other Mythic item (Dragon Egg, Cleric's Bag, etc.), block renaming in anvil
        ItemStack result = event.getResult();
        if (result != null && first != null && first.hasItemMeta() && result.hasItemMeta()) {
            var origName = first.getItemMeta().displayName();
            var resName = result.getItemMeta().displayName();
            if (origName != null && !origName.equals(resName)) {
                event.setResult(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilResultClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory inv)) {
            return;
        }
        if (event.getRawSlot() != 2) {
            return;
        }

        ItemStack first = inv.getFirstItem();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        if (itemFactory.isArachnaidAxe(clicked)) {
            if (!itemFactory.hasOfficialName(clicked)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendActionBar(plugin.rawMsg("rename-blocked"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                }
                return;
            }
            if (!itemFactory.hasExactOfficialEnchants(clicked)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendActionBar(plugin.rawMsg("enchant-blocked"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                }
                return;
            }
        } else if (itemFactory.isAnyMythicItem(first) && first != null && first.hasItemMeta() && clicked.hasItemMeta()) {
            var origName = first.getItemMeta().displayName();
            var resName = clicked.getItemMeta().displayName();
            if (origName != null && !origName.equals(resName)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendActionBar(plugin.rawMsg("rename-blocked"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                }
            }
        }
    }

    /**
     * Subtle crimson mythic strike particles on melee hit with the Sharpness VI Arachnaid Axe.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMythicAxeHit(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("weapon.combat-particles", true)) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        ItemStack main = attacker.getInventory().getItemInMainHand();
        if (!itemFactory.isArachnaidAxe(main)) {
            return;
        }
        itemFactory.ensureUpToDate(main);

        Particle.DustOptions crimson = new Particle.DustOptions(Color.fromRGB(220, 22, 54), 1.15f);
        victim.getWorld().spawnParticle(
                Particle.DUST,
                victim.getLocation().add(0, victim.getHeight() * 0.55, 0),
                10,
                0.3, 0.35, 0.3,
                crimson
        );
    }
}
