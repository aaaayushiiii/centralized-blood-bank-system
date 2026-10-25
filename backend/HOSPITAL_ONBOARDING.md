# Hospital application submission and onboarding

`POST /api/hospital-registration-requests` is public submission only. It does not
approve applications, authenticate applicants, or create HOSPITAL, USERS or
HOSPITAL_STAFF rows. Existing staff registration/login routes are unchanged.

## Implemented website workflow

The React `#/hospital/register` form uses the existing API transport, guest
session and CSRF handling. A successful submission shows its reference and
PENDING status; it does not activate a hospital or create staff credentials.
Administrator Login (`#/admin/sign-in`) and the protected Hospital Review
Dashboard (`#/admin`) now support real Oracle lists, details and decisions.
Citizen and hospital-staff registration/login remain separate and unchanged.

Java provides ADMIN login via `POST /api/auth/login`, protected session checking
via `GET /api/admin/auth/me`, and list/detail/approve/reject routes under
`/api/admin/hospital-registration-requests`. See
[WEB_ADMIN_AUTH.md](WEB_ADMIN_AUTH.md) for the offline CreateWebAdmin utility and
[ADMIN_HOSPITAL_REVIEW.md](ADMIN_HOSPITAL_REVIEW.md) for exact review contracts.
CreateWebAdmin inserts a new application-level ADMIN/ACTIVE USERS row with the
existing PBKDF2 hashing; it does not create an Oracle account or promote a user.

The current web review workflow requires `WEB_ADMIN_ENABLED=true` and loopback
binding/peer/origin checks. Local Java still connects as SYSTEM, which has
excessive privileges and is not suitable for deployment. The restricted CLI is
separate, still refuses SYSTEM, and has not been enabled through provisioning.
Local demo approval records that official government verification was not
performed; the HFR link is optional information, not automatic verification.

