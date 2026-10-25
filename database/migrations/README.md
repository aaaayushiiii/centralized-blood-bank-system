# Hospital onboarding migration 001

## Current local status

Migration 001 and the narrowly scoped ORA-54034 recovery were completed locally
on Oracle XE 21c / XEPDB1. The operator verified HOSPITAL_REGISTRATION_REQUEST,
SEQ_HOSP_REG_REQUEST, UQ_HRR_OPEN_ID (UNIQUE/VALID), IX_HRR_QUEUE
(NONUNIQUE/VALID), and preservation of the original eight application tables.
The onboarding table now contains applications; it is not an empty fresh schema.
**Do not rerun either migration or recovery on this populated local database.**

Operator-verified fictional demonstration records are request 100000 REJECTED;
100001 APPROVED, hospital 100000; and 100002 APPROVED through the website,
hospital 100001, reviewed_by WEB_ADMIN:100022. The latter hospital is active.
React hospital registration, Administrator Login/Dashboard and Java review APIs
are implemented. See [the review workflow](../../backend/ADMIN_HOSPITAL_REVIEW.md)
for live-test scope and [web authentication](../../backend/WEB_ADMIN_AUTH.md) for
the offline application ADMIN setup utility and localhost-only WEB_ADMIN_ENABLED
policy. SYSTEM remains the local JDBC identity; separate Oracle provisioning
was not completed. The restricted CLI remains separate and refuses SYSTEM.
This is an academic demonstration, not government verification or production
security. This documentation update did not access or change Oracle.

## Historical installation procedure

The instructions below preserve the original prerequisites, corrected fresh
installation and partial-installation recovery procedure. Zero-row expectations
and old counts describe those historical installation stages, not current data.
A fresh installation uses corrected `001_hospital_onboarding.sql`; it does not
also run recovery. Recovery applies only to the exact partial state it checks.
Neither script is an application startup step or a seed/reset operation.

This is the only migration source. Do not copy it into the outer workspace
`database/` folder. Numbered operator-run scripts were introduced without a
migration framework or ledger. The migration SQL itself changes no Java/React
code. Do not run baseline, sample-data or existing `tests.sql` scripts as recovery.

## Historical observed prerequisites (before migration 001)

SELECT-only inspection on 2026-10-25 found Oracle XE 21c 21.3, PDB `XEPDB1`,
all eight application tables owned by `SYSTEM`, and all eight `SEQ_*` sequences
incrementing by one without cycling. HOSPITAL has the ten baseline columns,
an enabled/validated primary key, and enabled/validated active, pincode and
coordinate checks. USERS permitted ADMIN; the administrator portal had not yet
been implemented. At that initial inspection no onboarding objects were found.
Inspection used local OS authentication and CDB dictionary views;
**do not run this migration as SYSDBA**.

The migration creates only a sequence, table and indexes; it never consumes an
existing sequence, writes existing rows, changes grants, or changes authentication.
Its only dependency is an FK to the existing HOSPITAL primary key. Implemented
approval inserts a hospital and updates its application atomically; no staff
account is created. Existing GET /api/hospitals lists newly active hospitals.

## Before execution (fresh installation only)

Use a fresh SQL*Plus session with no unrelated uncommitted work. An authorized
operator must connect directly as the existing object owner. Current local owner
is SYSTEM; changing owners/provisioning a new application account is outside this
migration. Do not change CURRENT_SCHEMA as a substitute for owner authentication.
Have an appropriate backup and run in a maintenance window. Only one operator
should run migration 001. Review and approve the SQL before execution.

From the repository root, start an interactive session (password prompted):

```powershell
sqlplus -L SYSTEM@//localhost:1521/XEPDB1
```

Verify metadata before DDL; the following statements are read-only:

