package com.arachnaid.listener;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import com.arachnaid.manager.CooldownManager;
import com.arachnaid.manager.SpecialWebManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;

public final class SpiderWebAbilityListener implements Listener {

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;
    private final CooldownManager cooldownManager;
    private final SpecialWebManager webManager;

    public SpiderWebAbilityListener(
            ArachnaidPlugin plugin,
            ArachnaidItemFactory itemFactory,
            CooldownManager cooldownManager,
            SpecialWebManager webManager
    ) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.cooldownManager = cooldownManager;
        this.webManager = webManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!itemFactory.isArachnaidAxe(event.getItem())) {
            return;
        }

        Player caster = event.getPlayer();
        double minPitch = plugin.getConfig().getDouble("spider-web.min-look-down-pitch", 45.0);

        if (!caster.isSneaking() || caster.getLocation().getPitch() < minPitch) {
            return;
        }

        event.setCancelled(true);

        if (!caster.hasPermission("arachnaid.use.web")) {
            caster.sendActionBar(plugin.rawMsg("no-permission"));
            return;
        }

        if (!caster.hasPermission("arachnaid.cooldown.bypass") && cooldownManager.isOnCooldown(caster.getUniqueId())) {
            long remaining = cooldownManager.getRemainingSeconds(caster.getUniqueId());
            String raw = plugin.getConfig().getString("messages.web-on-cooldown", "")
                    .replace("{time}", String.valueOf(remaining));
            caster.sendActionBar(plugin.parse(raw));
            caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }

        double halfWidth = plugin.getConfig().getDouble("spider-web.range-half-width", 4.0);
        double maxVertical = plugin.getConfig().getDouble("spider-web.range-vertical", 4.0);
        boolean targetCreative = plugin.getConfig().getBoolean("spider-web.target-creative", false);
        boolean targetMobs = plugin.getConfig().getBoolean("spider-web.target-mobs", false);

        Location origin = caster.getLocation();
        World world = caster.getWorld();

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(origin, halfWidth + 0.5, maxVertical + 0.5, halfWidth + 0.5)) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (living.getUniqueId().equals(caster.getUniqueId())) {
                continue;
            }
            if (living instanceof Player candidate) {
                if (candidate.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                if (!targetCreative && candidate.getGameMode() == GameMode.CREATIVE) {
                    continue;
                }
                if (candidate.hasPermission("arachnaid.exempt")) {
                    continue;
                }
            } else if (!targetMobs) {
                continue;
            }

            Location loc = living.getLocation();
            if (Math.abs(loc.getX() - origin.getX()) <= halfWidth
                    && Math.abs(loc.getZ() - origin.getZ()) <= halfWidth
                    && Math.abs(loc.getY() - origin.getY()) <= maxVertical) {
                targets.add(living);
            }
        }

        List<Block> allPlacedWebs = new ArrayList<>();
        int affectedCount = 0;

        for (LivingEntity target : targets) {
            List<Block> placed = placeFourGroundedWebs(target);
            if (!placed.isEmpty()) {
                allPlacedWebs.addAll(placed);
                affectedCount++;
                spawnWebEffects(target.getLocation());
            }
        }

        if (allPlacedWebs.isEmpty()) {
            caster.sendActionBar(plugin.rawMsg("web-no-targets"));
            caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            if (plugin.getConfig().getBoolean("spider-web.consume-cooldown-on-miss", false)) {
                applyCooldown(caster);
            }
            return;
        }

        webManager.addWebs(allPlacedWebs);
        applyCooldown(caster);

        world.playSound(origin, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 0.9f);
        world.playSound(origin, Sound.BLOCK_COBWEB_PLACE, 1.0f, 1.0f);

        String successRaw = plugin.getConfig().getString("messages.web-activated", "")
                .replace("{count}", String.valueOf(affectedCount));
        caster.sendActionBar(plugin.parse(successRaw));
    }

    private List<Block> placeFourGroundedWebs(LivingEntity target) {
        Location loc = target.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return List.of();
        }

        int maxSearch = plugin.getConfig().getInt("spider-web.max-ground-search-depth", 5);
        Integer groundSurfaceY = findGroundSurfaceY(loc, maxSearch);
        if (groundSurfaceY == null) {
            return List.of();
        }

        int baseX = (loc.getX() - Math.floor(loc.getX()) >= 0.5) ? loc.getBlockX() : loc.getBlockX() - 1;
        int baseZ = (loc.getZ() - Math.floor(loc.getZ()) >= 0.5) ? loc.getBlockZ() : loc.getBlockZ() - 1;

        int[][] primaryOffsets = {
                {0, 0}, {1, 0}, {0, 1}, {1, 1}
        };

        List<Block> placed = new ArrayList<>(4);
        for (int[] off : primaryOffsets) {
            Block b = world.getBlockAt(baseX + off[0], groundSurfaceY, baseZ + off[1]);
            if (canPlaceGroundedWeb(b)) {
                b.setType(Material.COBWEB, false);
                placed.add(b);
            }
        }

        if (placed.size() < 4) {
            int centerX = loc.getBlockX();
            int centerZ = loc.getBlockZ();
            int[][] fallbackOffsets = {
                    {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                    {-1, -1}, {1, -1}, {-1, 1}, {1, 1}
            };
            for (int[] off : fallbackOffsets) {
                if (placed.size() >= 4) {
                    break;
                }
                Block b = world.getBlockAt(centerX + off[0], groundSurfaceY, centerZ + off[1]);
                if (!placed.contains(b) && canPlaceGroundedWeb(b)) {
                    b.setType(Material.COBWEB, false);
                    placed.add(b);
                }
            }
        }

        return placed;
    }

    private Integer findGroundSurfaceY(Location loc, int maxDepth) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int startY = loc.getBlockY();
        int z = loc.getBlockZ();
        int minY = world.getMinHeight();

        for (int dy = 0; dy <= maxDepth; dy++) {
            int y = startY - dy;
            if (y <= minY) {
                break;
            }
            Block candidate = world.getBlockAt(x, y, z);
            Block below = candidate.getRelative(BlockFace.DOWN);
            if (below.getType().isSolid() && below.getType() != Material.COBWEB) {
                return y;
            }
        }
        return null;
    }

    private boolean canPlaceGroundedWeb(Block block) {
        if (!isReplaceable(block.getType())) {
            return false;
        }
        Block below = block.getRelative(BlockFace.DOWN);
        return below.getType().isSolid() && below.getType() != Material.COBWEB;
    }

    private boolean isReplaceable(Material type) {
        return type.isAir()
                || type == Material.SHORT_GRASS
                || type == Material.TALL_GRASS
                || type == Material.FERN
                || type == Material.LARGE_FERN
                || type == Material.SNOW;
    }

    private void applyCooldown(Player caster) {
        if (!caster.hasPermission("arachnaid.cooldown.bypass")) {
            long seconds = plugin.getConfig().getLong("spider-web.cooldown-seconds", 120L);
            cooldownManager.setCooldown(caster.getUniqueId(), seconds);
        }
    }

    private void spawnWebEffects(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.BLOCK, location.clone().add(0, 0.3, 0), 22,
                0.55, 0.25, 0.55, Material.COBWEB.createBlockData());
        world.playSound(location, Sound.BLOCK_COBWEB_PLACE, 0.9f, 1.1f);
    }
}
