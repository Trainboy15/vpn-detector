package me.TrainBoy888.vpnblocker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class VPNBlocker extends JavaPlugin implements CommandExecutor {

    private static VPNBlocker instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new VPNCheckListener(this), this);
        getCommand("vpnblocker").setExecutor(this);
        startStatsReporting();
        getLogger().info("VPNBlocker enabled");
    }

    public static VPNBlocker getInstance() {
        return instance;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("vpnblocker")) {
            return false;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "reload":
                return handleReload(sender);
            case "check":
                return handleCheck(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("vpnblocker.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        try {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "VPNBlocker configuration reloaded successfully.");
            return true;
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to reload configuration: " + e.getMessage());
            return true;
        }
    }

    private boolean handleCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vpnblocker.check")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /vpnblocker check <player|ip>");
            return true;
        }

        String target = args[1];

        // Run async to avoid blocking the server
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String ipToCheck;

                // Check if target is a player name
                Player player = Bukkit.getPlayer(target);
                if (player != null) {
                    ipToCheck = player.getAddress().getAddress().getHostAddress();
                    sender.sendMessage(ChatColor.GRAY + "Checking player: " + ChatColor.WHITE + player.getName());
                } else {
                    // Assume it's an IP address
                    ipToCheck = target;
                    sender.sendMessage(ChatColor.GRAY + "Checking IP: " + ChatColor.WHITE + ipToCheck);
                }

                boolean isVPN = queryVPN(ipToCheck);

                if (isVPN) {
                    sender.sendMessage(ChatColor.RED + "Result: " + ChatColor.DARK_RED + "VPN/PROXY DETECTED");
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Result: " + ChatColor.DARK_GREEN + "Not a VPN");
                }

            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Check failed: " + e.getMessage());
            }
        });

        return true;
    }

    private boolean queryVPN(String ip) throws Exception {
        String baseUrl = getConfig().getString("api.base-url", "");
        if (baseUrl.isBlank()) {
            throw new Exception("API base URL not configured");
        }

        String urlString = baseUrl + "/check/" + ip;
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(getConfig().getInt("api.connect-timeout-ms", 5000));
        conn.setReadTimeout(getConfig().getInt("api.read-timeout-ms", 5000));

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream())
        );

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        String json = response.toString();
        return json.contains("\"isVPN\":true");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "=== VPNBlocker Commands ===");
        sender.sendMessage(ChatColor.GOLD + "/vpnblocker reload" + ChatColor.GRAY + " - Reload configuration");
        sender.sendMessage(ChatColor.GOLD + "/vpnblocker check <player|ip>" + ChatColor.GRAY + " - Check if a player or IP is a VPN");
    }

    private void startStatsReporting() {
        if (!getConfig().getBoolean("stats.enabled", true)) {
            return;
        }

        int intervalSeconds = getConfig().getInt("stats.interval-seconds", 60);
        if (intervalSeconds <= 0) {
            intervalSeconds = 60;
        }

        long ticks = intervalSeconds * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(
            this,
            this::sendStatsPing,
            0L,
            ticks
        );
    }

    private void sendStatsPing() {
        String baseUrl = getConfig().getString("api.base-url", "");
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }

        String serverId = getConfig().getString("stats.server-id", "");
        if (serverId == null || serverId.isBlank()) {
            serverId = getServer().getName();
        }

        int onlinePlayers = getServer().getOnlinePlayers().size();
        int maxPlayers = getServer().getMaxPlayers();
        String version = getServer().getVersion();
        boolean debug = getConfig().getBoolean("logging.debug", false);

        String payload = "{" +
            "\"serverId\":" + quote(serverId) + "," +
            "\"timestamp\":" + System.currentTimeMillis() + "," +
            "\"onlinePlayers\":" + onlinePlayers + "," +
            "\"maxPlayers\":" + maxPlayers + "," +
            "\"version\":" + quote(version) +
            "}";

        try {
            URL url = new URL(baseUrl + "/ping");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(getConfig().getInt("api.connect-timeout-ms", 5000));
            conn.setReadTimeout(getConfig().getInt("api.read-timeout-ms", 5000));
            conn.setRequestProperty("Content-Type", "application/json");

            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            if (debug) {
                getLogger().info("Stats ping sent (" + code + ") for " + serverId);
            }
            conn.disconnect();
        } catch (Exception e) {
            if (debug) {
                getLogger().warning("Stats ping failed: " + e.getMessage());
            }
        }
    }

    private String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