```sql
SHOW USER
SHOW CON_NAME
SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS current_schema FROM dual;
SELECT table_name FROM user_tables WHERE table_name IN
 ('HOSPITAL','USERS','HOSPITAL_STAFF','DONOR','BLOOD_BAGS','REQUEST','ALLOCATION','BAG_STATUS_LOG');
SELECT column_name,data_type,data_length,char_used,data_precision,data_scale,nullable
 FROM user_tab_columns WHERE table_name='HOSPITAL' ORDER BY column_id;
SELECT constraint_name,constraint_type,status,validated,search_condition_vc
 FROM user_constraints WHERE table_name='HOSPITAL' ORDER BY constraint_name;
SELECT constraint_name,column_name,position FROM user_cons_columns
 WHERE table_name='HOSPITAL' ORDER BY constraint_name,position;
SELECT sequence_name,increment_by,cycle_flag,last_number FROM user_sequences
 WHERE sequence_name IN ('SEQ_HOSPITAL','SEQ_USERS','SEQ_HOSPITAL_STAFF','SEQ_DONOR',
 'SEQ_BLOOD_BAGS','SEQ_REQUEST','SEQ_ALLOCATION','SEQ_BAG_STATUS_LOG');
SELECT object_name,object_type FROM user_objects WHERE object_name IN
 ('HOSPITAL_REGISTRATION_REQUEST','SEQ_HOSP_REG_REQUEST','PK_HRR','UQ_HRR_HOSPITAL','UQ_HRR_OPEN_ID','IX_HRR_QUEUE');
SELECT privilege FROM session_privs WHERE privilege IN
 ('CREATE TABLE','CREATE SEQUENCE','UNLIMITED TABLESPACE');
SELECT default_tablespace FROM user_users;
SELECT tablespace_name,bytes,max_bytes FROM user_ts_quotas;
```

Expected: eight tables; HOSPITAL matches the baseline contract, including the
actual check expressions; eight sequences; no target objects. Confirm CREATE
TABLE/SEQUENCE and quota or UNLIMITED TABLESPACE in the default tablespace;
owner-created indexes also require space. Confirm no conflicting HRR constraint
names (the migration checks these). Snapshot existing table counts if required
by your deployment procedure, without exporting patient/contact data.

Preflight checks are deliberately strict for the observed installation, including
the PDB name and hospital column byte sizes. A different deployment must be
reviewed, not bypassed. Named check constraints are checked for enabled/validated
state; the operator must inspect their actual expressions above.

## Execute only after approval

Exact SQL*Plus command, **from the repository root**:

```sql
SPOOL hospital_onboarding_001.log
@database/migrations/001_hospital_onboarding.sql
```

The script exits SQL*Plus on success or error, closing the spool. Alternatively,
the complete invocation from PowerShell is:

```powershell
sqlplus -L SYSTEM@//localhost:1521/XEPDB1 @database/migrations/001_hospital_onboarding.sql
```

No password belongs in a command, script or committed log. Do not connect with
the application running as a migration runner. Successful output and exit status
are necessary but must be followed by the metadata postflight below.

## Read-only postflight (new connection)

```sql
SELECT object_name,object_type,status FROM user_objects WHERE object_name IN
 ('HOSPITAL_REGISTRATION_REQUEST','SEQ_HOSP_REG_REQUEST','PK_HRR','UQ_HRR_HOSPITAL','UQ_HRR_OPEN_ID','IX_HRR_QUEUE')
 ORDER BY object_name;
SELECT column_name,data_type,data_length,nullable,virtual_column,data_default
 FROM user_tab_cols WHERE table_name='HOSPITAL_REGISTRATION_REQUEST' AND hidden_column='NO' ORDER BY column_id;
SELECT constraint_name,constraint_type,status,validated,search_condition_vc
 FROM user_constraints WHERE table_name='HOSPITAL_REGISTRATION_REQUEST' ORDER BY constraint_name;
SELECT index_name,uniqueness,status FROM user_indexes
 WHERE table_name='HOSPITAL_REGISTRATION_REQUEST';
SELECT index_name,column_position,column_expression FROM user_ind_expressions
 WHERE table_name='HOSPITAL_REGISTRATION_REQUEST' ORDER BY index_name,column_position;
SELECT sequence_name,min_value,max_value,increment_by,cycle_flag,cache_size
 FROM user_sequences WHERE sequence_name='SEQ_HOSP_REG_REQUEST';
SELECT COUNT(*) AS applications FROM hospital_registration_request;
```

