# Restricted hospital review CLI (Phase 3)

This restricted CLI is implemented and tested, but remains **not enabled for
live use**: the separate reviewer account/role/grants were not provisioned.
It still refuses SYSTEM and SYS. The abandoned provisioning effort is not a
prerequisite for the separate, controlled local website demonstration.

The implemented web workflow is distinct: React hospital registration,
Administrator Login and Dashboard call protected Java authentication and
list/detail/approve/reject APIs. The web backend uses the existing SYSTEM
connection under localhost-only `WEB_ADMIN_ENABLED`; CreateWebAdmin separately
creates an application-level ADMIN/ACTIVE USERS record with PBKDF2 hashing, not
an Oracle reviewer identity. Neither web authentication nor web approval invokes
this CLI or relaxes its authorization. SYSTEM remains excessive privilege and
this local academic arrangement is not production-ready.

The operator has live-tested Oracle website review and approval/rejection.
Verified fictional records are request 100000 REJECTED; 100001 APPROVED, hospital
100000; and 100002 APPROVED through the website, hospital 100001, reviewer
WEB_ADMIN:100022. These results do not claim that this CLI was run live or that
any hospital was government-verified. See
[ADMIN_HOSPITAL_REVIEW.md](../ADMIN_HOSPITAL_REVIEW.md) for the current website
contracts and demo policy, and [WEB_ADMIN_AUTH.md](../WEB_ADMIN_AUTH.md) for setup.

## Isolation and authorization

This source tree is deliberately outside backend/src/main/java and the web
Maven source root. The existing production launcher compiles only public sources;
the admin launcher compiles only admin sources. The admin artifact has no web
server dependencies or endpoints and is not copied onto the public runtime
classpath. Keep this folder/artifact and reviewer credentials off any production
web host. OS access to the admin console and JDBC jar must be restricted to
trusted operators. Do not combine jars or add this source root to the web POM.

Use a separate named database account for each human reviewer, for example
BB_REVIEW_ALICE. The CLI permits only BB_REVIEW_ followed by 1..20 uppercase
ASCII letters/digits/underscores. Never use SYSTEM, SYS, the public application
account, a shared human account or proxy authentication. It checks SESSION_USER,
AUTHENTICATED_IDENTITY, CURRENT_SCHEMA, absence of PROXY_USER, ISDBA=FALSE and
CON_NAME=XEPDB1 on every operation. The sole enabled role must be
BB_HOSPITAL_REVIEWER. Any effective system privilege other than CREATE SESSION
causes refusal. A username prefix alone does not authorize a reviewer: password
authentication, the DBA-controlled role and actual object grants are required.

Provisioning plan for a DBA to review **later; not executed by these files**:

| Principal | Required privilege only |
|---|---|
| Each BB_REVIEW_* human account | CREATE SESSION, plus BB_HOSPITAL_REVIEWER as its sole/default role |
| BB_HOSPITAL_REVIEWER | SELECT on SYSTEM.HOSPITAL_REGISTRATION_REQUEST |
| BB_HOSPITAL_REVIEWER | UPDATE(status, reviewed_at, reviewed_by, verification_notes, rejection_reason, approved_hospital_id) on SYSTEM.HOSPITAL_REGISTRATION_REQUEST |
| BB_HOSPITAL_REVIEWER | SELECT and INSERT on SYSTEM.HOSPITAL |
| BB_HOSPITAL_REVIEWER | SELECT on SYSTEM.SEQ_HOSPITAL |
| BB_HOSPITAL_REVIEWER | EXECUTE on SYS.DBMS_LOCK |

No grant options, administrative roles, ANY system privileges, table ownership,
DDL privileges, hospital UPDATE/DELETE, request INSERT/DELETE, sequence-alter
privileges, or access to USERS/HOSPITAL_STAFF/inventory/requests/allocations/logs
are needed. Do not grant these via PUBLIC. The reviewer role has no web login
meaning. Have the DBA inspect effective direct, inherited and PUBLIC grants as
well as account policy before enabling it. The tool does not certify arbitrary
existing object grants as least-privilege merely because its role checks pass.

The existing local web server uses SYSTEM. SYSTEM inherently owns the
tables and can bypass any Java tool's rules using SQL. Source/classpath isolation
prevents it invoking this implementation, but cannot revoke schema-owner powers.
Before public release, move the web process to a restricted submission account
with no reviewer role, hospital INSERT, decision-column UPDATE or DBMS_LOCK
execution. Its other existing feature grants must be planned independently to
preserve those features. This phase changes no credentials or grants.

## Run later, only after authorized provisioning

Open an ordinary interactive PowerShell console. Set nonsecret configuration:

```powershell
$env:ADMIN_ORACLE_URL = 'jdbc:oracle:thin:@//localhost:1521/XEPDB1'
$env:ADMIN_ORACLE_USER = 'BB_REVIEW_ALICE'
# Set OJDBC_JAR to the existing trusted production Oracle JDBC jar path.
cd backend/admin
.\run-admin.ps1 -Action list
.\run-admin.ps1 -Action list -RequestId 100099   # next page after this ID
.\run-admin.ps1 -Action view -RequestId 100000
.\run-admin.ps1 -Action approve -RequestId 100000
.\run-admin.ps1 -Action reject -RequestId 100001
```

