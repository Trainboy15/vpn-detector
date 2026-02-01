const express = require('express');
const { updateVPNList, isVPN, getLastUpdated, getRangeCount } = require('./vpnChecker');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(express.json());

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
