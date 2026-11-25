package com.arachnaid.command;

import com.arachnaid.ArachnaidPlugin;
import com.arachnaid.item.ArachnaidItemFactory;
import com.arachnaid.manager.AxeStateManager;
import com.arachnaid.manager.CooldownManager;
import com.arachnaid.manager.ResourcePackManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ArachnaidCommand implements CommandExecutor, TabCompleter {

    private final ArachnaidPlugin plugin;
    private final ArachnaidItemFactory itemFactory;
    private final AxeStateManager stateManager;
    private final CooldownManager cooldownManager;
    private final ResourcePackManager resourcePackManager;

    public ArachnaidCommand(
            ArachnaidPlugin plugin,
            ArachnaidItemFactory itemFactory,
            AxeStateManager stateManager,
            CooldownManager cooldownManager,
            ResourcePackManager resourcePackManager
    ) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.stateManager = stateManager;
        this.cooldownManager = cooldownManager;
        this.resourcePackManager = resourcePackManager;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "give" -> handleGive(sender, args);
            case "reload" -> handleReload(sender);
            case "pack" -> handlePack(sender, args);
            case "cooldown" -> handleCooldown(sender, args);
            case "reset" -> handleReset(sender, args);
            case "unlock" -> handleUnlock(sender);
            case "status" -> handleStatus(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("arachnaid.admin.give")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.parse("<red>Usage: /arachnaid give <player> [force]</red>"));
            return;
        }

        boolean force = args.length >= 3 && args[2].equalsIgnoreCase("force");
        if (stateManager.isAxeActive() && !force) {
            sender.sendMessage(plugin.parse(
                    "<red>ARACHNAID AXE is already crafted/active.</red> "
                    + "<gray>Use <white>/arachnaid give " + args[1] + " force</white> to override or <white>/arachnaid unlock</white> first.</gray>"
            ));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.parse("<red>Player not found: " + args[1] + "</red>"));
            return;
        }

        ItemStack axe = itemFactory.createArachnaidAxe();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(axe);
        overflow.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));

        stateManager.updateWielder(target, true);

        sender.sendMessage(plugin.parse("<green>Gave <dark_red><bold>ARACHNAID AXE</bold></dark_red> (Sharpness "
                + itemFactory.getConfiguredSharpnessLevel() + ") to <white>" + target.getName() + "</white>.</green>"));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("arachnaid.admin.reload")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        plugin.reloadConfig();
        stateManager.syncRecipeState();
        resourcePackManager.start();
        for (Player p : Bukkit.getOnlinePlayers()) {
            resourcePackManager.sendPack(p);
        }
        sender.sendMessage(plugin.parse("<green>ArachnaidAxe config, recipe, and resource pack reloaded.</green>"));
    }

    private void handlePack(CommandSender sender, String[] args) {
        if (!sender.hasPermission("arachnaid.admin.pack")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.parse("<red>Player not found: " + args[1] + "</red>"));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.parse("<red>Usage: /arachnaid pack <player></red>"));
            return;
        }
        resourcePackManager.sendPack(target);
        sender.sendMessage(plugin.parse("<green>Sent texture pack to <white>" + target.getName()
                + "</white> <gray>(" + resourcePackManager.getActivePackUrl() + ")</gray></green>"));
    }

    private void handleCooldown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("arachnaid.admin.cooldown")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.parse("<red>Usage: /arachnaid cooldown <player></red>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.parse("<red>Player not found: " + args[1] + "</red>"));
            return;
        }

        long remaining = cooldownManager.getRemainingSeconds(target.getUniqueId());
        if (remaining <= 0) {
            sender.sendMessage(plugin.parse("<green>" + target.getName() + " has no active Spider Web cooldown.</green>"));
        } else {
            sender.sendMessage(plugin.parse("<yellow>" + target.getName() + "'s Spider Web cooldown: <white>" + remaining + "s</white>.</yellow>"));
        }
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("arachnaid.admin.reset")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.parse("<red>Usage: /arachnaid reset <player></red>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.parse("<red>Player not found: " + args[1] + "</red>"));
            return;
        }

        cooldownManager.resetCooldown(target.getUniqueId());
        sender.sendMessage(plugin.parse("<green>Reset Spider Web cooldown for <white>" + target.getName() + "</white>.</green>"));
    }

    private void handleUnlock(CommandSender sender) {
        if (!sender.hasPermission("arachnaid.admin.unlock")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        stateManager.forceUnlock();
        sender.sendMessage(plugin.parse("<green>ARACHNAID AXE crafting unlocked again for all players.</green>"));
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("arachnaid.admin.status")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        String packUrl = resourcePackManager.getActivePackUrl() != null ? resourcePackManager.getActivePackUrl() : "Disabled";
        if (stateManager.isAxeActive()) {
            sender.sendMessage(plugin.parse(
                    "<yellow>ARACHNAID AXE: <red>CRAFTED</red> | Wielder: <white>"
                            + stateManager.getCurrentWielderName() + "</white> | Pack: <gray>" + packUrl + "</gray></yellow>"
            ));
        } else {
            sender.sendMessage(plugin.parse(
                    "<yellow>ARACHNAID AXE: <green>CRAFTABLE</green> | Pack: <gray>" + packUrl + "</gray></yellow>"
            ));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(plugin.parse("<dark_red><bold>ArachnaidAxe Commands:</bold></dark_red>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid give <player> [force]</gray>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid pack [player]</gray>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid reload</gray>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid cooldown <player></gray>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid reset <player></gray>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid unlock</gray>"));
        sender.sendMessage(plugin.parse("<gray>/arachnaid status</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            List<String> subs = List.of("give", "pack", "reload", "cooldown", "reset", "unlock", "status");
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && List.of("give", "pack", "cooldown", "reset").contains(args[0].toLowerCase())) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return "force".startsWith(args[2].toLowerCase()) ? List.of("force") : List.of();
        }
        return List.of();
    }
}