Operator-verified live submission, rejection and website review results are
recorded in [Verified local demonstration](ADMIN_HOSPITAL_REVIEW.md#verified-local-demonstration).
They include request 100000 REJECTED, 100001 APPROVED (hospital 100000), and
100002 APPROVED through the website (hospital 100001, WEB_ADMIN:100022).
These are fictional academic records, not government-approved hospitals.

## Request and response

First GET `/api/auth/me`, retain its guest cookie and read `csrf_token` from the
JSON body. HTTP 401 is expected for this guest handshake. POST using the same
cookie, `Content-Type: application/json` and `X-CSRF-Token`. Origin must satisfy
the existing CORS policy. The existing 16 KiB body limit and strict JSON parser
apply; duplicate JSON keys are rejected.

Illustrative fictional request for a separately authorized test. This example
is not a command to submit again or a dump of any live application:

```json
{
  "hospital_name": "Onboarding Test Hospital",
  "address": "1 Fictional Test Road",
  "district": "Test District",
  "pincode": "600001",
  "latitude": 13.082680,
  "longitude": 80.270718,
  "official_phone": "+91 90000 00001",
  "official_email": "office@example.test",
  "registration_authority": "TEST_AUTHORITY",
  "jurisdiction_code": "IN_TN",
  "registration_number": "TEST-2026/001",
  "representative_name": "Test Representative",
  "representative_title": "Coordinator",
  "representative_email": "representative@example.test",
  "representative_phone": "9000000002"
}
```

All fields except coordinates are required. Coordinates must both be supplied
as JSON numbers or both be absent/null. Latitude is -90..90, longitude -180..180;
values must be representable at six decimal places without rounding.

Text is stripped, must not be blank or contain control/unpaired surrogate
characters, and is bounded by the migration's VARCHAR2 BYTE sizes using UTF-8:
hospital name 150, address 300, district 60, pincode 6, phones 20, emails 254,
authority 80, jurisdiction 30, registration number 100, representative name 100,
title 80. Deployment should use the expected AL32UTF8 database character set;
different database encodings require a byte-limit compatibility review.
Phone numbers must contain 7..15 digits with optional leading +, spaces,
parentheses and hyphens. Pincode must be six digits starting with 1..9.

Authority/jurisdiction accept ASCII letters, digits and underscore; they are
uppercased with Locale.ROOT. Registration numbers accept ASCII letters/digits,
spaces, hyphens, slash and dot, then uppercase and remove those separators. Thus
`TEST-2026/001` is stored as `TEST2026001`, matching the database index's key.
Emails are lowercased. Unknown fields are rejected, including privileged fields
even when null: status, submitted_at, registration_key, request ID, review
metadata, approved hospital ID, password or role.

Successful response: HTTP 201, after JDBC commit:

```json
{"registration_request_id":100000,"status":"PENDING"}
```

The ID comes only from SEQ_HOSP_REG_REQUEST; no client-generated ID is accepted.
The insertion uses a PreparedStatement and literal server-owned PENDING status.
The database supplies submitted_at and registration_key. Validation precedes
opening a connection. The sequence and insert use a single explicit JDBC
transaction, with rollback on failure and scoped resource closure. Oracle
sequence increments are not transactional; failures may leave normal ID gaps.

Errors retain the existing `{ "error": { "code": "...", "message": "..." } }`
envelope: 400 invalid fields/JSON, 403 CSRF/origin, 405 wrong method, 409
REGISTRATION_CONFLICT, 413 body limit, 415 media type, 429 RATE_LIMITED, 503 Oracle
unavailable, or 500 unexpected database failure. Duplicate handling relies on
the Oracle unique index, without a race-prone SELECT-before-INSERT check. A
conflict discloses no prior application ID, contact, applicant or constraint
name. It still reveals that a conflicting record exists; it is not an anonymous
status lookup. Do not retry a timeout blindly: the commit may have succeeded.

## Submission throttling

A separate bounded in-memory limiter allows 20 CSRF-valid submission attempts
per TCP peer IP per fixed ten-minute window. Invalid payloads and database
failures also count. At capacity (10,000 peer entries) or above the rate limit,
return 429 with Retry-After seconds. Guest-session rotation does not evade the
limit. Authentication's existing limiter and decisions are unchanged.

This is one-process throttling; it resets on restart. Multiple replicas need
shared enforcement. Behind a proxy, TCP peers may share one budget; do not trust
arbitrary forwarded-IP headers. Use a trusted reverse proxy/rate limiter before
public deployment. TLS, Secure cookies and the existing exact CORS configuration
must be set appropriately for production.

## Focused automated verification

From `backend/`:

```powershell
.\run-onboarding-tests.ps1
```

The runner compiles all production sources with `--release 17` without the test
driver, then compiles/runs only HospitalRegistrationTests and the existing
test-only OracleDriver. It forces the test-only URL, uses a local HTTP server
on port 18090, and restores environment settings on exit. It does not execute
the previous citizen, inventory, request or FEFO suites. No live driver is on
its test classpath and no Oracle SQL is executed.

Observed result: 245 checks passed, covering DAO bindings/normalization, required
and unknown fields, byte limits, coordinate rules, success/conflict/error
transactions and resource closure, HTTP guest CSRF/CORS/method checks, strict
JSON/body/media limits, no authenticated session, redacted failures and the
separate submission throttle. Database failures printed during tests are
intentional test-double failures. Oracle SQL execution and concurrent unique
index behavior are not claimed as tested by these doubles.

For any separately authorized additional live test, use a fresh fictional identity, perform the
guest handshake and POST the sample. Expect 201/PENDING. Read the row back using
the returned ID and confirm no HOSPITAL/account/staff row was created. Submit
the same identity again with different case/separators: expect a generic 409.
Do not test real registrations or insert into the integrated database without
authorization. Initial implementation tests used doubles only; subsequent local
live demonstration results are recorded above.

## Before public release

The authority/jurisdiction codes need a shared controlled vocabulary and issuing
authority-specific normalization policy. Uppercasing cannot resolve aliases or
impersonation. Real-world approval would require independent registration,
contact, representative-authority and licensing verification. The local demo
explicitly does not perform official government verification. Legacy hospitals
lack registration identity columns; review code matches names, address/pincode
and phone, which cannot establish legal identity by themselves.

Provision separate restricted submission and administrator database credentials
before public release; shared SYSTEM credentials cannot enforce that boundary.
The local demonstration intentionally did not complete the separate Oracle
account/role provisioning. The implemented administrator APIs are protected,
loopback-only routes; there is no unauthenticated approval endpoint or automatic
representative account creation. No production-readiness claim is made.
