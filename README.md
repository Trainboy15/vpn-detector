# vpn-detector

An API to see if an IP is associated with a VPN network.

This API uses the [X4BNet VPN list](https://github.com/X4BNet/lists_vpn) to check if a given IP address belongs to a known VPN network.

## Features

- Check if an IP address is associated with a VPN
- Automatically downloads and caches the X4BNet VPN list
- Simple REST API
- Health check endpoint

## Installation

```bash
npm install
```

## Usage

Start the server:

```bash
npm start
```

The API will be available at `http://localhost:3000` (or the port specified in the `PORT` environment variable).

## API Endpoints

### GET /

Get API information and available endpoints.

### GET /health

Check the API health and status.

**Response:**
```json
{
  "status": "ok",
  "initialized": true,
  "lastUpdated": "2026-02-01T10:00:00.000Z",
  "rangeCount": 12345
}
```

### GET /check/:ip

Check if an IP address is associated with a VPN.

**Example:**
```bash
curl http://localhost:3000/check/1.12.0.1
```

**Response:**
```json
{
  "ip": "1.12.0.1",
  "isVPN": true,
  "checkedAt": "2026-02-01T10:00:00.000Z"
}
```

### POST /refresh

Manually refresh the VPN list from the source.

**Example:**
```bash
curl -X POST http://localhost:3000/refresh
```

**Response:**
```json
{
  "success": true,
  "lastUpdated": "2026-02-01T10:00:00.000Z",
  "rangeCount": 12345
}
```

## Data Source

This API uses the VPN IP list from [X4BNet/lists_vpn](https://github.com/X4BNet/lists_vpn/blob/main/ipv4.txt).

## License

ISC