The CLI accepts only a local localhost/127.0.0.1 thin JDBC URL to XEPDB1, never
the web ORACLE_URL/ORACLE_USER/ORACLE_PASSWORD environment. Each invocation prompts
for the reviewer's existing password using Console.readPassword (no echo). No
password arguments, credential files or environment password fallback exist.
Redirected/noninteractive execution refuses before connecting. Password character
buffers are wiped on exit; the JDBC driver still necessarily uses an immutable
String internally. No decision payload is accepted in process arguments.

Commands return nonzero on failure/refusal. SQL messages, connection strings,
passwords and applicant details are not printed in exception traces. View/list
intentionally display application data to the authorized reviewer; protect the
console and avoid transcripts/screenshots containing PII. Control/format
characters are escaped before displaying applicant-supplied strings.

## Review and transaction protocol

List returns at most 100 PENDING entries ordered by ID with keyset pagination.
View displays the complete selected record, including prior decision metadata.
Read operations finish with rollback/connection closure and do not hold locks
while the operator investigates or enters evidence.

Approval first displays the application, then requires independent verified
authority/jurisdiction codes, registration number and representative email. It
also requires separate nonblank evidence/reference notes for the real registry
check, the representative's authority, and active/inactive legacy-hospital review
(each up to 600 UTF-8 bytes; combined notes bounded to 2000). Contact the issuing
registry and an independently sourced hospital contact; applicant-provided
contact details alone are not proof. Use the shared canonical issuing-authority
vocabulary; normalize numbers with the same case/separator policy as submission.
Type the exact confirmation phrase containing the application ID. These are
explicit human attestations: software cannot establish that evidence is true.

Only after prompts finish does the transaction begin:

1. Authenticate/authorize the reviewer from the database session, not a supplied
   reviewed_by field or a portal USERS.ADMIN account.
2. SELECT the application FOR UPDATE WAIT 5; require PENDING and an exact match
   of all visible values to the previously viewed snapshot. If any value changed,
   the operator must view and verify it again.
3. Compare verified identity and representative email against the locked row.
4. Acquire exclusive Oracle application lock **731908231**, timeout five seconds,
   release_on_commit=TRUE. Reserve this numeric ID exclusively for hospital
   approval in this database. All CLI approvals use this same lock, across
   different applications, and retain it through hospital insert and commit.
5. Recheck other PENDING/APPROVED applications for identical official identity.
   Query all HOSPITAL rows, including inactive ones, for matching trimmed/casefolded
   name, address+pincode, or digit-normalized official phone. Any candidate blocks
   approval before sequence consumption. No automatic merge/reactivation/override
   is available. Independently investigate fuzzy/alias matches as well; this
   conservative check is not a universal identity registry.
6. Generate SEQ_HOSPITAL.NEXTVAL; insert one active hospital with submitted details.
7. Conditionally update the still-PENDING application to APPROVED with database
   SYSTIMESTAMP, authenticated reviewer, evidence notes and approved hospital ID.
   Require exactly one affected row; commit only when all operations succeed.

The global lock serializes check-then-insert across this tool's sessions; the
application row lock and conditional update prevent repeated approval/rejection.
Existing UQ_HRR_OPEN_ID also enforces official-identity uniqueness. No named-lock
allocation, implicit commit, LOCK ANY TABLE, new lock table or alteration of an
existing hospital is needed. DBMS_LOCK.REQUEST releases the lock on commit or
rollback; nonzero request results cause rollback. See Oracle 21c documentation:
https://docs.oracle.com/en/database/oracle/oracle-database/21/arpls/DBMS_LOCK.html

Rejection displays the selected application and requires nonblank verification
notes (2000 bytes), reason (1000 bytes), and an ID-specific confirmation phrase.
It authorizes, locks, checks PENDING/current snapshot, conditionally updates only
the decision columns, and commits. It never consumes a hospital sequence or
inserts a hospital. A competing approval/rejection must wait for the same row
and then refuse the terminal status. No decision can automatically create staff.

Every SQL/runtime failure in a decision attempts rollback and closes the fresh
nonpooled connection; no success is printed until commit returns. Sequence gaps
after rollback are normal. If the network fails during commit, its outcome may
be ambiguous: view the application before retrying. Terminal applications are
never reset to PENDING by the tool. Actor and evidence are retained on the
existing application row; no additional audit-log table records are changed.

## Tests and limits

```powershell
cd backend/admin
.\run-admin-tests.ps1
```

The runner independently compiles public and admin sources with --release 17,
tests that AdminCli cannot load from the public runtime, then runs only focused
administrator checks using the existing test-only OracleDriver. Tests cover
authorization, list/view, normalized verified identity, atomic approval,
rejection, repeated decisions, stale snapshots, duplicate official/legacy
hospital refusal, required evidence, lock failure/timeout, conditional update
counts, sequence/insert/update/commit failures, rollback and JDBC resource
closure. Existing regression suites and the public onboarding suite are not run.
The shared test driver only gains optional fault-injection fields whose defaults
retain its existing behavior.

No live application lock, reviewer account creation, grants or DML was executed
through this restricted CLI. Website live results are separate, as noted above.
Test doubles verify SQL/order/transaction decisions, not actual Oracle
privilege grants or blocking semantics. Before live use, separately authorize
provisioning and dedicated-schema tests using two real reviewer sessions for
same-application approve/approve, approve/reject and different-application
same-hospital races, plus fault rollback. Out-of-band owner writes or other
hospital-creation tools do not participate in this advisory lock and remain a
deployment limitation; do not allow them to create hospitals concurrently.
