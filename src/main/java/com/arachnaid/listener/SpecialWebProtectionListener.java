package com.arachnaid.listener;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.manager.SpecialWebManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;

public final class SpecialWebProtectionListener implements Listener {

    private final ArachnaidPlugin plugin;
    private final SpecialWebManager webManager;

    public SpecialWebProtectionListener(ArachnaidPlugin plugin, SpecialWebManager webManager) {
        this.plugin = plugin;
        this.webManager = webManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!webManager.isSpecialWeb(block)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();

        boolean isShears = tool.getType() == Material.SHEARS;
        boolean creativeAdminBypass = plugin.getConfig().getBoolean("special-webs.creative-admin-can-break", true)
                && player.getGameMode() == GameMode.CREATIVE
                && player.hasPermission("arachnaid.admin");

        if (isShears || creativeAdminBypass) {
            if (!plugin.getConfig().getBoolean("special-webs.drop-items-on-break", false)) {
                event.setDropItems(false);
            }
            webManager.removeWeb(block);
            return;
        }

        event.setCancelled(true);
        player.sendActionBar(plugin.rawMsg("web-protected"));
        player.playSound(block.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.6f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (event.getBlock().getType() != Material.WATER) {
            return;
        }
        Block toBlock = event.getToBlock();
        if (!webManager.isSpecialWeb(toBlock)) {
            return;
        }

        webManager.removeWeb(toBlock);
        if (!plugin.getConfig().getBoolean("special-webs.drop-items-on-break", false)) {
            toBlock.setType(Material.AIR);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET) {
            return;
        }
        Block target = event.getBlock();
        if (webManager.isSpecialWeb(target)) {
            webManager.removeWeb(target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        webManager.removeWebs(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        webManager.removeWebs(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        webManager.removeWebs(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        webManager.removeWebs(event.getBlocks());
    }
}
