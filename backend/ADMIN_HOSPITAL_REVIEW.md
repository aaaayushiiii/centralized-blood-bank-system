# Local web administrator hospital review

The Java review APIs and React Administrator Dashboard are implemented. All routes below require `WEB_ADMIN_ENABLED=true`, a literal loopback server binding, loopback peer/local addresses, an allowed local browser Origin, and an authenticated ADMIN session. The feature remains disabled by default. The existing login, session cookie, expiry, CSRF and logout mechanisms apply. Every operation rereads ACTIVE/ADMIN from Oracle; decisions additionally lock that USERS row until commit. No public signup or approval route exists.

This is a local academic demonstration using the existing SYSTEM-owned schema. SYSTEM has excessive privileges: application authorization is not database privilege separation. Do not deploy this arrangement, expose it through a proxy/tunnel, or share database/admin credentials. The restricted CLI and its SYSTEM refusal are unchanged.

## Website access and setup

The React hospital registration form is available at `#/hospital/register`.
Administrator Login appears in role selection at `#/admin/sign-in`; the protected
review dashboard is `#/admin`. It uses the existing cookie/CSRF transport and
real Oracle records, with Pending/Approved/Rejected filters, cursor pagination,
full details and confirmation before decisions. Review tokens remain in memory.
There is no public administrator signup or automatic staff-account creation.

`POST /api/auth/login` authenticates the Oracle ACTIVE/ADMIN account and rotates
the session; `GET /api/auth/me` and `GET /api/admin/auth/me` validate it.
`POST /api/auth/logout` invalidates it. Administrator sessions expire after 30
minutes. The offline `backend/tools/create-web-admin.ps1` launcher runs
CreateWebAdmin to insert one new application-level USERS ADMIN/ACTIVE row using
existing salted PBKDF2 hashing. It prompts secretly for passwords and never
creates an Oracle user/role or promotes an existing account. See
[WEB_ADMIN_AUTH.md](WEB_ADMIN_AUTH.md) for the intentional local setup procedure.
The separate restricted CLI retains its SYSTEM refusal and its stricter review
workflow; the website does not call it.

## Verified local demonstration

The operator reported successful local Oracle XE 21c / XEPDB1 integration:
React hospital submission, administrator login, real application listing/details,
and website approval/rejection. The recorded fictional demonstration state is:

| Registration request | Status | Approved hospital ID | Verification context |
|---|---|---|---|
| 100000 | REJECTED | None | Earlier dummy application; not a verified hospital |
| 100001 | APPROVED | 100000 | Earlier controlled academic demonstration approval |
| 100002 | APPROVED | 100001 | Approved through website; reviewed_by = WEB_ADMIN:100022 |

The operator also verified hospital 100001, linked from request 100002, is active
(IS_ACTIVE=Y). Registration request IDs and hospital IDs are separate identifiers.
These results are not seed data, government certifications or a claim of broad
live concurrency/fault testing. No Oracle query or write was performed to prepare
this documentation. Further live tests require their own authorization.

## Read contracts

`GET /api/admin/hospital-registration-requests?status=PENDING&after_id=0&limit=20`

- `status`: PENDING (default), APPROVED or REJECTED, case insensitive.
- `after_id`: nonnegative decimal cursor, maximum 10 digits; default 0.
- `limit`: 1–100; default 20. Unknown or duplicate query parameters fail.
- HTTP 200: `{applications: [...], status, limit, next_after_id}`. Use the returned cursor until it is null. Each summary contains only `registration_request_id`, `hospital_name`, `district`, `status`, `submitted_at`, `approved_hospital_id`. Contacts, addresses and evidence are excluded from the list.

`GET /api/admin/hospital-registration-requests/{id}`

HTTP 200: `{application: {...}, review_token: "..."}`. Application fields match the onboarding table: request ID; hospital name/address/district/pincode/coordinates; official phone/email; registration authority/jurisdiction/number/key; representative name/title/email/phone; status; submitted/reviewed timestamps; reviewer; notes/reason; approved hospital ID. Nullable fields remain null. Timestamps use `YYYY-MM-DDTHH:mm:ss.ffffff` (Oracle timestamp, no invented timezone). All responses use `Cache-Control: no-store`.

The opaque HMAC review token binds all returned application values to the authenticated user, current login session and server process. It is not authorization or a replacement for CSRF. Reload details after a change, new login, server restart or stale-token response. It remains usable only while that session is valid and the row is unchanged.

