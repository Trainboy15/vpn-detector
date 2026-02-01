const express = require('express');
const { EventEmitter } = require('events');
const fs = require('fs');
const path = require('path');
const { updateVPNList, isVPN, getLastUpdated, getRangeCount } = require('./vpnChecker');

const app = express();
const PORT = process.env.PORT || 3000;
const STATS_PATH = path.join(__dirname, 'stats.json');

// Stats tracking (merged from stats.js)
const statsEvents = new EventEmitter();
const stats = new Map();

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
    Object.entries(data).forEach(([serverId, pings]) => {
      if (Array.isArray(pings)) {
        stats.set(serverId, pings);
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
  } catch (error) {
    console.error('Failed to save stats.json:', error.message);
  }
}

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

    if (!stats.has(serverId)) {
      stats.set(serverId, []);
    }

    const ping = {
      serverId,
      timestamp: timestamp || Date.now(),
      ...metrics
    };

    stats.get(serverId).push(ping);
    statsEvents.emit('ping', ping);
    saveStatsToDisk();

    res.json({ status: 'ok' });
  } catch (err) {
    res.status(400).json({ error: 'Invalid JSON' });
  }
});

// Check IP endpoint
app.get('/check/:ip', (req, res) => {
  if (!isInitialized) {
    return res.status(503).json({ error: 'Service not initialized' });
  }

  const { ip } = req.params;

  // Basic IP validation
  const ipRegex = /^(\d{1,3}\.){3}\d{1,3}$/;
  if (!ipRegex.test(ip)) {
    return res.status(400).json({ error: 'Invalid IP address format' });
  }

  try {
    const result = isVPN(ip);
    res.json({
      ip: ip,
      isVPN: result,
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

module.exports = app;
