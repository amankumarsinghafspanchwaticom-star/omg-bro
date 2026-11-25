package com.arachnaid.listener;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import com.arachnaid.manager.AxeStateManager;
import com.arachnaid.manager.ResourcePackManager;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Map;

public final class AxeLifecycleListener implements Listener {

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;
    private final AxeStateManager stateManager;
    private final ResourcePackManager resourcePackManager;

    public AxeLifecycleListener(
            ArachnaidPlugin plugin,
            ArachnaidItemFactory itemFactory,
            AxeStateManager stateManager,
            ResourcePackManager resourcePackManager
    ) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.stateManager = stateManager;
        this.resourcePackManager = resourcePackManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (!itemFactory.isArachnaidAxe(item.getItemStack())) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (itemFactory.isArachnaidAxe(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (itemFactory.isArachnaidAxe(event.getEntity().getItemStack())) {
            stateManager.markAxeLostAndUnlock("despawned", true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (!itemFactory.isArachnaidAxe(item.getItemStack())) {
            return;
        }
        EntityRemoveEvent.Cause cause = event.getCause();
        if (cause == EntityRemoveEvent.Cause.DESPAWN) {
            stateManager.markAxeLostAndUnlock("despawned", true);
        } else if (cause == EntityRemoveEvent.Cause.OUT_OF_WORLD) {
            stateManager.markAxeLostAndUnlock("fell into the void", true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAxeDurabilityBreak(PlayerItemBreakEvent event) {
        if (itemFactory.isArachnaidAxe(event.getBrokenItem())) {
            stateManager.markAxeLostAndUnlock("broke", true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupAxe(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (itemFactory.isArachnaidAxe(stack)) {
            itemFactory.ensureUpToDate(stack);
            event.getItem().setItemStack(stack);
            stateManager.updateWielder(player, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryTakeAxe(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (syncAndCheckAxe(player)) {
                stateManager.updateWielder(player, true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDragAxe(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (itemFactory.isArachnaidAxe(event.getOldCursor())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && syncAndCheckAxe(player)) {
                    stateManager.updateWielder(player, true);
                }
            });
        }
    }

    private boolean syncAndCheckAxe(Player player) {
        boolean has = false;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (itemFactory.isArachnaidAxe(item)) {
                itemFactory.ensureUpToDate(item);
                player.getInventory().setItem(i, item);
                has = true;
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (itemFactory.isArachnaidAxe(cursor)) {
            itemFactory.ensureUpToDate(cursor);
            player.setItemOnCursor(cursor);
            has = true;
        }
        return has;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        stateManager.syncPlayerRecipeBook(player);
        // Delay pack push by 15 ticks so client finishes login cleanly
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                resourcePackManager.sendPack(player);
            }
        }, 15L);

        if (syncAndCheckAxe(player)) {
            stateManager.updateWielder(player, true);
        }
    }

    /**
     * Gives instant diagnostic feedback if a shared host blocks the built-in pack HTTP port.
     */
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
                || status == PlayerResourcePackStatusEvent.Status.INVALID_URL) {
            plugin.getLogger().warning("Resource pack failed for " + player.getName() + " (" + status
                    + ") URL=" + resourcePackManager.getActivePackUrl());
            if (player.hasPermission("arachnaid.admin") && !resourcePackManager.isUsingCustomUrl()) {
                player.sendMessage(plugin.parse(
                        "<dark_red>[Arachnaid]</dark_red> <red>Texture pack download failed (your host blocked port "
                                + plugin.getConfig().getInt("resource-pack.port", 25580) + ").</red> "
                                + "<yellow>Fix: Upload <white>plugins/ArachnaidAxe/ArachnaidAxe-ResourcePack.zip</white> "
                                + "to <white>mc-packs.net</white>, paste the URL into <white>resource-pack.custom-url</white> in config.yml, and run <white>/arachnaid reload</white>.</yellow>"
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed) || !keyed.getKey().equals(stateManager.getRecipeKey())) {
            return;
        }
        if (stateManager.isAxeActive()) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftAxe(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed) || !keyed.getKey().equals(stateManager.getRecipeKey())) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        if (stateManager.isAxeActive()) {
            event.setCancelled(true);
            player.sendMessage(plugin.msg("craft-locked"));
            return;
        }

        if (event.getAction() == InventoryAction.NOTHING) {
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack[] matrix = event.getInventory().getMatrix();
            for (int i = 0; i < matrix.length; i++) {
                ItemStack slot = matrix[i];
                if (slot != null && !slot.getType().isAir()) {
                    if (slot.getAmount() > 1) {
                        slot.setAmount(slot.getAmount() - 1);
                    } else {
                        matrix[i] = null;
                    }
                }
            }
            event.getInventory().setMatrix(matrix);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(itemFactory.createArachnaidAxe());
            overflow.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
        } else {
            ClickType click = event.getClick();
            if ((click == ClickType.LEFT || click == ClickType.RIGHT)
                    && event.getCursor() != null && !event.getCursor().getType().isAir()) {
                event.setCancelled(true);
                return;
            }
            if (click == ClickType.NUMBER_KEY) {
                ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                if (hotbarItem != null && !hotbarItem.getType().isAir()) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (click == ClickType.SWAP_OFFHAND) {
                ItemStack offhand = player.getInventory().getItemInOffHand();
                if (!offhand.getType().isAir()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        stateManager.markAxeClaimed(player);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        if (plugin.getConfig().getBoolean("crafting.broadcast-on-craft", true)) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String raw = plugin.getConfig().getString("messages.craft-claimed-broadcast", "")
                    .replace("{player}", player.getName());
            Bukkit.broadcast(plugin.parse(prefix + raw));
        }
    }
}
