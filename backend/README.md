# Java/JDBC blood bank backend

The production backend is `com.bloodbank.server.BloodBankServer`, using JDK 17+ and Oracle JDBC. There is no mock-success mode. `server.js` is a retired entrypoint that deliberately exits with an error. Never place `target/test-classes` or test build directories on the production classpath.

## Database contract

Source of truth: `origin/feature/oracle-database:database/schema_baseline.sql`, `views.sql`, `procedures.sql`, and `api_database_contract.md`. This backend uses HOSPITAL, USERS, HOSPITAL_STAFF, DONOR, BLOOD_BAGS, REQUEST, ALLOCATION, and BAG_STATUS_LOG. It does not install, migrate, drop, or recreate any database object.

The application schema must already contain the approved tables, `seq_users`, `seq_hospital_staff`, `seq_donor`, `seq_request`, the allocation/log sequences used by PL/SQL, `PRC_NEARBY_HOSPITALS`, `PRC_EMERGENCY_DISPATCH`, their dependent functions/procedures, and `V_REQUEST_ALLOCATION_DETAIL`. Use a dedicated application account with the required privileges, never SYSTEM.

The conflicting PR scripts below are excluded from this corrected branch; **do not execute copies from the original PR**:

- `database/00_phase1_core_schema.sql`
- `database/01_auth_and_schema_updates.sql`
- `database/02_plsql_emergency_transaction.sql`
- `database/03_seed_data.sql`

## Run on Windows

Prerequisites: JDK 17 or newer (`java` and `javac`), an Oracle JDBC jar, an existing Oracle service with the approved objects, and application credentials. Maven is optional. The POM retains `com.oracle.database.jdbc:ojdbc8:19.8.0.0`; this dependency has not been downloaded or tested against live Oracle here. No Jackson or Node runtime is needed.

From the repository root, in PowerShell:

```powershell
cd backend
# Set JAVA_HOME to your installed JDK if java/javac are not correctly on PATH.
# Example discovery: java -XshowSettings:properties -version
$env:ORACLE_URL = Read-Host 'Oracle JDBC URL (e.g. jdbc:oracle:thin:@//localhost:1521/XEPDB1)'
$env:ORACLE_USER = Read-Host 'Application schema username'
$env:ORACLE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Database password' -AsSecureString)).Password
$env:OJDBC_JAR = Read-Host 'Absolute path to your Oracle JDBC jar'
.\run-backend.ps1
```

The script checks configuration, compiles only production sources with `javac --release 17`, and runs Java with the supplied driver. Build output goes to a unique temporary directory to avoid OneDrive class-loading issues. `run-backend.bat` delegates to the same script. Stop with Ctrl+C. The script does not load `.env` files automatically.

If Maven is available, an alternative way to obtain the declared driver is:

```powershell
mvn compile org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies
$env:OJDBC_JAR = (Resolve-Path .\target\dependency\ojdbc8-19.8.0.0.jar).Path
.\run-backend.ps1
```

Maven resolution was not verified in the implementation environment because Maven was unavailable. The plain jar does not bundle JDBC; `java -jar` alone is not the supported launch command.

Default API URL: `http://127.0.0.1:8080/api`. Keep React's existing `VITE_API_BASE_URL` at this value. Use the same hostname for the browser frontend and API (127.0.0.1 with 127.0.0.1, or localhost with localhost), since session cookies are SameSite=Lax. No frontend edits are needed.

