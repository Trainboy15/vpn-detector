const axios = require('axios');
const IPCIDR = require('ip-cidr').default;

const VPN_LIST_URL = 'https://raw.githubusercontent.com/X4BNet/lists_vpn/main/ipv4.txt';
let vpnRanges = [];
let lastUpdated = null;

/**
 * Fetches the VPN IP list from X4BNet repository
 * @returns {Promise<string[]>} Array of CIDR ranges
 */
async function fetchVPNList() {
  try {
    const response = await axios.get(VPN_LIST_URL);
    return response.data.split('\n').filter(line => line.trim() !== '');
  } catch (error) {
    console.error('Error fetching VPN list:', error.message);
    throw new Error('Failed to fetch VPN list');
  }
}

/**
 * Updates the VPN ranges cache
 */
async function updateVPNList() {
  try {
    vpnRanges = await fetchVPNList();
    lastUpdated = new Date();
    console.log(`VPN list updated: ${vpnRanges.length} ranges loaded`);
  } catch (error) {
    console.error('Failed to update VPN list:', error.message);
    throw error;
  }
}

/**
 * Checks if an IP address is in the VPN list
 * @param {string} ip - The IP address to check
 * @returns {boolean} True if the IP is in a VPN range
 */
function isVPN(ip) {
  if (vpnRanges.length === 0) {
    throw new Error('VPN list not loaded');
  }

  for (const range of vpnRanges) {
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
 * Gets the last update time of the VPN list
 * @returns {Date|null} Last update timestamp
 */
function getLastUpdated() {
  return lastUpdated;
}

/**
 * Gets the number of VPN ranges loaded
 * @returns {number} Number of ranges
 */
function getRangeCount() {
  return vpnRanges.length;
}

module.exports = {
  updateVPNList,
  isVPN,
  getLastUpdated,
  getRangeCount
};
