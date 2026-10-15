# Blood Bank Management System: Oracle database scripts

## Status of this delivery

* The scripts were written **without access to the project repository or to an Oracle database**. They target the schema assumed in `schema_gaps.md`; adapt names if your real schema differs.
* **Nothing here has been executed on Oracle.** Checks actually performed, none of them against Oracle: 147 plain SQL statements (DDL, views, seed inserts) parsed with an Oracle-dialect parser; the real DDL and seed script loaded into SQLite through a mechanical translation (constraints, foreign keys, rerun safety); the dispatch algorithm replayed in Python against that data to confirm the expected FEFO bag sets, outcomes and rejections. The PL/SQL bodies were reviewed by hand and **not** compiled. Oracle compile results and the PL/SQL tests are for you to run; expected results are listed below, not observed ones.

## Prerequisites

* Oracle Database 12.1 or later (tested design target: 19c/21c XE). SQL*Plus or SQLcl.
* A schema user with `CREATE TABLE, VIEW, PROCEDURE, SEQUENCE`.
* Do not run `schema_baseline.sql` if your project already has these tables.

## Files

| File | Purpose |
|---|---|
| `schema_baseline.sql` | Proposed tables, sequences, indexes (only if you have no schema). Refuses to run if the tables exist. |
| `views.sql` | `V_DASHBOARD_INVENTORY`, `V_DASHBOARD_TOTALS`, `V_AVAILABLE_BAG_DETAIL`, `V_REQUEST_ALLOCATION_DETAIL` |
| `procedures.sql` | `FN_IS_RBC_COMPATIBLE`, `FN_HAVERSINE_KM`, `PRC_ALLOCATE_FEFO`, `PRC_EMERGENCY_DISPATCH`, `PRC_EXPIRE_STALE_BAGS`, `PRC_NEARBY_HOSPITALS` |
| `sample_data.sql` | Fictional Tamil Nadu test data (reserved ID ranges, safe to rerun) |
| `tests.sql` | Self-checking tests with before/after queries; ends with `ROLLBACK` |
| `api_database_contract.md` | What the Java backend calls and how to read the results |
| `schema_gaps.md` | Assumptions and changes needing approval |

## Execution order

```
sqlplus APP_USER/APP_PASSWORD@//HOST:1521/SERVICE

SQL> @schema_baseline.sql     -- only if no schema exists yet
SQL> @views.sql
SQL> @procedures.sql
SQL> @sample_data.sql
SQL> @tests.sql
```

Verify compilation:

```sql
SELECT object_name, object_type, status
  FROM user_objects
 WHERE object_name IN ('V_DASHBOARD_INVENTORY','V_DASHBOARD_TOTALS','V_AVAILABLE_BAG_DETAIL',
       'V_REQUEST_ALLOCATION_DETAIL','FN_IS_RBC_COMPATIBLE','FN_HAVERSINE_KM','PRC_ALLOCATE_FEFO',
       'PRC_EMERGENCY_DISPATCH','PRC_EXPIRE_STALE_BAGS','PRC_NEARBY_HOSPITALS')
 ORDER BY object_type, object_name;
-- expect STATUS = VALID for all 10 rows

SELECT name, type, line, text FROM user_errors ORDER BY name, sequence;
-- expect no rows
```

## Rules the code follows

* **Available stock** = `STATUS = 'AVAILABLE'` and `EXPIRY_DATE > TRUNC(SYSDATE)`. A bag expiring today counts as expired. Views and procedures use the same rule.
* **One bag = one unit**, counted with `COUNT(*)`; inventory is aggregated from `BLOOD_BAGS` alone, so no join can double-count.
* **Compatibility** (red cells), implemented in `FN_IS_RBC_COMPATIBLE`:

  | Patient | Can receive from |
  |---|---|
  | A+ | A+, A-, O+, O- |
  | A- | A-, O- |
  | B+ | B+, B-, O+, O- |
  | B- | B-, O- |
  | AB+ | all eight groups |
  | AB- | A-, B-, AB-, O- |
  | O+ | O+, O- |
  | O- | O- |