Expected: new objects VALID, indexes VALID, constraints ENABLED/VALIDATED;
sequence maximum 9999999999 matching NUMBER(10); zero applications immediately
after a fresh installation, before submissions. The current local database is
populated and must not be expected to have zero applications. Do not select
NEXTVAL to verify a sequence. Compare existing table counts if captured before.
Constraint/concurrency insert tests belong in a dedicated disposable schema and
need separate authorization; none are run by this migration.

## Data contract and duplicate policy

* ID: registration_request_id NUMBER(10), supplied explicitly from
  SEQ_HOSP_REG_REQUEST.NEXTVAL by the submission DAO. No trigger or identity column.
* Hospital: hospital_name 150, address 300, district 60, pincode 6, nullable paired
  latitude/longitude NUMBER(9,6). Byte limits intentionally match HOSPITAL.
* Official contact: official_phone 20 and official_email 254, required.
* Identity: registration_authority 80 and jurisdiction_code 30 are **canonical
  uppercase codes** containing only A-Z, 0-9 and underscore. A real-world service
  and CLI need a controlled authority/jurisdiction vocabulary; aliases
  must not become distinct identities. These codes are not representative names.
* registration_number 100 accepts ASCII letters/digits, spaces, hyphens, slash
  and dot. Virtual registration_key uppercases and removes ASCII spaces and
  `-/.`. Thus `ab-12/3`, `AB123` and `Ab 12.3` collide for the same authority and
  jurisdiction. Review this equivalence rule with the intended issuing authority;
  punctuation-significant identifiers require a revised policy before execution.
* Representative: name 100, title 80, email 254, phone 20; all required. No password
  or user-account FK exists. Emails must be lowercase/trimmed and have a basic
  address shape; phones have basic format checks, not proof of contact ownership.
* status defaults to PENDING. submitted_at defaults to SYSTIMESTAMP. TIMESTAMP(6)
  matches existing conventions and has no timezone; reviewers must use database
  timestamps consistently. reviewed_at must not precede submitted_at.
* reviewed_by 128 records the authenticated decision source: the restricted CLI
  uses its dedicated database reviewer identity; website decisions use
  WEB_ADMIN:<authenticated USERS user ID>. It is not supplied by the applicant.
  verification_notes 2000 and rejection_reason 1000 record decisions. The schema
  checks their presence, not whether a caller is authorized or evidence is true.
* approved_hospital_id NUMBER(10) is nullable, unique, and references HOSPITAL.
  There is no cascading delete. The FK does not itself verify IS_ACTIVE='Y'.
* Pending rows have no decision metadata or hospital link. Approved rows require
  reviewer, review time, verification notes and hospital link, with no rejection
  reason. Rejected rows require reviewer, review time, notes and rejection reason,
  and cannot link a hospital. CHECK expressions explicitly reject missing values.
* Unique UQ_HRR_OPEN_ID reserves (authority, jurisdiction, registration_key) for
  both pending and approved applications. Concurrent duplicates fail with
  ORA-00001; the submission API translates this into a safe conflict response.
  All indexed expressions are NULL for rejected rows, allowing a fresh application
  while retaining rejection history. Emails, hospital names and phone numbers
  are not unique because legitimate institutions may share them.
* IX_HRR_QUEUE supports status/time/ID review ordering. UQ_HRR_HOSPITAL prevents
  more than one approved application linking the same hospital and indexes the FK.

## Risks, permissions and recovery

