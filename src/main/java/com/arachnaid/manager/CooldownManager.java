package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CooldownManager {

    private final ArachnaidPlugin plugin;
    private final File file;
    private final Map<UUID, Long> expirations = new ConcurrentHashMap<>();

    public CooldownManager(ArachnaidPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cooldowns.yml");
    }

    public boolean isOnCooldown(UUID playerId) {
        Long expiry = expirations.get(playerId);
        if (expiry == null) {
            return false;
        }
        if (expiry <= System.currentTimeMillis()) {
            expirations.remove(playerId);
            return false;
        }
        return true;
    }

    public long getRemainingSeconds(UUID playerId) {
        Long expiry = expirations.get(playerId);
        if (expiry == null) {
            return 0L;
        }
        long diff = expiry - System.currentTimeMillis();
        if (diff <= 0L) {
            expirations.remove(playerId);
            return 0L;
        }
        return (long) Math.ceil(diff / 1000.0);
    }

    public void setCooldown(UUID playerId, long seconds) {
        expirations.put(playerId, System.currentTimeMillis() + (seconds * 1000L));
        save();
    }

    public void resetCooldown(UUID playerId) {
        expirations.remove(playerId);
        save();
    }

    public void load() {
        expirations.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long expiry = yaml.getLong(key, 0L);
                if (expiry > now) {
                    expirations.put(uuid, expiry);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        expirations.entrySet().removeIf(entry -> entry.getValue() <= now);
        for (Map.Entry<UUID, Long> entry : expirations.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save cooldowns.yml: " + e.getMessage());
        }
    }
}
