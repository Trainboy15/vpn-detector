const axios = require('axios');
const IPCIDR = require('ip-cidr').default;

const VPN_IPV4_LIST_URL = 'https://raw.githubusercontent.com/X4BNet/lists_vpn/main/ipv4.txt';
const VPN_IPV6_LIST_URL = 'https://raw.githubusercontent.com/MISP/misp-warninglists/main/lists/vpn-ipv6/list.json';
let vpnRangesIPv4 = [];
let vpnRangesIPv6 = [];
let lastUpdated = null;
let blacklistedIPs = new Set(['163.116.254.42']); // Store blacklisted IPs

/**
 * Fetches the IPv4 VPN IP list from X4BNet repository
 * @returns {Promise<string[]>} Array of CIDR ranges
 */
async function fetchVPNListIPv4() {
  try {
    const response = await axios.get(VPN_IPV4_LIST_URL);
    return response.data.split('\n').filter(line => line.trim() !== '');
  } catch (error) {
    console.error('Error fetching IPv4 VPN list:', error.message);
    throw new Error('Failed to fetch IPv4 VPN list');
  }
}

/**
 * Fetches the IPv6 VPN IP list from MISP warninglists
 * @returns {Promise<string[]>} Array of CIDR ranges
 */
async function fetchVPNListIPv6() {
  try {
    const response = await axios.get(VPN_IPV6_LIST_URL);
    // Extract the list from MISP JSON format
    const list = response.data.list || [];
    return list.filter(range => range && range.trim() !== '');
  } catch (error) {
    console.error('Error fetching IPv6 VPN list:', error.message);
    throw new Error('Failed to fetch IPv6 VPN list');
  }
}

/**
 * Updates the VPN ranges cache for both IPv4 and IPv6
 */
async function updateVPNList() {
  try {
    const [ipv4, ipv6] = await Promise.all([
      fetchVPNListIPv4(),
      fetchVPNListIPv6()
    ]);
    vpnRangesIPv4 = ipv4;
    vpnRangesIPv6 = ipv6;
    lastUpdated = new Date();
    console.log(`VPN list updated: ${vpnRangesIPv4.length} IPv4 ranges and ${vpnRangesIPv6.length} IPv6 ranges loaded`);
  } catch (error) {
    console.error('Failed to update VPN list:', error.message);
    throw error;
  }
}

/**
 * Checks if an IPv4 address is in the VPN list
 * @param {string} ip - The IPv4 address to check
 * @returns {boolean} True if the IP is in a VPN range
 */
function isVPNIPv4(ip) {
  if (vpnRangesIPv4.length === 0) {
    throw new Error('IPv4 VPN list not loaded');
  }

  for (const range of vpnRangesIPv4) {
    try {
      const cidr = new IPCIDR(range);
      if (cidr.contains(ip)) {
        return true;
      }
    } catch (error) {
      // Skip invalid CIDR ranges
      continue;
    }
  }
  return false;
}

/**
 * Checks if an IPv6 address is in the VPN list
 * @param {string} ip - The IPv6 address to check
 * @returns {boolean} True if the IP is in a VPN range
 */
function isVPNIPv6(ip) {
  if (vpnRangesIPv6.length === 0) {
    throw new Error('IPv6 VPN list not loaded');
  }

  for (const range of vpnRangesIPv6) {
    try {
      const cidr = new IPCIDR(range);
      if (cidr.contains(ip)) {
        return true;
      }
    } catch (error) {
      // Skip invalid CIDR ranges
      continue;
    }
  }
  return false;
}

/**
 * Checks if an IP address (IPv4 or IPv6) is in the VPN list
 * @param {string} ip - The IP address to check
 * @returns {boolean} True if the IP is in a VPN range
 */
function isVPN(ip) {
  // Check blacklist first
  if (blacklistedIPs.has(ip)) {
    return true;
  }

  // Detect if IPv6 (contains colons)
  if (ip.includes(':')) {
    return isVPNIPv6(ip);
  }
  return isVPNIPv4(ip);
}

/**
 * Gets the last update time of the VPN list
 * @returns {Date|null} Last update timestamp
 */
function getLastUpdated() {
  return lastUpdated;
}

/**
 * Gets the number of VPN ranges loaded
 * @returns {object} Number of IPv4 and IPv6 ranges
 */
function getRangeCount() {
  return {
    ipv4: vpnRangesIPv4.length,
    ipv6: vpnRangesIPv6.length,
    total: vpnRangesIPv4.length + vpnRangesIPv6.length
  };
}

/**
 * Adds an IP to the blacklist
 * @param {string} ip - IP address to blacklist
 */
function addToBlacklist(ip) {
  blacklistedIPs.add(ip);
}

/**
 * Removes an IP from the blacklist
 * @param {string} ip - IP address to remove from blacklist
 */
function removeFromBlacklist(ip) {
  blacklistedIPs.delete(ip);
}

/**
 * Gets the blacklisted IPs
 * @returns {string[]} Array of blacklisted IPs
 */
function getBlacklist() {
  return Array.from(blacklistedIPs);
}

module.exports = {
  updateVPNList,
  isVPN,
  getLastUpdated,
  getRangeCount,
  addToBlacklist,
  removeFromBlacklist,
  getBlacklist
};
