# Centralized Blood Bank & Emergency Routing — Frontend

React, Vite, JavaScript and plain CSS. The existing Citizen Portal and Hospital Command Center visual design is preserved. New landing, role selection and authentication screens reuse its components and styles. No backend, database or SQL files are changed.

## Setup

Use Node.js 22.12+ or a supported newer version. From the repository root:

```powershell
cd frontend
npm install
# For a fresh checkout; the local .env is already updated:
Copy-Item .env.example .env
npm run dev
```

Set the Java API URL in `.env`:

```dotenv
VITE_API_MODE=live
VITE_API_BASE_URL=http://127.0.0.1:8080/api
```

The base URL includes `/api` exactly once. Restart Vite after changes. Use the frontend URL printed by Vite, normally http://127.0.0.1:5173. Keep the hostname consistent with the backend (use 127.0.0.1 on both, or localhost on both) for cookie site behavior. Frontend environment values are public; never put secrets there.

```powershell
npm test
npm run build
npm run preview
```

No new dependencies were added. DM Sans and existing colors, typography, layouts and spacing are retained. Only auth-specific CSS was appended. Fictional fixtures are used only by tests and the explicitly labelled development preview. They are excluded from production bundles. Live errors never fall back to fixtures. Authentication always calls the Java backend, regardless of inventory environment settings.

## Routes

Routes use hash navigation without React Router. Open as `http://127.0.0.1:5173/#/roles`, for example. This avoids server rewrite configuration and supports refresh/back/forward.

| Route                       | Access                         | Purpose                                    |
| --------------------------- | ------------------------------ | ------------------------------------------ |
| `#/`                        | Public                         | Healthcare coordination landing page       |
| `#/roles`                   | Public                         | Citizen or Hospital Staff role selection   |
| `#/citizen/sign-up`         | Public                         | Citizen account signup                     |
| `#/citizen/sign-in`         | Public                         | Citizen sign-in                            |
| `#/hospital/request-access` | Public                         | Request staff access; approval required    |
| `#/hospital/sign-in`        | Public                         | Hospital staff sign-in                     |
| `#/citizen`                 | Verified active citizen        | Existing Citizen Portal                    |
| `#/hospital`                | Verified active hospital staff | Existing Hospital Command Center           |
| `#/request`                 | Verified active hospital staff | Existing emergency request/allocation flow |

Legacy `#citizen`, `#hospital` and `#request` hashes still resolve and are guarded. Unknown routes show Page not found. Anonymous protected visits redirect to the relevant sign-in screen; wrong-role visits redirect to the user's own workspace. Signed-in users visiting auth forms return to their own workspace.

## Session behavior

The app checks `GET /api/auth/me` on startup and after login. It renders a session-loading state first. Only a validated ACTIVE user from `/me` opens a protected dashboard; a successful login POST alone is insufficient. Registration never authenticates the frontend. Staff must have a backend-approved hospital assignment. The existing hospital selector is restricted to this assignment; Java must enforce it independently.

Requests use `credentials: 'include'` and `cache: 'no-store'`. Java sets and owns the session cookie. No JWTs, hardcoded credentials, fake authentication, password storage or localStorage/sessionStorage auth state exists. Passwords remain only in the input/submission and are cleared after the attempt. A CSRF token returned by `/me` is kept only in memory and attached as `X-CSRF-Token` on POST requests, including inventory mutations.

A 401 from operational APIs clears the frontend session and gates the dashboard. Network/server errors display connection errors. Logout calls the backend and clears frontend state only after success; a failure is explicitly reported without claiming logout occurred. Logout supports 200 JSON or 204. After logout, `/me` bootstraps a new guest CSRF token. After a mutation timeout, confirm backend outcome before retrying to avoid duplicate requests.

## Authentication API contracts

These are proposed JSON DTOs for agreement with the Java and database teams, not new schema implementations. USERS and HOSPITAL_STAFF are managed by those teams; the six existing tables are unchanged. Confirm field naming and password policy before integration. The UI sanity policy is signup passwords 12–128 characters; sign-in does not impose the signup minimum on existing passwords. Confirm-password is never sent to the server.

### GET /api/auth/me

200 authenticated response:

```json
{
  "user": {
    "user_id": "U-123",
    "full_name": "User Name",
    "email": "user@example.com",
    "role": "CITIZEN",
    "status": "ACTIVE",
    "hospital_id": null
  },
  "csrf_token": "server-generated-csrf-value"
}
```

