# 🩸 Blood Bank & Emergency Routing System — Java Backend & JDBC (Member 3)

> **Course:** BACSE202 — Database Systems (Phase 2 & 3 Implementation)  
> **Component:** Member 3 — Java Backend, JDBC & REST API Engine  
> **Server Port:** `8080` | **Frontend URL:** `http://localhost:5173` | **CORS:** Enabled

---

## 📋 Architecture & Role Summary (Member 3)

Member 3 is responsible for **connecting the frontend with Oracle SQL** via Java JDBC:
1. **Oracle Database Connectivity:** Configured via Oracle JDBC Driver (`ojdbc8`).
2. **Authentication Engine:** Supports Citizen and Hospital Staff sign-up/login with SHA-256 password hashing.
3. **Hospital-Scoped Inventory:** Hospital staff access only their hospital's inventory; citizens/admins access global views.
4. **Geolocation Routing:** Haversine distance engine and Pincode resolver for nearest hospital matching.
5. **4-Table FEFO Emergency Transaction:** Atomically updates `REQUESTS`, `BLOOD_BAGS`, `ALLOCATIONS`, and `BAG_STATUS_LOG`.
6. **CORS Configuration:** Fully configured with `Access-Control-Allow-Credentials: true` for React (Vite: `5173`).

---

## 🚀 How to Run the Java Backend

### Method 1: IntelliJ IDEA / Eclipse (Recommended for Students)
1. Open IntelliJ or Eclipse.
2. Select **Open / Import Project** $\to$ choose `blood-bank-backend/`.
3. Open `src/main/java/com/bloodbank/server/BloodBankServer.java`.
4. Right-click and choose **Run 'BloodBankServer.main()'**.
5. The console will display:
   ```text
   =================================================================
    BLOOD BANK & EMERGENCY ROUTING BACKEND (JDBC / REST API)
    Server successfully running on: http://localhost:8080
    CORS configured for React Frontend: http://localhost:5173
   =================================================================
   ```

### Method 2: Maven Terminal
```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="com.bloodbank.server.BloodBankServer"
```

---

## 📡 API Contract for Frontend Integration (Member 2 Hand-off)

All responses follow the unified standard response envelope:
```json
{
  "success": true,
  "message": "Human-readable status message",
  "data": { ... },
  "timestamp": 1728987654321
}
```

### 1. Authentication Endpoints

#### `POST /api/auth/login`
- **Request Body:**
  ```json
  {
    "email": "staff.central@cityhospital.org",
    "password": "password123"
  }
  ```
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "message": "Login successful. Welcome back, Dr. Ramesh Kumar!",
    "data": {
      "user_id": "USR-002",
      "email": "staff.central@cityhospital.org",
      "role": "HOSPITAL_STAFF",
      "full_name": "Dr. Ramesh Kumar",
      "phone": "+919811223344",
      "hospital_id": "HOSP-01",
      "hospital_name": "City Central Blood Bank & Hospital",
      "token": "TOKEN-3f82a4c1-..."
    }
  }
  ```

#### `POST /api/auth/register`
- **Request Body (Citizen):**
  ```json
  {
    "full_name": "Karthik Verma",
    "email": "karthik@sample.com",
    "password": "password123",
    "phone": "+919988776655",
    "role": "CITIZEN"
  }
  ```
- **Request Body (Hospital Staff):**
  ```json
  {
    "full_name": "Dr. Sunita Rao",
    "email": "sunita@cityhospital.org",
    "password": "password123",
    "phone": "+919122334455",
    "role": "HOSPITAL_STAFF",
    "hospital_id": "HOSP-01"
  }
  ```

---

### 2. Geolocation & Nearby Hospital Search

#### `GET /api/hospitals/nearby?pincode=560001&radius=25`
- **Query Parameters:**
  - `pincode` *(optional)*: Indian PIN code (e.g. `560001`, `560034`, `632014` for VIT)
  - `lat`, `lon` *(optional)*: Direct browser GPS coordinates
  - `radius` *(optional, default 25)*: Search radius in kilometers
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "message": "Found 3 hospitals within 25.0 km.",
    "data": {
      "search_pincode": "560001",
      "radius_km": 25.0,
      "total_found": 3,
      "hospitals": [
        {
          "hospital_id": "HOSP-01",
          "name": "City Central Blood Bank & Hospital",
          "latitude": 12.971598,
          "longitude": 77.594562,
          "address": "102 MG Road, Central District, Bangalore",
          "phone": "+918023456789",
          "available_units": 48,
          "distance_km": 0.0
        },
        {
          "hospital_id": "HOSP-02",
          "name": "St. Jude Care Multispecialty",
          "latitude": 12.93444,
          "longitude": 77.61923,
          "address": "45 80-Feet Road, Koramangala 4th Block, Bangalore",
          "phone": "+918098765432",
          "available_units": 32,
          "distance_km": 4.95
        }
      ]
    }
  }
  ```

