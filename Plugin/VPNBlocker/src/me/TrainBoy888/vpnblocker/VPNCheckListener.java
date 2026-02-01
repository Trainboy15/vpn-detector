package me.TrainBoy888.vpnblocker;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class VPNCheckListener implements Listener {

    private final VPNBlocker plugin;

    public VPNCheckListener(VPNBlocker plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        FileConfiguration config = plugin.getConfig();

        boolean checksEnabled = config.getBoolean(
            "checks.on-prelogin",
            config.getBoolean("checks.on-join", true)
        );
        if (!checksEnabled) {
            return;
        }

        if (event.getAddress() == null) {
            plugin.getLogger().warning("Could not resolve IP for " + event.getName());
            return;
        }

        String ip = event.getAddress().getHostAddress();

        String baseUrl = config.getString("api.base-url", "");
        int connectTimeout = config.getInt("api.connect-timeout-ms", 5000);
        int readTimeout = config.getInt("api.read-timeout-ms", 5000);
        boolean kickEnabled = config.getBoolean("kick.enabled", true);
        boolean kickOnError = config.getBoolean("checks.kick-on-error", false);
        boolean debug = config.getBoolean("logging.debug", false);

        if (baseUrl.isBlank()) {
            plugin.getLogger().warning("api.base-url is not configured. (Change this in config.yml)");
            return;
        }

        try {
            String urlString = baseUrl + "/check/" + ip;

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
            );

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String json = response.toString();

            boolean isVPN = json.contains("\"isVPN\":true");

            if (debug) {
                plugin.getLogger().info("VPN check for " + event.getName() + " -> " + json);
            }

            if (isVPN && kickEnabled) {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.translateAlternateColorCodes('&', getKickMessage(config))
                );
            }

        } catch (Exception e) {
            // Fail-open (allow player if API is down)
            plugin.getLogger().warning("VPN check failed for IP: " + ip);
            if (kickOnError && kickEnabled) {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.translateAlternateColorCodes('&', getKickErrorMessage(config))
                );
            }
        }
    }

    private String getKickMessage(FileConfiguration config) {
        String message = String.join("\n", config.getStringList("kick.message"));
        if (message.isBlank()) {
            return "&cVPNs and proxies are not allowed on this server.\n" +
                   "&7Please disable your VPN and try again.";
        }
        return message;
    }

    private String getKickErrorMessage(FileConfiguration config) {
        String message = String.join("\n", config.getStringList("kick.error-message"));
        if (message.isBlank()) {
            return "&cCould not verify your connection.\n" +
                   "&7Please try again later.";
        }
        return message;
    }
}
