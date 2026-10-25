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

No new dependencies were added. DM Sans and existing colors, typography, layouts and spacing are retained. Only auth-specific CSS was appended. Synthetic responses are used only in automated tests. Live errors never fall back to fixtures. Authentication always calls the Java backend, regardless of inventory environment settings.

## Routes

Routes use hash navigation without React Router. Open as `http://127.0.0.1:5173/#/roles`, for example. This avoids server rewrite configuration and supports refresh/back/forward.

| Route                       | Access                         | Purpose                                    |
| --------------------------- | ------------------------------ | ------------------------------------------ |
| `#/`                        | Public                         | Healthcare coordination landing page       |
| `#/roles`                   | Public                         | Citizen or Hospital Staff role selection   |
| `#/citizen/sign-up`         | Public                         | Citizen account signup                     |
| `#/citizen/sign-in`         | Public                         | Citizen sign-in                            |
| `#/hospital/request-access` | Public                         | Request staff access; approval required    |
| `#/hospital/register`       | Public                         | Apply to register a new hospital           |
| `#/hospital/sign-in`        | Public                         | Hospital staff sign-in                     |
| `#/citizen`                 | Verified active citizen        | Existing Citizen Portal                    |
| `#/hospital`                | Verified active hospital staff | Existing Hospital Command Center           |
| `#/request`                 | Verified active hospital staff | Existing emergency request/allocation flow |

Legacy `#citizen`, `#hospital` and `#request` hashes still resolve and are guarded. Unknown routes show Page not found. Anonymous protected visits redirect to the relevant sign-in screen; wrong-role visits redirect to the user's own workspace. Signed-in users visiting auth forms return to their own workspace.

## Hospital onboarding frontend

Home and role selection include **Register Your Hospital**, linking to
`#/hospital/register`. Existing staff sign-in and access-request links remain.
This public route also works for an already signed-in representative and does
not grant any dashboard permission.

The form follows `backend/HOSPITAL_ONBOARDING.md`: thirteen required text fields
plus optional paired coordinates. Representative designation is sent as
`representative_title`. The payload is allowlisted and never sends review
metadata, approval status, hospital IDs, passwords or roles. Client validation
checks UTF-8 byte limits, contacts, official codes and coordinate bounds/precision;
the Java API remains authoritative. Canonical authority/jurisdiction codes must
be agreed with the administrator; the frontend does not invent a code catalog.

The existing API base URL, cookie transport and in-memory CSRF mechanism are
reused. The new client bootstraps through `/auth/me` only if a token is absent,
then POSTs `/hospital-registration-requests`. An additive `expectedStatus` option
in the shared transport requires HTTP 201 for this call; existing consumers
retain their prior behavior. A valid numeric reference and PENDING response
are required before displaying success. Registration never logs the applicant
in or activates a hospital/account.

Submission is locked synchronously while pending and after success. Recoverable
validation/conflict/availability errors retain entered values for a deliberate
retry. CSRF expiry clears only the token so a later manual retry bootstraps it;
POSTs are never automatically replayed. A network failure after sending or an
unconfirmed success blocks further submissions in that mounted form and asks
the applicant to contact the administrator. There is no idempotency/status API;
refresh/navigation cannot guarantee exactly-once submission, and an interrupted
request may have committed. No application data is stored in browser storage.

Targeted verification only:

```powershell
node --test tests/hospital-registration.test.js
npm run build
```

Observed result: 25 reported tests passed, production build passed, with no new
dependencies. Tests cover exact payload/normalization, validation, guest CSRF,
cookies, expected success status, API failures, routing/navigation and React
submission lifecycle. They use synthetic fetch responses, server-rendered React
markup and the existing dependency-free hook scheduler pattern. They do not
claim real-browser layout or live React-to-Oracle verification. No live POSTs,
Oracle writes or existing citizen/FEFO integration suites were run in this phase.

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

## Operational API integration

Operational screens use the Java REST API configured above. See `../backend/README.md` for the implemented contracts and Oracle behavior. Hospital listing, compatible blood search, donor registration, hospital inventory, request creation and allocation use the existing Java/JDBC services.

Request creation and allocation are separate actions. Java owns committed Oracle transactions, compatibility, FEFO reservation and audit logging. The frontend never writes database statuses or audit records directly.

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

`npm test` checks the existing live API transport, response contracts, authentication and hospital lifecycle behavior using synthetic responses. `npm run build` compiles JSX and builds production assets. These automated checks do not create Oracle records.

## Local administrator web dashboard (Task 4)

The role-selection page includes **Administrator Login** at `#/admin/sign-in`. An ACTIVE ADMIN session opens `#/admin`. Citizen and hospital-staff sessions retain their existing workspaces. There is no administrator signup screen. The existing API URL, cookie transport, CSRF store and dependencies are reused; no password, session identifier or review token is saved in browser storage.

The dashboard reads the Java APIs documented in [ADMIN_HOSPITAL_REVIEW.md](../backend/ADMIN_HOSPITAL_REVIEW.md). It lists Pending, Approved and Rejected applications in pages of 20 using `after_id`, opens full details, and holds the signed review token in component memory. Local demo approval only asks "Approve this hospital?" after reviewing submitted details. Verified identity re-entry, checkboxes, evidence and typed phrases are not required. Rejection asks for a reason and confirmation. Java records fixed academic-demo notes and server-derived reviewer metadata; official government verification is not performed. The HFR link is optional information. These operations remain restricted by the existing loopback-only WEB_ADMIN_ENABLED policy.

Decisions are never automatically retried. Any failed or uncertain decision requires reloading details and checking the current status before reviewing again. Successful decisions refresh the queue. Repeated submissions are blocked while saving. The backend always authorizes the operation and derives reviewer metadata; frontend route guards only improve usability.

Administrator sessions have a server-enforced absolute 30-minute lifetime. React also clears administrator state after a 30-minute UI upper bound, and the dashboard rechecks the protected session every minute and on window focus. A restored session may have less time remaining; server responses remain authoritative. Confirmed logout clears React identity, unmounts review state, and reboots guest CSRF. A failed logout is reported without claiming the server session ended.

Local prerequisites, to be configured only when separately approved: an intentionally created web ADMIN account, backend `WEB_ADMIN_ENABLED=true`, loopback binding and an allowed local frontend origin. There are no new VITE_* credentials or flags. The current SYSTEM JDBC connection is excessive privilege for a classroom demonstration and is not suitable for deployment. The restricted CLI's safeguards remain unchanged.

Focused tests: `node --test tests/admin-review.test.js`. Full frontend tests: `npm test`. Production build: `npm run build`. Tests use synthetic fetch responses and an isolated React hook scheduler; they do not connect to Oracle or replace runtime data. They verify contracts/handlers and do not claim browser layout or live Oracle verification. Task 4 creates no administrator account and performs no live approval/rejection.
