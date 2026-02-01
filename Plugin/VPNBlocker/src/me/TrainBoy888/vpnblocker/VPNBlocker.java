package me.TrainBoy888.vpnblocker;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class VPNBlocker extends JavaPlugin {

    private static VPNBlocker instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new VPNCheckListener(this), this);
        startStatsReporting();
        getLogger().info("VPNBlocker enabled");
    }

    public static VPNBlocker getInstance() {
        return instance;
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
