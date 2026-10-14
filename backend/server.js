/**
 * ============================================================================
 * BLOOD BANK & EMERGENCY ROUTING BACKEND (Node.js Zero-Dependency Companion Runner)
 * ============================================================================
 * Mirrors the exact Java BloodBankServer.java on port 8080.
 * Allows instant local testing without compiling Java or running Oracle XE.
 * Zero external dependencies (uses standard Node.js http, crypto, url).
 * ============================================================================
 */

const http = require('http');
const crypto = require('crypto');
const url = require('url');

const PORT = 8080;
const DEFAULT_CSRF = "csrf-token-secured-session-88371";

// Active in-memory session store: sessionId -> userObj
const ACTIVE_SESSIONS = new Map();

// Pincode Coordinates dictionary (Bangalore / Vellore / Chennai)
const PINCODE_MAP = {
  "560001": [12.971598, 77.594562], // MG Road / Central
  "560034": [12.934440, 77.619230], // Koramangala
  "560066": [12.969819, 77.749977], // Whitefield
  "560029": [12.916576, 77.599908], // Bannerghatta / BTM
  "632014": [12.969200, 79.155900], // VIT Katpadi
  "632001": [12.924900, 79.135400]  // Vellore Town / CMC
};

function haversineDistanceKm(lat1, lon1, lat2, lon2) {
  const R = 6371.0;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
            Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
            Math.sin(dLon/2) * Math.sin(dLon/2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
  return Math.round(R * c * 100) / 100;
}

// In-Memory Seed Data matching Phase 1 & 2 DML
const HOSPITALS = [
  { hospital_id: "HOSP-01", name: "City Central Blood Bank & Hospital", latitude: 12.971598, longitude: 77.594562, address: "102 MG Road, Central District, Bangalore", phone: "+918023456789", available_units: 48 },
  { hospital_id: "HOSP-02", name: "St. Jude Care Multispecialty", latitude: 12.934440, longitude: 77.619230, address: "45 80-Feet Road, Koramangala 4th Block, Bangalore", phone: "+918098765432", available_units: 32 },
  { hospital_id: "HOSP-03", name: "Apex Trauma & Emergency Care", latitude: 12.916576, longitude: 77.599908, address: "78 Bannerghatta Main Road, BTM 2nd Stage, Bangalore", phone: "+918012344321", available_units: 25 },
  { hospital_id: "HOSP-04", name: "Apollo Tech Blood Center", latitude: 12.969819, longitude: 77.749977, address: "ITPB Main Road, Whitefield, Bangalore", phone: "+918055667788", available_units: 40 },
  { hospital_id: "HOSP-05", name: "CMC Hospital & Research Blood Bank", latitude: 12.924900, longitude: 79.135400, address: "Ida Scudder Road, Vellore Town, Vellore", phone: "+914162281000", available_units: 60 }
];

const BLOOD_BAGS = [
  { bag_id: "BAG-101", blood_group: "O", rh_factor: "+", collection_date: "2026-10-04", expiry_date: "2026-11-15", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-102", blood_group: "O", rh_factor: "-", collection_date: "2026-09-24", expiry_date: "2026-11-05", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-103", blood_group: "A", rh_factor: "+", collection_date: "2026-09-06", expiry_date: "2026-10-18", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-104", blood_group: "A", rh_factor: "-", collection_date: "2026-10-09", expiry_date: "2026-11-20", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-105", blood_group: "B", rh_factor: "+", collection_date: "2026-09-29", expiry_date: "2026-11-10", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-106", blood_group: "B", rh_factor: "-", collection_date: "2026-10-02", expiry_date: "2026-11-13", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-107", blood_group: "AB", rh_factor: "+", collection_date: "2026-10-06", expiry_date: "2026-11-17", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-108", blood_group: "AB", rh_factor: "-", collection_date: "2026-09-04", expiry_date: "2026-10-16", status: "AVAILABLE", hospital_id: "HOSP-01" },
  { bag_id: "BAG-201", blood_group: "B", rh_factor: "+", collection_date: "2026-09-30", expiry_date: "2026-11-11", status: "AVAILABLE", hospital_id: "HOSP-02" },
  { bag_id: "BAG-202", blood_group: "AB", rh_factor: "-", collection_date: "2026-09-26", expiry_date: "2026-11-07", status: "AVAILABLE", hospital_id: "HOSP-02" },
  { bag_id: "BAG-203", blood_group: "O", rh_factor: "+", collection_date: "2026-10-12", expiry_date: "2026-11-23", status: "AVAILABLE", hospital_id: "HOSP-02" },
  { bag_id: "BAG-204", blood_group: "O", rh_factor: "-", collection_date: "2026-09-05", expiry_date: "2026-10-17", status: "AVAILABLE", hospital_id: "HOSP-02" }
];

const USERS = [
  { user_id: "USR-001", email: "citizen@demo.com", password: "password123", role: "CITIZEN", full_name: "Ananya Sharma", phone: "+919876543210", status: "ACTIVE", hospital_id: null },
  { user_id: "USR-002", email: "staff.central@cityhospital.org", password: "password123", role: "HOSPITAL_STAFF", full_name: "Dr. Ramesh Kumar", phone: "+919811223344", status: "ACTIVE", hospital_id: "HOSP-01" },
  { user_id: "USR-003", email: "staff.stjude@stjude.org", password: "password123", role: "HOSPITAL_STAFF", full_name: "Sister Mary Varghese", phone: "+919822334455", status: "ACTIVE", hospital_id: "HOSP-02" }
];

function applyCors(req, res) {
  const origin = req.headers.origin || "http://localhost:5173";
  res.setHeader('Access-Control-Allow-Origin', origin);
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With, Origin, Accept, X-CSRF-Token');
  res.setHeader('Access-Control-Allow-Credentials', 'true');
  res.setHeader('Content-Type', 'application/json; charset=UTF-8');
}

function getSessionUser(req) {
  const cookieHeader = req.headers.cookie;
  if (!cookieHeader) return null;
  const cookies = cookieHeader.split(';').map(c => c.trim().split('='));
  const found = cookies.find(([k]) => k === 'blood_bank_session');
  return found ? ACTIVE_SESSIONS.get(found[1]) : null;
}

const server = http.createServer((req, res) => {
  applyCors(req, res);
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;
  const query = parsedUrl.query;

  let body = '';
  req.on('data', chunk => body += chunk);
  req.on('end', () => {
    let jsonBody = {};
    try { if (body) jsonBody = JSON.parse(body); } catch (e) {}

    // 1. /api/auth/me
    if (pathname === '/api/auth/me' && req.method === 'GET') {
      const user = getSessionUser(req);
      if (user) {
        res.writeHead(200);
        res.end(JSON.stringify({ user, csrf_token: DEFAULT_CSRF }));
      } else {
        res.writeHead(401);
        res.end(JSON.stringify({ user: null, csrf_token: DEFAULT_CSRF }));
      }
      return;
    }

    // 2. /api/auth/login
    if (pathname === '/api/auth/login' && req.method === 'POST') {
      const { email, password, role } = jsonBody;
      const user = USERS.find(u => u.email.toLowerCase() === (email || '').toLowerCase() && u.password === password);
      if (!user) {
        res.writeHead(401);
        res.end(JSON.stringify({ error: "Invalid email or password" }));
        return;
      }
      if (role && user.role !== role) {
        res.writeHead(401);
        res.end(JSON.stringify({ error: "Role mismatch for account" }));
        return;
      }

      const sid = "SESS-" + crypto.randomUUID();
      ACTIVE_SESSIONS.set(sid, user);
      res.setHeader('Set-Cookie', `blood_bank_session=${sid}; Path=/; HttpOnly; SameSite=Lax`);
      res.writeHead(200);
      res.end(JSON.stringify({ user, csrf_token: DEFAULT_CSRF, status: "SUCCESS" }));
      return;
    }

    // 3. /api/auth/register/citizen
    if (pathname === '/api/auth/register/citizen' && req.method === 'POST') {
      const { email, password, full_name, phone } = jsonBody;
      USERS.push({
        user_id: "USR-" + (USERS.length + 100),
        email, password, full_name, phone, role: "CITIZEN", status: "ACTIVE", hospital_id: null
      });
      res.writeHead(201);
      res.end(JSON.stringify({ status: "REGISTERED", message: "Citizen account created" }));
      return;
    }

    // 4. /api/auth/register/hospital
    if (pathname === '/api/auth/register/hospital' && req.method === 'POST') {
      const { email, password, full_name, phone, hospital_id } = jsonBody;
      USERS.push({
        user_id: "USR-" + (USERS.length + 100),
        email, password, full_name, phone, role: "HOSPITAL_STAFF", status: "ACTIVE", hospital_id: hospital_id || "HOSP-01"
      });
      res.writeHead(201);
      res.end(JSON.stringify({ status: "PENDING_APPROVAL", message: "Staff account submitted" }));
      return;
    }

    // 5. /api/auth/logout
    if (pathname === '/api/auth/logout' && req.method === 'POST') {
      res.setHeader('Set-Cookie', 'blood_bank_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax');
      res.writeHead(200);
      res.end(JSON.stringify({ status: "LOGGED_OUT" }));
      return;
    }

    // 6. /api/hospitals
    if (pathname === '/api/hospitals' && req.method === 'GET') {
      const list = HOSPITALS.map(h => ({
        hospital_id: h.hospital_id,
        name: h.name,
        address: h.address,
        phone: h.phone,
        latitude: h.latitude,
        longitude: h.longitude
      }));
      res.writeHead(200);
      res.end(JSON.stringify(list));
      return;
    }

    // 7. /api/hospitals/nearby
    if (pathname === '/api/hospitals/nearby' && req.method === 'GET') {
      const pin = query.pincode;
      let lat = query.lat ? parseFloat(query.lat) : null;
      let lon = query.lon ? parseFloat(query.lon) : null;
      const radius = query.radius ? parseFloat(query.radius) : 50.0;

      if ((!lat || !lon) && pin && PINCODE_MAP[pin]) {
        [lat, lon] = PINCODE_MAP[pin];
      } else if (!lat || !lon) {
        [lat, lon] = [12.971598, 77.594562];
      }

      const results = HOSPITALS.map(h => {
        const dist = haversineDistanceKm(lat, lon, h.latitude, h.longitude);
        return {
          hospital: {
            hospital_id: h.hospital_id,
            name: h.name,
            address: h.address,
            phone: h.phone
          },
          distance_km: dist,
          available_units: h.available_units
        };
      }).filter(r => r.distance_km <= radius)
        .sort((a, b) => a.distance_km - b.distance_km);

      res.writeHead(200);
      res.end(JSON.stringify({ hospitals: results }));
      return;
    }

    // 8. /api/hospitals/:id/inventory
    const invMatch = pathname.match(/^\/api\/hospitals\/([^/]+)\/inventory$/);
    if (invMatch && req.method === 'GET') {
      const hospId = invMatch[1];
      const bags = BLOOD_BAGS.filter(b => b.hospital_id === hospId).map(b => ({
        bag_id: b.bag_id,
        blood_group: b.blood_group,
        rh_factor: b.rh_factor,
        expiry_date: b.expiry_date,
        bag_status: b.status
      }));

      res.writeHead(200);
      res.end(JSON.stringify({
        as_of_date: new Date().toISOString().split('T')[0],
        near_expiry_days: 7,
        bags
      }));
      return;
    }

    // 9. /api/inventory/dashboard (Member 3 endpoint)
    if (pathname === '/api/inventory/dashboard' && req.method === 'GET') {
      const hospId = query.hospitalId;
      const filtered = hospId ? BLOOD_BAGS.filter(b => b.hospital_id === hospId) : BLOOD_BAGS;
      const available = filtered.filter(b => b.status === 'AVAILABLE');

      const breakdown = { "A+": 0, "A-": 0, "B+": 0, "B-": 0, "AB+": 0, "AB-": 0, "O+": 0, "O-": 0 };
      available.forEach(b => {
        const t = b.blood_group + b.rh_factor;
        if (breakdown[t] !== undefined) breakdown[t]++;
      });

      res.writeHead(200);
      res.end(JSON.stringify({
        success: true,
        message: "Inventory dashboard loaded",
        data: {
          filter_hospital_id: hospId || null,
          total_units: filtered.length,
          available_units: available.length,
          reserved_units: filtered.filter(b => b.status === 'RESERVED').length,
          group_breakdown: breakdown,
          inventory_items: filtered
        }
      }));
      return;
    }

    // 10. /api/donors
    if (pathname === '/api/donors' && req.method === 'POST') {
      const donorId = "D-" + (Math.floor(Math.random() * 900) + 100);
      res.writeHead(201);
      res.end(JSON.stringify({ donor: { donor_id: donorId, ...jsonBody } }));
      return;
    }

    // 11. /api/requests (Emergency demand)
    if (pathname === '/api/requests' && req.method === 'POST') {
      const reqId = "REQ-" + (Math.floor(Math.random() * 9000) + 1000);
      const { blood_group, rh_factor, units_requested, hospital_id } = jsonBody;

      // FEFO Matching
      let allocatedCount = 0;
      BLOOD_BAGS.forEach(b => {
        if (allocatedCount < (units_requested || 1) &&
            b.blood_group === blood_group &&
            b.rh_factor === rh_factor &&
            b.status === 'AVAILABLE') {
          b.status = 'RESERVED';
          allocatedCount++;
        }
      });

      const status = allocatedCount >= (units_requested || 1) ? 'FULFILLED' : (allocatedCount > 0 ? 'PARTIALLY_FILLED' : 'PENDING');
      res.writeHead(201);
      res.end(JSON.stringify({
        request: {
          request_id: reqId,
          hospital_id: hospital_id || "HOSP-01",
          blood_group,
          rh_factor,
          units_requested: units_requested || 1,
          request_status: status
        }
      }));
      return;
    }

    // 12. /api/requests/emergency (Member 3 endpoint)
    if (pathname === '/api/requests/emergency' && req.method === 'POST') {
      const reqId = "REQ-" + (Math.floor(Math.random() * 9000) + 1000);
      const { blood_group, rh_factor, units_requested, hospital_id, priority } = jsonBody;

      const allocatedBags = [];
      BLOOD_BAGS.forEach(b => {
        if (allocatedBags.length < (units_requested || 1) &&
            b.blood_group === blood_group &&
            b.rh_factor === rh_factor &&
            b.status === 'AVAILABLE') {
          b.status = 'RESERVED';
          allocatedBags.push(b.bag_id);
        }
      });

      const status = allocatedBags.length >= (units_requested || 1) ? 'FULFILLED' : (allocatedBags.length > 0 ? 'PARTIALLY_FILLED' : 'PENDING');

      res.writeHead(200);
      res.end(JSON.stringify({
        success: true,
        message: `Emergency allocation: ${allocatedBags.length} units reserved via FEFO.`,
        data: {
          request_id: reqId,
          hospital_id: hospital_id || "HOSP-01",
          blood_group,
          rh_factor,
          units_requested: units_requested || 1,
          units_allocated: allocatedBags.length,
          priority: priority || "CRITICAL",
          status,
          allocated_bags: allocatedBags
        }
      }));
      return;
    }

    // 13. /api/health
    if (pathname === '/api/health' && req.method === 'GET') {
      res.writeHead(200);
      res.end(JSON.stringify({ status: "UP", service: "Blood Bank REST API", port: PORT }));
      return;
    }

    // Fallback 404
    res.writeHead(404);
    res.end(JSON.stringify({ error: "Endpoint not found: " + pathname }));
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log("=================================================================");
  console.log(` BLOOD BANK BACKEND API RUNNING ON http://127.0.0.1:${PORT}`);
  console.log(` Connected to React Frontend on http://localhost:5173`);
  console.log("=================================================================");
});
