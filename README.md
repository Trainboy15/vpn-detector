# vpn-detector

This API uses the [X4BNet VPN list](https://github.com/X4BNet/lists_vpn) and [misp-warninglists](https://github.com/MISP/misp-warninglists/blob/main/lists/vpn-ipv6/list.json) to check if a given IP address belongs to a known VPN network.

## Features

- Check if an IP address is associated with a VPN
- Automatically downloads and caches the X4BNet VPN list
- Works with IPv4 & IPv6 addresses.
## Uses

This VPN detector can be integrated into various applications and services:

- **Anti-VPN Plugins**: Protect your Minecraft servers by blocking VPN users
  - Included in this plugin!
  - Minecraft server plugins (Spigot, Paper, Bukkit)

## Installation
Move VPNBlocker-x.x.x.jar to `/server/plugins/`

## Data Source

This API uses the VPN IP list from [X4BNet/lists_vpn](https://github.com/X4BNet/lists_vpn/blob/main/ipv4.txt) and [misp-warninglists] (https://github.com/MISP/misp-warninglists/blob/main/lists/vpn-ipv6/list.json).

## License

ISC