Oracle DDL implicitly commits; WHENEVER SQLERROR ... ROLLBACK **cannot undo a
created sequence/table/index**. A quota, privilege or later DDL error can leave a
partial installation. The script intentionally aborts on any rerun, including a
completed installation. Keep the transcript; inspect metadata and prepare an
operator-reviewed recovery/forward-fix. No automated cleanup or down script is
provided, and no object should be dropped casually after applications exist.

No grants or reviewer accounts are created by these migrations. The current
local demonstration uses existing SYSTEM credentials under the localhost-only
web-admin policy; it does not provision separate Oracle accounts. CHECK
constraints do not enforce administrator authorization, immutable terminal
states or hospital active status: the approved Java transactions enforce these
application invariants. SYSTEM can bypass them through direct SQL. Least-
privilege access and deployment security require separate design before public
exposure. The restricted CLI still requires a dedicated reviewer identity.

Legacy HOSPITAL rows have no official registration key. Therefore database
uniqueness cannot detect every legacy duplicate or impersonation. Review code
checks names, address/pincode and phone across active/inactive hospitals. Actual
registration, representative authority and licensing need independent checks in
any real-world workflow. The local academic website explicitly records that
official government verification was not performed. No existing hospital is
automatically linked, updated, activated, deduplicated or reseeded by migration.

## Historical recovery from the observed ORA-54034 partial installation

Before recovery, read-only inspection confirmed the table had 24 visible columns, zero
rows and 27 enabled/validated constraints (including 16 NOT NULL checks).
PK_HRR and UQ_HRR_HOSPITAL are valid unique indexes. SEQ_HOSP_REG_REQUEST is valid,
with min 1, max 9999999999, increment 1, NOCYCLE, cache 20, LAST_NUMBER 100000.
UQ_HRR_OPEN_ID and IX_HRR_QUEUE were absent at that stage. All eight original tables were VALID,
their 91 constraints remain enabled/validated, their sequence metadata is
unchanged, and counts match the prior snapshot: hospital 9, users 13, staff 5,
donor 19, bags 49, request 15, allocation 5, log 57. Counts and metadata alone
cannot prove every field value is unchanged; no historical row hashes exist.

The original function-based index nested CASE around virtual REGISTRATION_KEY.
Oracle rejects that with ORA-54034. The corrected expression expands the exact
normalization over stored REGISTRATION_NUMBER instead. The virtual column,
table, sequence, constraints and existing indexes are preserved.
Oracle documentation: https://docs.oracle.com/en/error-help/db/ora-54034/

The separate `001_recover_hospital_onboarding.sql` creates **only** UQ_HRR_OPEN_ID
and IX_HRR_QUEUE. The latter is the only remaining DDL after the failed index in
the original script. No table, sequence, constraints, grants or data are modified.
Its strict preflight checks the observed partial state, including an empty table;
unexpected state aborts before DDL. Do not accept writes/submissions or permit
parallel schema changes during recovery.

For a different installation in that exact partial state, only after separate
approval, from the repository root (not for the completed local database):

```powershell
sqlplus -L SYSTEM@//localhost:1521/XEPDB1 @database/migrations/001_recover_hospital_onboarding.sql
```

Use SQL*Plus's password prompt; never put passwords in commands or logs. Capture
output, check the process exit code and inspect for ORA-/SP2-/OS errors. Then run
the read-only postflight above and recount original tables. Expect four valid
indexes on the onboarding table (two original plus the recovered two), zero rows,
unchanged table/sequence and all constraints still enabled/validated.

Second execution deliberately fails preflight if either target index exists,
before creating anything. It is fail-safe, not a silent successful no-op. If only
the first recovery index succeeds and the second fails, stop and inspect; do not
rerun, drop or automatically clean up. DDL implicit commits still apply.

The local recovery was subsequently executed and both indexes were verified
VALID; subsequent submission and website review tests used the populated table.
The earlier zero-row/sequence/count snapshot above remains historical evidence,
not a current count or permission to rerun DDL. Automated test doubles do not
establish live Oracle concurrency behavior; additional contention/fault tests
remain separately authorized work.