* **FEFO order**: `EXPIRY_DATE`, then `COLLECTION_DATE`, then `BAG_ID`. Pure FEFO ignores group, so an O- bag that expires first is used for an O+ patient (see request 97004). If you want to protect O- stock, add an ordering term in the cursor of `PRC_ALLOCATE_FEFO`; this is a policy decision.
* **Transactions**: no procedure commits. The caller commits or rolls back. `PRC_EMERGENCY_DISPATCH` uses savepoints so a refused or failed call leaves no partial changes and holds no leftover lock.
* **Partial fulfilment**: not defined in your documents, so the chosen default is *allowed* (`p_allow_partial='Y'`): reserve what exists, status `PARTIALLY_FULFILLED`. With `'N'`, nothing is kept unless the full quantity exists. Zero compatible stock sets the request to `REJECTED`; change `c_status_no_stock` if you prefer it to stay `PENDING`.
* **RESERVED is not ISSUED.** Dispatch never marks a bag issued.

### Concurrency

1. The request row is locked first (`FOR UPDATE WAIT 5`). Two sessions processing the same request are serialised; the second sees a non-`PENDING` request and returns `NOT_ELIGIBLE`.
2. Candidate bags are read without locks, then each is locked one at a time with `FOR UPDATE SKIP LOCKED` and re-checked for status and expiry. Only the bags actually reserved are locked, and a bag locked by another session is skipped, never double-booked.
3. A unique index on `ALLOCATION` (one live allocation per bag) is a final safety net.
4. Consequence: while another transaction holds the earliest-expiring bags uncommitted, a concurrent request takes the next-earliest ones, and may end up partial although the other session later releases stock. Keep dispatch transactions short.

Manual two-session demonstration (do not run `tests.sql` in between):

```
-- In BOTH sessions first declare the bind variables, one per line:
VARIABLE o VARCHAR2(30)
VARIABLE m VARCHAR2(500)
VARIABLE r NUMBER
VARIABLE u NUMBER

-- Session 1                                        -- Session 2
EXEC prc_emergency_dispatch(97012,'S1',NULL,'Y',:o,:r,:u,:m)   -- do not commit
                                                    EXEC prc_emergency_dispatch(97013,'S2',NULL,'Y',:o,:r,:u,:m)
                                                    -- expect PARTIAL, 1 unit (bag 95703)
COMMIT;                                             COMMIT;
-- expect: 3 distinct bags reserved in total (95701, 95702, 95703), none twice
```

## Test data overview

| Request | Scenario | Expected result |
|---|---|---|
| 97001 B+ x3 @90001 | FEFO, compatibility, tie-break, stale/expired/reserved/issued/discarded bags present | `SUCCESS`, bags 95004, 95005, 95002 |
| 97002 AB- x4 @90002 | only 3 compatible bags | `PARTIAL`, 95102, 95101, 95103 |
| 97003 O- x2 @90003 | only an expired-by-date and a reserved O- | `INSUFFICIENT_STOCK`, request `REJECTED` |
| 97004 O+ x2 @90004 | O- bag expires first | `SUCCESS`, 95302, 95303 |
| 97005 AB+ x2 @90005 | universal recipient | `SUCCESS`, 95401, 95403 |
| 97006 A- x1 @90006 | | `SUCCESS`, 95502 |
| 97014 A+ x5 @90006 | partial fulfilment off, 3 compatible | `INSUFFICIENT_STOCK`, nothing kept |
| 97012/97013 B- x2 @90008 | three B- bags for four units | `SUCCESS` (95701, 95702), then `PARTIAL` (95703) |
| 97007, 97009, 97010, 97011 | cancelled, history, authorization case | `NOT_ELIGIBLE` / `NOT_AUTHORIZED` |

Hospital 90009 has no coordinates and must never appear in nearby results. Test accounts use `@example.test` e-mails and a placeholder hash; they cannot log in.

