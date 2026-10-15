# Schema gaps and assumptions

## Why this file exists

The repository, the existing database scripts and the table definitions were **not available** when these scripts were written, so no existing schema could be inspected. Everything below is therefore an assumption, not a finding. If your repository already has these tables, compare it against this list first and adapt the scripts; do not run `schema_baseline.sql`.

## Information needed from the project

| Needed | Why |
|---|---|
| Oracle version (`SELECT banner FROM v$version`) | Scripts assume 12.1 or later (`FOR UPDATE SKIP LOCKED`, 30-character identifiers). |
| Real table and column names for hospital, users, staff, donor, bags, request, allocation, log | Every script uses the names in the table below. |
| Real status values for bags, requests and allocations | Used in views, procedures and tests. |
| How primary keys are generated (sequence, identity, trigger) | The procedures call `seq_allocation.NEXTVAL` and `seq_bag_status_log.NEXTVAL`. |
| Whether `REQUEST` stores the hospital that should supply the blood | Dispatch reads stock from `REQUEST.HOSPITAL_ID`. |
| Whether one bag is always one unit | Views count bags with `COUNT(*)`. |
| Existing partial-fulfilment rule, if any | The procedure currently defaults to allowing partial fulfilment (`p_allow_partial = 'Y'`). |
| Password hashing library chosen by the Java backend | Only the column size (`PASSWORD_HASH VARCHAR2(255)`) matters to SQL. |

## Assumed schema (`schema_baseline.sql`)

| Table | Key columns | Notes |
|---|---|---|
| `HOSPITAL` | `HOSPITAL_ID`, `NAME`, `ADDRESS`, `DISTRICT`, `PINCODE`, `LATITUDE`, `LONGITUDE`, `IS_ACTIVE` | Coordinates nullable; both or neither. |
| `USERS` | `USER_ID`, `EMAIL` (unique, lowercase), `PASSWORD_HASH`, `USER_ROLE`, `ACCOUNT_STATUS` | Roles: `CITIZEN`, `HOSPITAL_STAFF`, `ADMIN`. |
| `HOSPITAL_STAFF` | `STAFF_ID`, `USER_ID` (unique), `HOSPITAL_ID` | One hospital per staff user. |
| `DONOR` | `DONOR_ID`, `DONOR_CODE`, `BLOOD_GROUP` | `(DONOR_ID, BLOOD_GROUP)` unique so a bag's group must match its donor. |
| `BLOOD_BAGS` | `BAG_ID`, `BAG_CODE`, `DONOR_ID`, `HOSPITAL_ID`, `BLOOD_GROUP`, `COLLECTION_DATE`, `EXPIRY_DATE`, `STATUS` | Statuses `AVAILABLE`, `RESERVED`, `ISSUED`, `EXPIRED`, `DISCARDED`. One row = one unit. |
| `REQUEST` | `REQUEST_ID`, `BLOOD_GROUP`, `UNITS_REQUIRED`, `UNITS_ALLOCATED`, `PRIORITY`, `HOSPITAL_ID`, `STATUS`, `PROCESSED_AT`, `REMARKS` | Statuses `PENDING`, `FULFILLED`, `PARTIALLY_FULFILLED`, `REJECTED`, `CANCELLED`. |
| `ALLOCATION` | `ALLOCATION_ID`, `REQUEST_ID`, `BAG_ID`, `ALLOCATED_AT`, `ALLOCATED_BY`, `STATUS` | Statuses `RESERVED`, `ISSUED`, `RELEASED`. Unique function-based index allows only one live allocation per bag. |
| `BAG_STATUS_LOG` | `LOG_ID`, `BAG_ID`, `OLD_STATUS`, `NEW_STATUS`, `CHANGED_AT`, `CHANGED_BY`, `REASON`, `REQUEST_ID` | `OLD_STATUS` is NULL for the first entry of a bag. |

## Schema changes that need your approval (only if your existing tables differ)

1. **`ALLOCATION` unique index on live allocations** (`uq_alloc_active_bag`). If your table lacks it, adding it is non-destructive but will fail if duplicate live allocations already exist. Check first:
   ```sql
   SELECT bag_id, COUNT(*) FROM allocation
    WHERE status IN ('RESERVED','ISSUED') GROUP BY bag_id HAVING COUNT(*) > 1;
   ```
2. **`REQUEST.PROCESSED_AT` and `REQUEST.REMARKS`**: written by the dispatch procedure. If missing, add the columns or remove those two assignments.
3. **`BAG_STATUS_LOG.REQUEST_ID`**: links log rows to the request that caused them. Optional; remove it from the two INSERTs if you do not want it.
4. **Statuses**: if the existing CHECK constraints use other words, edit the constants at the top of `PRC_EMERGENCY_DISPATCH` and the literals in `PRC_ALLOCATE_FEFO`, `views.sql` and `tests.sql`. Do not loosen constraints just to make the seed data load.

## Pincode to coordinates (not provided)

The frontend sends a pincode, but no pincode-to-coordinate data exists in the project, and none is invented here. Options for the backend, in order of preference:

1. Load a licensed or open pincode dataset into a lookup table you control, for example:
   ```sql
   -- PROPOSED, not created by any script
   CREATE TABLE pincode_location (
     pincode    VARCHAR2(6) PRIMARY KEY,
     latitude   NUMBER(9,6) NOT NULL,
     longitude  NUMBER(9,6) NOT NULL,
     source     VARCHAR2(100) NOT NULL
   );
   ```
2. Or let the backend call a geocoding service of your choice and pass the resulting latitude/longitude to `PRC_NEARBY_HOSPITALS`.
3. As a fallback, filter by exact `HOSPITAL.PINCODE` or by district; this is not a distance search.
