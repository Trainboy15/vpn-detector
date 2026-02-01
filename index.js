const express = require('express');
const { EventEmitter } = require('events');
const fs = require('fs');
const path = require('path');
const { updateVPNList, isVPN, getLastUpdated, getRangeCount, addToBlacklist, removeFromBlacklist, getBlacklist } = require('./vpnChecker');

const app = express();
const PORT = process.env.PORT || 3000;
const STATS_PATH = path.join(__dirname, 'stats.json');

// Stats tracking (merged from stats.js)
const statsEvents = new EventEmitter();
const stats = new Map();
let hasUnsavedChanges = false;
const SAVE_INTERVAL = 10000; // Save every 10 seconds if there are changes

function loadStatsFromDisk() {
  try {
    if (!fs.existsSync(STATS_PATH)) {
      return;
    }
    const raw = fs.readFileSync(STATS_PATH, 'utf8');
    if (!raw.trim()) {
      return;
    }
    const data = JSON.parse(raw);
    Object.entries(data).forEach(([serverId, ping]) => {
      // Handle both old format (array) and new format (single object)
      if (Array.isArray(ping)) {
        // Take the last ping from old format
        stats.set(serverId, ping[ping.length - 1]);
      } else {
        stats.set(serverId, ping);
      }
    });
  } catch (error) {
    console.error('Failed to load stats.json:', error.message);
  }
}

function saveStatsToDisk() {
  try {
    const json = JSON.stringify(Object.fromEntries(stats), null, 2);
    fs.writeFileSync(STATS_PATH, json, 'utf8');
    hasUnsavedChanges = false;
  } catch (error) {
    console.error('Failed to save stats.json:', error.message);
  }
}

// Periodic save timer - only saves if there are unsaved changes
setInterval(() => {
  if (hasUnsavedChanges) {
    saveStatsToDisk();
    console.log('Stats saved to disk');
  }
}, SAVE_INTERVAL);

// Middleware
app.use(express.json());

// Load stats from disk on startup
loadStatsFromDisk();

// Initialize VPN list on startup
let isInitialized = false;

async function initialize() {
  try {
    console.log('Initializing VPN detector...');
    await updateVPNList();
    isInitialized = true;
    console.log('VPN detector initialized successfully');
  } catch (error) {
    console.error('Failed to initialize VPN detector:', error.message);
    process.exit(1);
  }
}

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    initialized: isInitialized,
    lastUpdated: getLastUpdated(),
    rangeCount: getRangeCount()
  });
});

// Stats ping endpoint
app.post('/ping', (req, res) => {
  try {
    const { serverId, timestamp, ...metrics } = req.body || {};

    if (!serverId) {
      return res.status(400).json({ error: 'serverId is required' });
    }

    const ping = {
      serverId,
      timestamp: timestamp || Date.now(),
      ...metrics
    };

    // Store only the latest ping for each server (replaces previous)
    stats.set(serverId, ping);
    statsEvents.emit('ping', ping);
    hasUnsavedChanges = true; // Mark for periodic save

    res.json({ status: 'ok' });
  } catch (err) {
    res.status(400).json({ error: 'Invalid JSON' });
  }
});

/**
 * Validates IPv4 address format
 * @param {string} ip - IP address to validate
 * @returns {boolean} True if valid IPv4
 */
function isValidIPv4(ip) {
  const ipv4Regex = /^(\d{1,3}\.){3}\d{1,3}$/;
  if (!ipv4Regex.test(ip)) return false;
  // Check octets are 0-255
  return ip.split('.').every(octet => {
    const num = parseInt(octet, 10);
    return num >= 0 && num <= 255;
  });
}

/**
 * Validates IPv6 address format
 * @param {string} ip - IP address to validate
 * @returns {boolean} True if valid IPv6
 */
function isValidIPv6(ip) {
  // Basic IPv6 validation - must contain colons and valid hex characters
  const ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/;
  return ipv6Regex.test(ip);
}

// Check IP endpoint
app.get('/check/:ip', (req, res) => {
  if (!isInitialized) {
    return res.status(503).json({ error: 'Service not initialized' });
  }

  const { ip } = req.params;

  // Determine IP version and validate
  const isIPv6 = ip.includes(':');
  const isValid = isIPv6 ? isValidIPv6(ip) : isValidIPv4(ip);

  if (!isValid) {
    return res.status(400).json({ error: 'Invalid IP address format' });
  }

  try {
    const result = isVPN(ip);
    res.json({
      ip: ip,
      isVPN: result,
      ipVersion: isIPv6 ? 6 : 4,
      checkedAt: new Date().toISOString()
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Refresh VPN list endpoint
app.post('/refresh', async (req, res) => {
  try {
    await updateVPNList();
    res.json({
      success: true,
      lastUpdated: getLastUpdated(),
      rangeCount: getRangeCount()
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Blacklist endpoints
app.post('/blacklist/add', (req, res) => {
  try {
    const { ip } = req.body;
    if (!ip) {
      return res.status(400).json({ error: 'ip is required' });
    }
    addToBlacklist(ip);
    hasUnsavedChanges = true;
    res.json({ status: 'ok', message: `IP ${ip} added to blacklist` });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.post('/blacklist/remove', (req, res) => {
  try {
    const { ip } = req.body;
    if (!ip) {
      return res.status(400).json({ error: 'ip is required' });
    }
    removeFromBlacklist(ip);
    hasUnsavedChanges = true;
    res.json({ status: 'ok', message: `IP ${ip} removed from blacklist` });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.get('/blacklist', (req, res) => {
  res.json({
    blacklist: getBlacklist(),
    count: getBlacklist().length
  });
});

// Root endpoint
app.get('/', (req, res) => {
  res.json({
    name: 'VPN Detector API',
    description: 'API to check if an IP is associated with a VPN network',
    endpoints: {
      health: 'GET /health - Check API health',
      check: 'GET /check/:ip - Check if an IP is a VPN',
      refresh: 'POST /refresh - Refresh the VPN list'
    }
  });
});

// Start server
initialize().then(() => {
  app.listen(PORT, () => {
    console.log(`VPN Detector API listening on port ${PORT}`);
  });
});

// Graceful shutdown - save stats before exit
process.on('SIGINT', () => {
  console.log('\nShutting down gracefully...');
  if (hasUnsavedChanges) {
    saveStatsToDisk();
    console.log('Stats saved to disk');
  }
  process.exit(0);
});

process.on('SIGTERM', () => {
  console.log('\nShutting down gracefully...');
  if (hasUnsavedChanges) {
    saveStatsToDisk();
    console.log('Stats saved to disk');
  }
  process.exit(0);
});

module.exports = app;
