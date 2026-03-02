package me.TrainBoy888.vpnblocker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VPNBlocker extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static VPNBlocker instance;

    private final Object rangeLock = new Object();
    private List<CidrRange> cachedRanges = new ArrayList<CidrRange>();
    private final Set<String> blacklistedIps = new HashSet<String>();
    private final Set<String> whitelistedIps = new HashSet<String>();
    private File rangesCacheFile;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        rangesCacheFile = new File(getDataFolder(), "vpn-ranges-cache.txt");

        loadManualListsFromConfig();
        loadCachedRangesFromDisk();

        getServer().getPluginManager().registerEvents(new VPNCheckListener(this), this);
        if (getCommand("vpnblocker") != null) {
            getCommand("vpnblocker").setExecutor(this);
            getCommand("vpnblocker").setTabCompleter(this);
        }

        refreshRangesFromSourcesAsync();
        startStatsReporting();

        getLogger().info("VPNBlocker enabled (cached ranges: " + getCachedRangeCount() + ")");
    }

    @Override
    public void onDisable() {
        saveManualListsToConfig();
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

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "reload":
                return handleReload(sender);
            case "check":
                return handleCheck(sender, args);
            case "blacklist":
                return handleManualListCommand(sender, args, true);
            case "whitelist":
                return handleManualListCommand(sender, args, false);
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
            loadManualListsFromConfig();
            refreshRangesFromSourcesAsync();
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

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String ipToCheck = resolveTargetIp(target);
                if (ipToCheck == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid player or IP: " + target);
                    return;
                }

                sender.sendMessage(ChatColor.GRAY + "Checking IP: " + ChatColor.WHITE + ipToCheck);
                boolean isVPN = isIpBlocked(ipToCheck);

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

    private boolean handleManualListCommand(CommandSender sender, String[] args, boolean blacklist) {
        String permissionPrefix = blacklist ? "vpnblocker.blacklist" : "vpnblocker.whitelist";
        String listName = blacklist ? "blacklist" : "whitelist";

        if (!sender.hasPermission(permissionPrefix)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /vpnblocker " + listName + " <add|remove|list> [player|ip]");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            Set<String> source = blacklist ? blacklistedIps : whitelistedIps;
            synchronized (source) {
                sender.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "VPNBlocker " + (blacklist ? "Blacklist" : "Whitelist") + " (" + source.size() + ")");
                if (source.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "(empty)");
                } else {
                    sender.sendMessage(ChatColor.GRAY + String.join(ChatColor.DARK_GRAY + ", " + ChatColor.WHITE, source));
                }
            }
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /vpnblocker " + listName + " <add|remove> <player|ip>");
            return true;
        }

        String ip = resolveTargetIp(args[2]);
        if (ip == null) {
            sender.sendMessage(ChatColor.RED + "Invalid player or IP: " + args[2]);
            return true;
        }

        if ("add".equals(action)) {
            if (blacklist) {
                synchronized (blacklistedIps) {
                    blacklistedIps.add(ip);
                }
            } else {
                synchronized (whitelistedIps) {
                    whitelistedIps.add(ip);
                }
            }
            saveManualListsToConfig();
            sender.sendMessage(ChatColor.GREEN + "Added " + ChatColor.WHITE + ip + ChatColor.GREEN + " to " + listName + ".");
            return true;
        }

        if ("remove".equals(action)) {
            boolean removed;
            if (blacklist) {
                synchronized (blacklistedIps) {
                    removed = blacklistedIps.remove(ip);
                }
            } else {
                synchronized (whitelistedIps) {
                    removed = whitelistedIps.remove(ip);
                }
            }
            saveManualListsToConfig();
            if (removed) {
                sender.sendMessage(ChatColor.GREEN + "Removed " + ChatColor.WHITE + ip + ChatColor.GREEN + " from " + listName + ".");
            } else {
                sender.sendMessage(ChatColor.YELLOW + ip + " was not in " + listName + ".");
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /vpnblocker " + listName + " <add|remove|list> [player|ip]");
        return true;
    }

    public boolean isIpBlocked(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }

        String normalizedIp = ip.trim();

        synchronized (whitelistedIps) {
            if (whitelistedIps.contains(normalizedIp)) {
                return false;
            }
        }

        synchronized (blacklistedIps) {
            if (blacklistedIps.contains(normalizedIp)) {
                return true;
            }
        }

        synchronized (rangeLock) {
            for (CidrRange range : cachedRanges) {
                if (range.contains(normalizedIp)) {
                    return true;
                }
            }
        }

        return false;
    }

    public int getCachedRangeCount() {
        synchronized (rangeLock) {
            return cachedRanges.size();
        }
    }

    private void refreshRangesFromSourcesAsync() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                List<String> sourceUrls = getConfig().getStringList("lists.source-urls");
                if (sourceUrls == null || sourceUrls.isEmpty()) {
                    getLogger().warning("No list source URLs configured. Using local cache only.");
                    return;
                }

                List<CidrRange> parsed = new ArrayList<CidrRange>();
                List<String> allRawLines = new ArrayList<String>();

                for (String sourceUrl : sourceUrls) {
                    if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
                        continue;
                    }

                    String content = downloadText(sourceUrl.trim());
                    List<String> lines = extractCidrLines(content);
                    allRawLines.addAll(lines);

                    for (String line : lines) {
                        CidrRange range = CidrRange.parse(line);
                        if (range != null) {
                            parsed.add(range);
                        }
                    }
                }

                synchronized (rangeLock) {
                    cachedRanges = parsed;
                }

                saveRangesToDisk(allRawLines);
                getLogger().info("VPN list refreshed: " + parsed.size() + " ranges loaded and cached.");
            } catch (Exception e) {
                getLogger().warning("Failed to refresh VPN list from source, using cache: " + e.getMessage());
            }
        });
    }

    private void loadCachedRangesFromDisk() {
        if (rangesCacheFile == null || !rangesCacheFile.exists()) {
            return;
        }

        List<CidrRange> parsed = new ArrayList<CidrRange>();

        try (BufferedReader reader = new BufferedReader(new FileReader(rangesCacheFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                CidrRange range = CidrRange.parse(trimmed);
                if (range != null) {
                    parsed.add(range);
                }
            }

            synchronized (rangeLock) {
                cachedRanges = parsed;
            }

            getLogger().info("Loaded " + parsed.size() + " VPN ranges from local cache.");
        } catch (Exception e) {
            getLogger().warning("Could not load VPN range cache: " + e.getMessage());
        }
    }

    private void saveRangesToDisk(List<String> ranges) {
        if (rangesCacheFile == null) {
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rangesCacheFile, false))) {
            for (String range : ranges) {
                String trimmed = range == null ? "" : range.trim();
                if (!trimmed.isEmpty()) {
                    writer.write(trimmed);
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to save VPN ranges cache: " + e.getMessage());
        }
    }

    private String downloadText(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(getConfig().getInt("api.connect-timeout-ms", 5000));
        conn.setReadTimeout(getConfig().getInt("api.read-timeout-ms", 5000));

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        } finally {
            conn.disconnect();
        }

        return response.toString();
    }

    private List<String> extractCidrLines(String content) {
        if (content == null || content.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        String trimmed = content.trim();

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            Pattern pattern = Pattern.compile("\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String candidate = matcher.group(1).trim();
                if (candidate.contains("/")) {
                    result.add(candidate);
                }
            }
            return result;
        }

        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("#") || !item.contains("/")) {
                continue;
            }
            result.add(item);
        }

        return result;
    }

    private void loadManualListsFromConfig() {
        synchronized (blacklistedIps) {
            blacklistedIps.clear();
            blacklistedIps.addAll(getConfig().getStringList("lists.blacklist"));
        }

        synchronized (whitelistedIps) {
            whitelistedIps.clear();
            whitelistedIps.addAll(getConfig().getStringList("lists.whitelist"));
        }
    }

    private void saveManualListsToConfig() {
        synchronized (blacklistedIps) {
            getConfig().set("lists.blacklist", new ArrayList<String>(blacklistedIps));
        }
        synchronized (whitelistedIps) {
            getConfig().set("lists.whitelist", new ArrayList<String>(whitelistedIps));
        }
        saveConfig();
    }

    private String resolveTargetIp(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }

        Player player = Bukkit.getPlayerExact(input);
        if (player != null && player.getAddress() != null && player.getAddress().getAddress() != null) {
            return player.getAddress().getAddress().getHostAddress();
        }

        String normalized = input.trim();
        try {
            InetAddress.getByName(normalized);
            return normalized;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("vpnblocker")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(args[0], listOf("reload", "check", "blacklist", "whitelist"));
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2) {
            if ("blacklist".equals(sub) || "whitelist".equals(sub)) {
                return filterByPrefix(args[1], listOf("add", "remove", "list"));
            }
        }

        if (args.length == 3) {
            if ("check".equals(sub)) {
                List<String> playerNames = new ArrayList<String>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    playerNames.add(p.getName());
                }
                return filterByPrefix(args[2], playerNames);
            }

            if ("blacklist".equals(sub) || "whitelist".equals(sub)) {
                String action = args[1].toLowerCase(Locale.ROOT);
                if ("add".equals(action)) {
                    List<String> playerNames = new ArrayList<String>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        playerNames.add(p.getName());
                    }
                    return filterByPrefix(args[2], playerNames);
                }

                if ("remove".equals(action)) {
                    Set<String> source = "blacklist".equals(sub) ? blacklistedIps : whitelistedIps;
                    synchronized (source) {
                        return filterByPrefix(args[2], new ArrayList<String>(source));
                    }
                }
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterByPrefix(String token, List<String> options) {
        String prefix = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }

        Collections.sort(matches);
        return matches;
    }

    private List<String> listOf(String... values) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, values);
        return list;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "=== VPNBlocker Commands ===");
        sender.sendMessage(ChatColor.GOLD + "/vpnblocker reload" + ChatColor.GRAY + " - Reload configuration");
        sender.sendMessage(ChatColor.GOLD + "/vpnblocker check <player|ip>" + ChatColor.GRAY + " - Check if a player or IP is a VPN");
        sender.sendMessage(ChatColor.GOLD + "/vpnblocker blacklist <add|remove|list> [player|ip]" + ChatColor.GRAY + " - Manage blacklist");
        sender.sendMessage(ChatColor.GOLD + "/vpnblocker whitelist <add|remove|list> [player|ip]" + ChatColor.GRAY + " - Manage whitelist");
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

    private static class CidrRange {
        private final byte[] network;
        private final int prefixLength;

        private CidrRange(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static CidrRange parse(String cidr) {
            if (cidr == null || !cidr.contains("/")) {
                return null;
            }
            try {
                String[] parts = cidr.trim().split("/", 2);
                InetAddress inetAddress = InetAddress.getByName(parts[0]);
                byte[] bytes = inetAddress.getAddress();
                int maxPrefix = bytes.length * 8;
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > maxPrefix) {
                    return null;
                }
                return new CidrRange(bytes, prefix);
            } catch (Exception e) {
                return null;
            }
        }

        boolean contains(String ip) {
            try {
                byte[] target = InetAddress.getByName(ip).getAddress();
                if (target.length != network.length) {
                    return false;
                }

                int fullBytes = prefixLength / 8;
                int remainingBits = prefixLength % 8;

                for (int i = 0; i < fullBytes; i++) {
                    if (target[i] != network[i]) {
                        return false;
                    }
                }

                if (remainingBits > 0) {
                    int mask = 0xFF << (8 - remainingBits);
                    if ((target[fullBytes] & mask) != (network[fullBytes] & mask)) {
                        return false;
                    }
                }

                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