## Approval contract

`POST /api/admin/hospital-registration-requests/{id}/approve`

Use the existing session cookie and `X-CSRF-Token`. Local demo JSON contains exactly:

```json
{
  "review_token": "<token from detail in this login session>",
  "confirmation": "APPROVE 123456"
}
```

Confirmation must exactly equal `APPROVE {id}`; React generates it after the administrator confirms "Approve this hospital?". No typed identity, evidence, checklist or review context is required. Java supplies the exact notes: `Local academic demonstration approval; official government verification not performed.` Browser-supplied notes, identities and privileged fields are rejected.

This simplified endpoint requires the existing `WEB_ADMIN_ENABLED=true` policy and loopback binding, peer and origin checks. It is unavailable by default or on a public server binding. The HFR link is optional information; no government verification or blood-bank licensing is claimed. The restricted CLI retains its existing independent safeguards and workflow.

The transaction locks the pending request, validates its session-bound token, obtains the CLI-compatible exclusive `SYS.DBMS_LOCK` ID **731908231** (5-second wait, release on commit), and checks conflicting PENDING/APPROVED applications and all hospitals. Matches include normalized official identity where stored, uppercase trimmed name, uppercase trimmed address plus pincode, or normalized phone. HOSPITAL has no official registration columns, so legacy hospitals require name/address/phone checks. Conservative matches require investigation; no override is exposed.

Only then does it obtain `SYSTEM.SEQ_HOSPITAL.NEXTVAL`, insert exactly one active HOSPITAL and conditionally update the pending application to APPROVED with `WEB_ADMIN:<authenticated user ID>`, server timestamp, fixed demo notes and hospital ID. Both changes commit together. No staff account is created.

HTTP 200: `{registration_request_id, status: "APPROVED", approved_hospital_id}`.

## Rejection contract

`POST /api/admin/hospital-registration-requests/{id}/reject`

Same cookie/CSRF requirements. Exact JSON fields:

```json
{
  "review_token": "<token from detail>",
  "confirmation": "REJECT 123456",
  "rejection_reason": "<nonempty reason>"
}
```

The nonempty reason has a 1000 UTF-8 byte limit. Java supplies `Local academic demonstration rejection; official government verification not performed.` as review notes. Confirmation must exactly equal `REJECT {id}`. Locks the pending application, checks its token, records server reviewer/timestamp/notes/reason and commits one conditional update. No hospital insert, sequence consumption or staff creation occurs.

HTTP 200: `{registration_request_id, status: "REJECTED"}`.

## Errors, guarantees and limits

Unknown/missing body fields, including submitted reviewer, role, status or hospital ID, are rejected. All writes use bound parameters. Existing 16 KiB JSON limit, duplicate-key rejection and safe error response format apply. Typical errors: 400 invalid input, 401 invalid session, 403 policy/role/CSRF denial, 404 missing application, 409 stale/already-reviewed/conflicting/busy review, 413 oversized body, 500/503 safe database failure. SQL text and applicant conflict details are never returned. Both success responses are sent only after commit. Any caught transaction failure triggers rollback; connection failure during commit can leave the outcome uncertain, so reload the record before retrying. Oracle sequences may have gaps after rollback.

Request row locking and conditional updates prevent repeated decisions; the shared advisory lock serializes cooperating approval code across different applications. Other hospital writers must cooperate with that lock to obtain the same duplicate protection. Direct SYSTEM SQL can bypass application safeguards. Concurrent new submissions can appear after duplicate checks; subsequent approval must recheck them under the shared lock. No claim of database-wide uniqueness for hospital names/phones is made.

No migration or grant is added. Local execution assumes the existing JDBC identity can select/lock USERS and applications, select hospitals/sequence, insert HOSPITAL, update decision columns and execute SYS.DBMS_LOCK. The controlled local live results are recorded above. Additional Oracle writes,
provisioning and live contention/fault verification require separate approval.

Run `./backend/run-admin-review-tests.ps1` from the repository root in PowerShell. It compiles Java 17 sources and runs only synthetic JDBC-double/API checks on loopback ports 18093/18094. The runner forces `jdbc:oracle:thin:@TEST-ONLY` and excludes the real Oracle driver; it cannot write live records. Tests cover default-disabled access, authorization, token/CSRF validation, duplicate checks, prepared bindings, commit/rollback and safe errors. No earlier citizen, inventory or FEFO suite is run.