---

### 3. Inventory Dashboard

#### `GET /api/inventory/dashboard` *(Global)* or `?hospitalId=HOSP-01` *(Staff Scoped)*
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "message": "Live inventory retrieved for hospital: HOSP-01",
    "data": {
      "filter_hospital_id": "HOSP-01",
      "total_units": 8,
      "available_units": 8,
      "reserved_units": 0,
      "expiring_within_7_days": 2,
      "group_breakdown": {
        "A+": 1, "A-": 1, "B+": 1, "B-": 1,
        "AB+": 1, "AB-": 1, "O+": 1, "O-": 1
      },
      "inventory_items": [
        {
          "bag_id": "BAG-101",
          "blood_group": "O",
          "rh_factor": "+",
          "blood_type": "O+",
          "collection_date": "2026-10-04",
          "expiry_date": "2026-11-15",
          "days_to_expiry": 32,
          "status": "AVAILABLE",
          "hospital_id": "HOSP-01",
          "hospital_name": "City Central Blood Bank & Hospital",
          "hospital_phone": "+918023456789",
          "donor_id": "D-01"
        }
      ]
    }
  }
  ```

---

### 4. Emergency Request (4-Table FEFO Transaction)

#### `POST /api/requests/emergency`
- **Request Body:**
  ```json
  {
    "hospital_id": "HOSP-01",
    "blood_group": "O",
    "rh_factor": "+",
    "units_requested": 2,
    "priority": "CRITICAL"
  }
  ```
- **Response (`200 OK`):**
  ```json
  {
    "success": true,
    "message": "All 2 units successfully allocated via FEFO matching.",
    "data": {
      "request_id": "REQ-87391",
      "hospital_id": "HOSP-01",
      "blood_group": "O",
      "rh_factor": "+",
      "units_requested": 2,
      "units_allocated": 2,
      "priority": "CRITICAL",
      "status": "FULFILLED",
      "allocated_bags": ["BAG-101", "BAG-203"],
      "message": "All 2 units successfully allocated via FEFO matching.",
      "timestamp": "2026-10-14T21:30:00"
    }
  }
  ```

---

### 5. Donor Registration & Blood Search

#### `POST /api/donors/register`
- **Request Body:**
  ```json
  {
    "full_name": "Vikram Aditya",
    "blood_group": "O",
    "rh_factor": "-",
    "age": 24,
    "gender": "M",
    "contact_number": "+919876543219",
    "last_donation_date": "2026-06-15"
  }
  ```

#### `GET /api/blood/search?bloodGroup=O&rhFactor=%2B&pincode=560001`
- Returns hospitals having units in stock, sorted by nearest distance!

---

## 🧪 Postman Testing (Phase 2 & 3 Viva Deliverable)

1. Open **Postman** desktop app.
2. Click **Import** $\to$ choose `blood-bank-backend/postman_collection.json`.
3. All 10 pre-configured requests will load across 5 organized folders.
4. Execute each request against `http://localhost:8080` and capture screenshots of:
   - Status code `200 OK` / `201 Created`
   - Response time
   - Formatted JSON response body
