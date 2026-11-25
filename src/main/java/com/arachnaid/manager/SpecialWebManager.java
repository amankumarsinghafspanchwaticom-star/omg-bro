package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpecialWebManager {

    private final ArachnaidPlugin plugin;
    private final File file;
    private final Set<BlockKey> trackedWebs = ConcurrentHashMap.newKeySet();

    public SpecialWebManager(ArachnaidPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "webs.yml");
    }

    public void addWebs(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        for (Block block : blocks) {
            trackedWebs.add(BlockKey.of(block));
        }
        save();
    }

    public boolean removeWeb(Block block) {
        boolean removed = trackedWebs.remove(BlockKey.of(block));
        if (removed) {
            save();
        }
        return removed;
    }

    public void removeWebs(List<Block> blocks) {
        boolean changed = false;
        for (Block block : blocks) {
            if (trackedWebs.remove(BlockKey.of(block))) {
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    public boolean isSpecialWeb(Block block) {
        if (block == null || block.getType() != Material.COBWEB) {
            return false;
        }
        return trackedWebs.contains(BlockKey.of(block));
    }

    public void load() {
        trackedWebs.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> entries = yaml.getStringList("webs");
        for (String raw : entries) {
            BlockKey key = BlockKey.parse(raw);
            if (key == null) {
                continue;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world != null && world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                if (world.getBlockAt(key.x(), key.y(), key.z()).getType() != Material.COBWEB) {
                    continue;
                }
            }
            trackedWebs.add(key);
        }
    }

    public void save() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> serialized = new ArrayList<>(trackedWebs.size());
        for (BlockKey key : trackedWebs) {
            serialized.add(key.serialize());
        }
        yaml.set("webs", serialized);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save webs.yml: " + e.getMessage());
        }
    }

    public record BlockKey(UUID worldId, int x, int y, int z) {
        public static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        public String serialize() {
            return worldId + ":" + x + ":" + y + ":" + z;
        }

        public static BlockKey parse(String raw) {
            String[] parts = raw.split(":");
            if (parts.length != 4) {
                return null;
            }
            try {
                return new BlockKey(
                        UUID.fromString(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])
                );
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