Staff uses `role: "HOSPITAL_STAFF"`, `status: "ACTIVE"` and a nonempty approved `hospital_id`. Other roles/statuses are rejected by the frontend. IDs may be strings or numbers; staff hospital comparisons normalize to strings. Do not return password hashes or authentication secrets.

Guest response: 200 `{ "user": null, "csrf_token": "guest-csrf-value" }`, or 401 `{ "csrf_token": "guest-csrf-value", "error": { "code": "UNAUTHENTICATED", "message": "Please sign in." } }`. A CSRF token is required even for a guest, so the login/register/logout POSTs can be protected. This may establish an anonymous server session. A plain 401 without the token shows a session error; agree this bootstrap contract explicitly. Return Cache-Control: no-store. A failed connection is not treated as an authenticated or successful session.

### POST /api/auth/register/citizen

Request:

```json
{
  "full_name": "User Name",
  "email": "user@example.com",
  "password": "user-entered-password"
}
```

201 response: `{ "status": "REGISTERED" }`. The account does not open a dashboard automatically. The user signs in next. Account signup is separate from the existing DONOR registration form and does not write blood/donor attributes here.

### POST /api/auth/register/hospital

Request:

```json
{
  "full_name": "Staff Name",
  "email": "staff@hospital.example",
  "password": "user-entered-password",
  "hospital_id": "H-123"
}
```

201/202 response: `{ "status": "PENDING_APPROVAL" }`. Requesting access does not create an approved staff session. The Java team owns staff verification, approval, role assignment and denial. Hospital selection loads the existing GET /api/hospitals contract; it must be available for public access requests (public listing may expose only agreed hospital directory fields). The backend must validate hospital ID and prevent self-granting permissions.

### POST /api/auth/login

Request:

```json
{
  "email": "user@example.com",
  "password": "user-entered-password",
  "role": "CITIZEN"
}
```

Use HOSPITAL_STAFF for staff. The role is an expected account type, never permission granted by the client. Java validates credentials and staff approval, rotates the session ID and sets a cookie. Response: 200 `{ "csrf_token": "rotated-csrf-value" }` (or 204). The frontend then calls `/me` and requires a matching role with ACTIVE status before routing. Invalid credentials: 401; unapproved/disabled staff: 403 with a safe explanation; rate limits: 429. The backend must enforce all credential validation and password hashing.

### POST /api/auth/logout

Request `{}` with cookie credentials and CSRF header. Response 204 (or 200 JSON). Java invalidates the session and expires its cookie. Make logout idempotent. Failures must use non-2xx HTTP status; frontend keeps its session state and reports the failure. The backend should return success when the session is already absent.

### Cookies, CSRF, CORS and authorization

Java must issue HttpOnly cookies, Secure over HTTPS in deployment, and an appropriate SameSite/Path/expiry policy. Frontend code never reads the session cookie. All POSTs attach X-CSRF-Token when `/me` supplied it; the server must validate it and rotate it as appropriate. Confirm the proposed header/bootstrap names against the Java framework configuration.

For cross-origin local development, allow the exact frontend origin, Access-Control-Allow-Credentials: true, GET/POST/OPTIONS and Content-Type/X-CSRF-Token. Do not use wildcard origins with credentials. For truly cross-site deployments agree SameSite=None; Secure and browser third-party cookie limitations, or prefer a same-site deployment. Authorize every inventory/request/allocation endpoint by server session role and hospital assignment; frontend guards are not access control. Return 401 for expired sessions, 403 for insufficient permissions and consistent safe JSON errors.

## Files changed in this extension

- Existing: `src/App.jsx`, `src/main.jsx`, `src/styles.css` (auth styles appended), `src/services/api.js`, `src/services/transport.js`, `src/components/useHospitals.js`, all three existing pages (public copy and staff scoping only), `.env.example`, local ignored `.env`, `README.md`, `tests/api.test.js` (error expectations).
- New: `src/auth/AuthContext.jsx`, `src/auth/routes.js`, `src/pages/LandingPage.jsx`, `src/pages/RoleSelection.jsx`, `src/pages/AuthPage.jsx`, `src/services/auth.js`, `src/services/authClient.js`, `src/services/authValidation.js`, `src/services/sessionSecurity.js`, `tests/auth.test.js`, `tests/api-integration.test.js`.
- No backend/database/SQL files or dependencies were modified. No commits or pushes are performed.

## Verification