Optional environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `BIND_ADDRESS` | `127.0.0.1` | Listening interface |
| `CORS_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Exact comma-separated browser origins |
| `COOKIE_SECURE` | `false` | Set `true` behind HTTPS for deployment |

For nonlocal use, terminate HTTPS at a trusted reverse proxy and set `COOKIE_SECURE=true` and explicit frontend origins. Sessions are bounded, in memory, expire after eight hours (guest CSRF sessions: 30 minutes), and are invalidated by logout or a server restart. This server is a single-instance deployment; clustered shared sessions are not implemented.

## React API contract

Paths below are relative to `/api`. Errors have `{ "error": { "code": "...", "message": "..." } }`. Oracle configuration/connection failures return 503; SQL failures return a sanitized 500 (duplicate records: 409). Empty database results remain empty.

| Method and path | Access and response |
|---|---|
| GET `/auth/me` | Issues guest cookie and random CSRF token with 401; authenticated response is `{user, csrf_token}` with 200 |
| POST `/auth/register/citizen` | `{email,password,full_name,phone?}` -> 201 `{status:"REGISTERED"}` |
| POST `/auth/register/hospital` | Same plus numeric `hospital_id` -> 201 `{status:"PENDING_APPROVAL"}` |
| POST `/auth/login` | `{email,password,role}` -> `{user,csrf_token}`; rotates session cookie |
| POST `/auth/logout` | Invalidates server session and cookie |
| GET `/hospitals` | Hospital array; authenticated staff receive only their active assigned hospital |
| GET `/hospitals/nearby` | `pincode`, `blood_group`, `rh_factor`, optional `radius`, or `lat` and `lon` -> `{hospitals:[{hospital,distance_km,available_units}]}` |
| GET `/hospitals/{id}/inventory` | Approved staff only; scopes to authenticated assignment regardless of supplied ID; `{as_of_date,near_expiry_days,bags}` |
| POST `/donors` | Citizen only; `{full_name,age,gender,contact_number,last_donation_date,blood_group,rh_factor}` -> 201 `{donor:{donor_id}}` |
| POST `/requests` | Staff only; `{hospital_id,blood_group,rh_factor,units_requested,priority}` -> 201 `{request}` with PENDING; hospital scope comes from account |
| POST `/requests/{id}/allocations` | Staff at owning hospital; `{}` -> `{request,allocations,outcome,message}` from genuine dispatch |
| GET `/requests/{id}/allocations` | Owning hospital staff or requesting citizen; returns stored `{request,allocations}` without dispatching |
| GET `/health` | 200 only if an actual JDBC connection validates; otherwise 503; does not prove schema compatibility |

Compatibility aliases: POST `/requests/emergency` creates a pending request only; GET `/inventory/dashboard` returns the authenticated hospital's inventory envelope; GET `/blood/search` uses the same search envelope and accepts `bloodGroup`/`rhFactor` aliases. These are not separate simulation engines.

Every POST requires the cookie and `X-CSRF-Token` from GET `/auth/me`. React already performs this handshake. For Postman, first get a guest session; set the CSRF collection variable from the response, then log in and refresh it from the login response. The included collection automates token capture and uses Postman's cookie jar. Enter credentials in local variables; never export populated passwords.

## Mapping decisions and limitations

- Oracle stores numeric IDs; responses return JSON numbers. React accepts these IDs. Blood group and Rh are combined for Oracle and split for React inventory/request responses.
- `Critical` -> CRITICAL, `Urgent` -> HIGH, `Routine` -> NORMAL. Quantities must be whole numbers from 1 to 999.
- Search delegates RBC compatibility, expiry filtering, and stock selection to `PRC_NEARBY_HOSPITALS`. Available counts include compatible donor groups, not just exact-group bags. Dispatch delegates FEFO and locking to approved PL/SQL; there is no fallback allocation algorithm.
- Pincode coordinate data is absent from the approved schema. For a pincode with active geolocated hospitals, search uses the centroid of those actual hospital coordinates as an approximate search origin. A pincode with no such records returns 422 LOCATION_UNKNOWN. This is not full postal geocoding; explicit lat/lon supports precise origins without a schema change.
- A bag expiring today is unavailable under the approved rule (`expiry_date > TRUNC(SYSDATE)`). Inventory uses Oracle's date, title-case React labels, and shows such AVAILABLE rows as Expired without changing database rows.
- React has no patient fields. REQUEST.PATIENT_NAME receives the literal nonclinical label `Hospital stock request`, with a matching remark; no patient identity is invented. This endpoint records a hospital requirement, not a named-patient clinical request.
- DONOR has no age, gender or user-link columns. Age/gender are validated, but not stored; date_of_birth remains NULL because a birth date cannot be inferred from age. Name, combined blood group, phone, last donation date and a sequence-generated donor code are persisted. No unsupported identity link or medical eligibility claim is returned. The approved phone column allows 15 characters even though the frontend accepts up to 20; longer numbers return a clear 400.
- Staff access requests are stored as PENDING_VERIFICATION and cannot log in until an administrator independently verifies the assignment and activates the account. No public endpoint approves accounts. Approval is an administrative operation against the existing USERS account, not a schema migration.
- Passwords use salted PBKDF2-HMAC-SHA256, 600,000 iterations, JDK cryptographic APIs, constant-time comparison, and a versioned stored format. Existing test placeholder/legacy SHA hashes are rejected. Use registration to create a real hashed account; staff then require approval. Failed login counters lock an active account after five failures. Authentication requests are also rate limited per source IP.

## Validation

```powershell
.\run-tests.ps1
```

This compiles Java 17 production/test sources in a temporary directory, runs DAO/codec/password regression tests and HTTP tests on port 18089, and restores temporary environment settings. The JDBC double is confined to `src/test/java/oracle/jdbc/OracleDriver.java`, accepts only `jdbc:oracle:thin:@TEST-ONLY`, and is never on the production launch classpath. These tests verify Java decisions, parameter binding, resources and transactions; they do not validate Oracle SQL compilation, PL/SQL execution, or concurrent Oracle locking.

Before deployment, an authorized operator must verify against the real application schema:

1. Confirm the approved tables, columns, sequences, views, functions and procedures exist and are valid. Do not run the conflicting PR SQL or recreate tables.
2. Configure actual JDBC service URL, credentials and driver; call `/api/health`.
3. Register accounts, administratively approve staff, and complete cookie-backed login.
4. Search and inspect inventory, including empty/expired stock and different compatible groups.
5. Create a request and verify PENDING with no allocations; dispatch it and inspect REQUEST, ALLOCATION, BLOOD_BAGS and BAG_STATUS_LOG. Verify repeat dispatch is rejected and cross-hospital access is blocked.
6. Verify rollback and concurrent dispatch on test records in a dedicated test schema. Allocation commits real changes; never run this against production records casually.

No live Oracle JDBC operation has been verified during this implementation. The approved seed accounts use `TEST-ONLY-NOT-A-REAL-HASH` and are intentionally unusable for login.
