package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

public final class ResourcePackManager {

    private static final String BUNDLED_PATH = "resourcepack/ArachnaidAxe-ResourcePack.zip";

    private final ArachnaidPlugin plugin;
    private HttpServer httpServer;
    private byte[] cachedPackBytes;
    private String cachedSha1Hex;
    private volatile String activePackUrl;
    private boolean usingCustomUrl = false;

    public ResourcePackManager(ArachnaidPlugin plugin) {
        this.plugin = plugin;
    }

    public String getActivePackUrl() {
        return activePackUrl;
    }

    public boolean isUsingCustomUrl() {
        return usingCustomUrl;
    }

    public void start() {
        stop();

        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }

        File outFile = extractBundledPack();
        if (outFile == null || !outFile.exists()) {
            plugin.getLogger().warning("Bundled resource pack could not be extracted.");
            return;
        }

        try {
            this.cachedPackBytes = Files.readAllBytes(outFile.toPath());
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            this.cachedSha1Hex = HexFormat.of().formatHex(md.digest(cachedPackBytes));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read/hash resource pack: " + e.getMessage());
            return;
        }

        String customUrl = plugin.getConfig().getString("resource-pack.custom-url", "").trim();
        if (!customUrl.isEmpty()) {
            this.usingCustomUrl = true;
            this.activePackUrl = customUrl;
            plugin.getLogger().info("Using custom resource pack URL: " + activePackUrl + " (SHA-1: " + cachedSha1Hex + ")");
            return;
        }

        this.usingCustomUrl = false;
        if (!plugin.getConfig().getBoolean("resource-pack.use-built-in-host", true)) {
            return;
        }

        int port = plugin.getConfig().getInt("resource-pack.port", 25580);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/arachnaid-pack.zip", exchange -> {
                String method = exchange.getRequestMethod();
                if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                if ("HEAD".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, cachedPackBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(cachedPackBytes);
                    }
                }
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            this.httpServer = server;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not bind Resource Pack HTTP port " + port + ": " + e.getMessage()
                    + ". Upload plugins/ArachnaidAxe/ArachnaidAxe-ResourcePack.zip to https://mc-packs.net and set 'resource-pack.custom-url' in config.yml.");
            return;
        }

        String configuredHost = plugin.getConfig().getString("resource-pack.public-host", "").trim();
        if (!configuredHost.isEmpty()) {
            this.activePackUrl = "http://" + configuredHost + ":" + port + "/arachnaid-pack.zip";
            plugin.getLogger().info("Bundled Resource Pack server started at: " + activePackUrl + " (SHA-1: " + cachedSha1Hex + ")");
        } else {
            // Auto-detect real public IPv4 async so cloud/Docker hosts never advertise private 172.x/10.x IPs
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String publicIp = fetchPublicIpv4();
                this.activePackUrl = "http://" + publicIp + ":" + port + "/arachnaid-pack.zip";
                plugin.getLogger().info("Bundled Resource Pack server started at: " + activePackUrl + " (SHA-1: " + cachedSha1Hex + ")");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        sendPack(online);
                    }
                });
            });
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        activePackUrl = null;
    }

    public void sendPack(Player player) {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }
        if (activePackUrl == null || activePackUrl.isBlank() || cachedSha1Hex == null) {
            return;
        }

        try {
            UUID packUuid = UUID.nameUUIDFromBytes(("arachnaid-pack-v2-" + cachedSha1Hex).getBytes(StandardCharsets.UTF_8));
            ResourcePackInfo.Builder infoBuilder = ResourcePackInfo.resourcePackInfo()
                    .id(packUuid)
                    .uri(URI.create(activePackUrl));

            // Only attach local SHA-1 when serving our exact bundled ZIP (custom URLs may be re-zipped by external hosts)
            if (!usingCustomUrl) {
                infoBuilder.hash(cachedSha1Hex);
            } else {
                infoBuilder.hash(cachedSha1Hex);
            }

            boolean required = plugin.getConfig().getBoolean("resource-pack.required", false);
            String promptRaw = plugin.getConfig().getString("resource-pack.prompt",
                    "<dark_red><bold>Arachnaid Axe</bold></dark_red> <gray>Mythic 32x32 Weapon Texture</gray>");
            Component prompt = plugin.parse(promptRaw);

            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(infoBuilder.build())
                    .required(required)
                    .prompt(prompt)
                    .replace(false)
                    .build();

            player.sendResourcePacks(request);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send resource pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    private File extractBundledPack() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File target = new File(plugin.getDataFolder(), "ArachnaidAxe-ResourcePack.zip");
        try (InputStream in = plugin.getResource(BUNDLED_PATH)) {
            if (in == null) {
                return null;
            }
            Files.write(target.toPath(), in.readAllBytes());
            return target;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to extract bundled resource pack: " + e.getMessage());
            return null;
        }
    }

    private String fetchPublicIpv4() {
        String serverIp = Bukkit.getIp();
        if (serverIp != null && !serverIp.isBlank() && !"0.0.0.0".equals(serverIp)
                && !serverIp.startsWith("127.") && !serverIp.startsWith("10.")
                && !serverIp.startsWith("172.") && !serverIp.startsWith("192.168.")) {
            return serverIp;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body().trim();
                if (!body.isBlank() && body.length() <= 45) {
                    return body;
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