Run `npm test` for operational fixtures and API wrapper paths/payloads plus auth transport contracts, cookie credentials, CSRF headers, guest session responses, login confirmation, role/approval validation, access-request status, logout failures/204 and route policies. Tests stub responses only; they are not runtime authentication. `npm run build` validates JSX and builds production assets. Actual login, approval, session persistence and logout require the separately implemented Java backend.

## Operational API contracts — agree with the Java developer

These endpoints are proposals, not implemented backend code or schema changes. JSON uses existing inventory attributes in snake_case. Wrapper objects, counts, distance and inventory metadata are response projections, **not new database columns**. IDs are shown as strings; agree actual Oracle ID types, serialization and generation. Confirm field lengths, nullability, gender and status enum values against the fixed schema. `blood_group`: O/A/B/AB; `rh_factor`: +/-.

### GET /hospitals

200 response: HOSPITAL array. Empty array is a valid no-hospitals state.

```json
[
  {
    "hospital_id": "H-DEMO-01",
    "name": "Sample North Hospital",
    "address": "Fictional North District, Vellore",
    "latitude": 12.94,
    "longitude": 79.14,
    "phone": "0000000000"
  }
]
```

### GET /hospitals/nearby?pincode=632001&blood_group=O&rh_factor=%2B

200 response:

```json
{
  "hospitals": [
    {
      "hospital": {
        "hospital_id": "H-DEMO-01",
        "name": "Sample North Hospital",
        "address": "Fictional North District, Vellore",
        "latitude": 12.94,
        "longitude": 79.14,
        "phone": "0000000000"
      },
      "distance_km": 2.4,
      "available_units": 3
    }
  ]
}
```

Pincode is a transient search parameter and must never be stored in DONOR or HOSPITAL. Backend converts pincode to coordinates, computes distance from existing hospital latitude/longitude, defines the radius and sorts by distance. `distance_km` and `available_units` are computed response values. Agree exact-match versus compatible-group search and which units qualify as available; expired/reserved bags must not count. No results: `{ "hospitals": [] }`. Invalid/unresolved pincode: clear 4xx error. UI never computes geocoding or distance.

### GET /hospitals/{hospital_id}/inventory

200 response:

```json
{
  "as_of_date": "2026-10-13",
  "near_expiry_days": 7,
  "bags": [
    {
      "bag_id": "B-DEMO-001",
      "donor_id": "D-DEMO-001",
      "hospital_id": "H-DEMO-01",
      "blood_group": "O",
      "rh_factor": "+",
      "collection_date": "2026-09-20",
      "expiry_date": "2026-10-15",
      "bag_status": "Available"
    }
  ]
}
```

Bag fields belong to BLOOD_BAGS. `as_of_date` and `near_expiry_days` are response metadata supplied by the backend. UI derives counts and warnings from bags. Near-expiry includes Available, unexpired bags within the threshold. Agree timezone and expiry-at-start/end-of-day rules; this proposal treats expiry on as_of_date as still unexpired. Return all bags for accurate counts; pagination requires a revised contract with server aggregates. UI recognizes Available, Reserved and Expired; other statuses still display in table and filter.

### POST /donors

Request (existing DONOR attributes only):

```json
{
  "full_name": "Fictional Demo Donor",
  "age": 25,
  "gender": "Female",
  "blood_group": "O",
  "rh_factor": "+",
  "contact_number": "0000000000",
  "last_donation_date": null
}
```

201 response: `{ "donor": { "donor_id": "D-DEMO-NEW-1", "full_name": "Fictional Demo Donor", "age": 25, "gender": "Female", "blood_group": "O", "rh_factor": "+", "contact_number": "0000000000", "last_donation_date": null } }`.

Backend creates donor_id and validates age, gender, phone, field lengths and nullability. UI age limits 1–120 are sanity checks, not donation eligibility. No pincode, account or eligibility fields are proposed. Backend must enforce all validation; registration does not confirm eligibility.

### POST /requests

Request (existing REQUEST attributes only):

```json
{
  "hospital_id": "H-DEMO-01",
  "blood_group": "O",
  "rh_factor": "+",
  "units_requested": 2,
  "priority": "Critical"
}
```

201 response:

```json
{
  "request": {
    "request_id": "R-DEMO-1",
    "hospital_id": "H-DEMO-01",
    "blood_group": "O",
    "rh_factor": "+",
    "units_requested": 2,
    "priority": "Critical",
    "request_status": "Pending",
    "request_time": "2026-10-13T10:00:00+05:30"
  }
}
```

