package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class AxeStateManager {

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;
    private final File file;
    private final NamespacedKey recipeKey;
    private boolean axeActive = false;
    private UUID currentWielderUuid = null;
    private String currentWielderName = "None";

    public AxeStateManager(ArachnaidPlugin plugin, ArachnaidItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.file = new File(plugin.getDataFolder(), "state.yml");
        this.recipeKey = new NamespacedKey(plugin, "arachnaid_axe_recipe");
    }

    public NamespacedKey getRecipeKey() {
        return recipeKey;
    }

    public boolean isAxeActive() {
        return axeActive;
    }

    public UUID getCurrentWielderUuid() {
        return currentWielderUuid;
    }

    public String getCurrentWielderName() {
        return currentWielderName;
    }

    public void load() {
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            this.axeActive = yaml.getBoolean("axe-active", false);
            String uuidStr = yaml.getString("wielder-uuid", "");
            if (uuidStr != null && !uuidStr.isBlank()) {
                try {
                    this.currentWielderUuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ignored) {
                    this.currentWielderUuid = null;
                }
            } else {
                this.currentWielderUuid = null;
            }
            this.currentWielderName = yaml.getString("wielder-name", "None");
        } else {
            this.axeActive = false;
            this.currentWielderUuid = null;
            this.currentWielderName = "None";
            save();
        }
        syncRecipeState();
    }

    public void save() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("axe-active", axeActive);
        yaml.set("wielder-uuid", currentWielderUuid != null ? currentWielderUuid.toString() : "");
        yaml.set("wielder-name", currentWielderName != null ? currentWielderName : "None");
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save state.yml: " + e.getMessage());
        }
    }

    public void markAxeClaimed(Player claimer) {
        this.axeActive = true;
        if (claimer != null) {
            this.currentWielderUuid = claimer.getUniqueId();
            this.currentWielderName = claimer.getName();
        }
        save();
        syncRecipeState();
    }

    public void updateWielder(Player newWielder, boolean broadcast) {
        if (newWielder == null) {
            return;
        }
        UUID newId = newWielder.getUniqueId();
        boolean changed = !newId.equals(this.currentWielderUuid);

        this.axeActive = true;
        this.currentWielderUuid = newId;
        this.currentWielderName = newWielder.getName();
        save();
        syncRecipeState();

        if (changed && broadcast && plugin.getConfig().getBoolean("crafting.broadcast-on-wielder-change", true)) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String raw = plugin.getConfig().getString("messages.wielder-changed-broadcast", "")
                    .replace("{player}", newWielder.getName());
            Bukkit.broadcast(plugin.parse(prefix + raw));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 0.8f);
            }
        }
    }

    public void markAxeLostAndUnlock(String reason, boolean broadcast) {
        if (!this.axeActive) {
            return;
        }
        this.axeActive = false;
        this.currentWielderUuid = null;
        this.currentWielderName = "None";
        save();
        syncRecipeState();

        if (broadcast && plugin.getConfig().getBoolean("crafting.broadcast-on-unlock", true)) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String raw = plugin.getConfig().getString("messages.craft-unlocked-broadcast", "")
                    .replace("{reason}", reason);
            Bukkit.broadcast(plugin.parse(prefix + raw));
        }
    }

    public void forceUnlock() {
        this.axeActive = false;
        this.currentWielderUuid = null;
        this.currentWielderName = "None";
        save();
        syncRecipeState();
    }

    public void syncRecipeState() {
        Bukkit.removeRecipe(recipeKey);

        boolean craftingEnabled = plugin.getConfig().getBoolean("crafting.enabled", true);
        if (!axeActive && craftingEnabled) {
            ShapedRecipe recipe = buildRecipeFromConfig();
            if (recipe != null) {
                Bukkit.addRecipe(recipe);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.discoverRecipe(recipeKey);
                }
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.undiscoverRecipe(recipeKey);
            }
        }
    }

    public void syncPlayerRecipeBook(Player player) {
        boolean craftingEnabled = plugin.getConfig().getBoolean("crafting.enabled", true);
        if (!axeActive && craftingEnabled) {
            player.discoverRecipe(recipeKey);
        } else {
            player.undiscoverRecipe(recipeKey);
        }
    }

    private ShapedRecipe buildRecipeFromConfig() {
        List<String> shape = plugin.getConfig().getStringList("crafting.shape");
        if (shape.size() != 3) {
            plugin.getLogger().warning("Invalid crafting.shape in config.yml (must have 3 rows). Using default.");
            shape = List.of("GEG", "FAF", "GEG");
        }

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, itemFactory.createArachnaidAxe());
        recipe.shape(shape.get(0), shape.get(1), shape.get(2));

        ConfigurationSection ingSection = plugin.getConfig().getConfigurationSection("crafting.ingredients");
        if (ingSection != null) {
            for (String key : ingSection.getKeys(false)) {
                if (key.length() != 1) {
                    continue;
                }
                String matName = ingSection.getString(key, "");
                Material mat = Material.matchMaterial(matName);
                if (mat == null || mat.isAir()) {
                    plugin.getLogger().warning("Invalid crafting ingredient '" + matName + "' for key '" + key + "'.");
                    continue;
                }
                recipe.setIngredient(key.charAt(0), mat);
            }
        } else {
            recipe.setIngredient('G', Material.ENCHANTED_GOLDEN_APPLE);
            recipe.setIngredient('E', Material.EMERALD_ORE);
            recipe.setIngredient('F', Material.FERMENTED_SPIDER_EYE);
            recipe.setIngredient('A', Material.DIAMOND_AXE);
        }

        return recipe;
    }
}