`sample_data.sql` deletes only rows in these ID ranges before inserting: hospitals 90001-90009, users 91001-91099, staff 92001-92099, donors 93001-93099, bags 95001-95999, requests 97001-97099 (plus the allocation and log rows pointing at them), then commits.

## Expected output of `tests.sql`

* Section 1 prints the eight-row compatibility matrix; all checks `PASS`.
* Section 2: hospital 90001 B+ shows 3 available units, nearest expiry today + 4 days.
* Section 3: request 97001 goes `PENDING` to `FULFILLED`, 3 allocation rows in order 95004, 95005, 95002, three `AVAILABLE -> RESERVED` log rows, and the dashboard B+ count drops from 3 to 1.
* Section 4: outcomes as in the table above; invalid IDs give `NOT_FOUND`/`INVALID_PARAMETER`; the helper raises ORA-20003/ORA-20004 for bad units and groups.
* Section 5: 14 reservations in total, none expired, incompatible or cross-hospital.
* Section 6: 200 km around Chennai returns hospitals 90001, 90002, 90007, 90008 only.
* Last lines: `ALL TESTS PASSED`, then `tests.sql finished; changes rolled back.` A failure prints `FAIL ...` lines and exits non-zero.

## Useful queries

```sql
SELECT * FROM v_dashboard_inventory WHERE hospital_id = 90007 ORDER BY blood_group;
SELECT * FROM v_dashboard_totals ORDER BY blood_group;
SELECT bag_id, blood_group, status, expiry_date FROM blood_bags WHERE hospital_id = 90001
 ORDER BY expiry_date, collection_date, bag_id;
VARIABLE rc REFCURSOR
EXEC prc_nearby_hospitals(12.9714, 79.1456, 25, NULL, :rc)
PRINT rc
```

## Screenshots to capture (from a real SQL*Plus run)

Use a terminal window wide enough for `SET LINESIZE 220`; include the prompt/connection line so the session is visible.

1. Object compile check: the `user_objects` query (10 rows `VALID`) and the empty `user_errors` query.
2. `@sample_data.sql` finishing, then the counts: `SELECT COUNT(*) FROM blood_bags WHERE bag_id BETWEEN 95001 AND 95999;` (49).
3. Test section 1: compatibility matrix and its PASS lines.
4. Test section 2: `V_DASHBOARD_INVENTORY` for hospital 90001 and the PASS lines.
5. Test section 3: BEFORE bag list (note column showing `ELIGIBLE`, `incompatible with B+`, `expired by date`), dispatch PASS lines, AFTER bag list, `ALLOCATION`, `REQUEST` and `BAG_STATUS_LOG` rows.
6. Test section 4: partial (97002), insufficient (97003), duplicate (97001 second call), invalid IDs.
7. Test section 5 and section 6 (allocations table and nearby-hospital result).
8. The final `ALL TESTS PASSED` line.

Capture them yourself from your own run; do not reuse output from anywhere else.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `ORA-20001 schema_baseline.sql refused to run` | Tables already exist. Use your schema; see `schema_gaps.md`. |
| `ORA-00942 table or view does not exist` while compiling | Run order, or a table/column name differs from the assumption. |
| `PLS-00201` or `Warning: Procedure created with compilation errors` | Check `SELECT * FROM user_errors`; `procedures.sql` also stops with ORA-20002 if any unit fails. |
| `ORA-02290` or `ORA-02291` while loading `sample_data.sql` | Your constraints or status words differ. Adapt the data; do not weaken constraints. |
| `ORA-00001` on a unique index while loading | Leftover rows with test IDs from another script; check the ID ranges. |
| `ORA-00054` or `ORA-30006` from dispatch | Another session holds the request row; dispatch returns `BUSY` after 5 seconds. Commit or roll back the other session. |
| Tests fail on `Section 6` hospital list | Real hospitals nearby do not affect the check (only IDs 90001-90009 are compared); a failure means the Haversine function or view changed. |
| Garbled `&` prompts | Scripts set `DEFINE OFF`; make sure you ran them with SQL*Plus/SQLcl, not a client that substitutes variables. |