Backend generates ID, status and timestamp. Priority: Critical/Urgent/Routine. Units: positive integer. Request creation does not allocate blood. Pending/Allocated are proposed API/mock values; confirm exact status vocabulary against Phase 1, without changing schema.

### POST /requests/{request_id}/allocations

Request: `{}`. 200 response:

```json
{
  "request": { "request_id": "R-DEMO-1", "request_status": "Allocated" },
  "allocations": [
    {
      "allocation_id": "A-DEMO-1",
      "request_id": "R-DEMO-1",
      "bag_id": "B-DEMO-001",
      "allocated_at": "2026-10-13T10:01:00+05:30"
    }
  ]
}
```

Allocation fields belong to ALLOCATION. Backend owns clinical compatibility, priority scheduling, expiry checks, atomic transactions, unique BAG_ID enforcement, BLOOD_BAGS status transitions and BAG_STATUS_LOG audit entries. Prevent double allocation under concurrency and retries. Agree partial versus all-or-nothing allocation; mock uses all-or-nothing and rejects repeats. Allocation must not bypass higher-priority requests. UI never directly writes statuses or audit logs. Agree idempotency behavior.

### Errors and networking

All failures should return an appropriate HTTP status with safe JSON messages:

```json
{
  "error": {
    "code": "INSUFFICIENT_STOCK",
    "message": "No compatible units are currently available."
  }
}
```

Agree 400 validation/unresolved pincode, 404 unknown IDs, 409 stock/concurrency conflicts and 5xx service failures. Configure CORS for frontend origin, GET/POST/OPTIONS and Content-Type. Confirm base URL, HTTPS deployment, ID serialization, YYYY-MM-DD dates, timestamp offsets, allowed enum values, validation limits, availability semantics, allocation policy and idempotency. Operational calls include the secure session cookie and CSRF header. Java must enforce role and hospital permissions on every endpoint.

## Verification

`npm test` checks availability counts, unsupported pincodes, atomic failed allocation, duplicate allocation prevention and live transport errors. `npm run build` compiles JSX and builds production assets. Live integration cannot be confirmed until the Java backend implements agreed contracts.

## Development-only portal preview (without a backend)

The local ignored `.env` is enabled for this task. On a fresh checkout, set:

```dotenv
VITE_ENABLE_PREVIEW=true
```

Restart `npm run dev` after changing the flag. Keep `VITE_API_MODE=live`: preview is separate from the real API mode and never changes sign-in behavior. On `#/roles`, choose **Preview Hospital Dashboard** or **Preview Citizen Portal**. The same options appear on the matching sign-in screens. Direct routes are `#/preview/hospital`, `#/preview/citizen` and `#/preview/request` on the URL printed by Vite. Preview can open even while the real session check is pending or fails.

A persistent banner labels development preview as **not authenticated**, with fictional hospitals, inventory, distances and a fixed reference date of 2026-10-13. No real availability is claimed. Existing screens and forms are reused with an explicitly injected in-memory data service. Citizen preview supports pincodes 632001, 632002 and 632014. Donor registration, requests and allocation operate only on memory fixtures and are labelled as preview results; no API mutations or real records are created. Do not enter real personal data. Navigation between preview screens shares those fixtures; exiting preview or reloading resets them. **Exit preview** returns to role selection.

To disable, set `VITE_ENABLE_PREVIEW=false` (or remove the variable), then restart Vite. Preview options disappear and direct preview routes show Page not found. Normal Citizen/Staff Sign In still calls the actual Java authentication APIs and verifies `/auth/me`; backend connection failures remain errors. No preview code sets AuthContext.user, stores auth state, creates tokens or grants real-role access. Normal `#/citizen`, `#/hospital` and `#/request` guards remain intact.

Production protection is based on the Vite command, not just `import.meta.env.DEV`: `vite.config.js` defines preview as false for every build and for `vite preview`, even with `VITE_ENABLE_PREVIEW=true`, `--mode development`, or `NODE_ENV=development`. A conditional lazy import excludes the preview workspace and fixtures from built assets. `npm run preview` serves a built application and **cannot** enable this development feature.

Added `src/preview/policy.js`, `src/preview/config.js`, `src/preview/PreviewWorkspace.jsx`, `src/components/PreviewOptions.jsx` and `tests/preview.test.js`. Updated app routing, sign-in/role-selection links, the existing pages and hospital hook for explicit data-service injection, Vite config and environment examples. Existing CSS, auth services, backend and database files are unchanged.
