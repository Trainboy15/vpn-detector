package me.TrainBoy888.vpnblocker;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

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

        boolean kickEnabled = config.getBoolean("kick.enabled", true);
        boolean debug = config.getBoolean("logging.debug", false);

        try {
            boolean isVPN = plugin.isIpBlocked(ip);

            if (debug) {
                plugin.getLogger().info("VPN cache check for " + event.getName() + " (" + ip + ") -> " + isVPN);
            }

            if (isVPN && kickEnabled) {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.translateAlternateColorCodes('&', getKickMessage(config))
                );
            }

        } catch (Exception e) {
            plugin.getLogger().warning("VPN cache check failed for IP: " + ip + " (" + e.getMessage() + ")");
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
