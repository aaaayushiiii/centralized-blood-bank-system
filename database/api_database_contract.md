# Java backend to database contract

All objects are in the application schema. No procedure here commits or rolls back: the Java service owns the transaction (`setAutoCommit(false)`, then `commit()` or `rollback()`).

## 1. Hospital inventory and dashboard

View `V_DASHBOARD_INVENTORY`: one row per active hospital per blood group (all 8 groups always present, zero units when empty).

| Column | Meaning |
|---|---|
| `HOSPITAL_ID`, `HOSPITAL_NAME`, `ADDRESS`, `DISTRICT`, `PINCODE`, `LATITUDE`, `LONGITUDE` | Hospital identity |
| `BLOOD_GROUP` | `A+ A- B+ B- AB+ AB- O+ O-` |
| `AVAILABLE_UNITS` | Bags with `STATUS='AVAILABLE'` and `EXPIRY_DATE > TRUNC(SYSDATE)` |
| `NEAREST_EXPIRY_DATE`, `LATEST_EXPIRY_DATE` | Min/max expiry of those bags (NULL when none) |
| `EXPIRING_WITHIN_7_DAYS` | Count of those bags expiring within 7 days |

```sql
-- hospital staff screen (hospital id comes from the login, see section 4)
SELECT blood_group, available_units, nearest_expiry_date, expiring_within_7_days
  FROM v_dashboard_inventory
 WHERE hospital_id = ?
 ORDER BY blood_group;

-- one group at one hospital
SELECT available_units FROM v_dashboard_inventory WHERE hospital_id = ? AND blood_group = ?;

-- consolidated dashboard
SELECT blood_group, total_available_units, hospitals_with_stock, nearest_expiry_date
  FROM v_dashboard_totals ORDER BY blood_group;

-- bag-level expiry monitoring for one hospital
SELECT bag_code, blood_group, expiry_date, days_to_expiry, fefo_rank
  FROM v_available_bag_detail WHERE hospital_id = ? ORDER BY expiry_date, bag_id;

-- bags held by a request
SELECT bag_code, blood_group, expiry_date, allocation_status, bag_status
  FROM v_request_allocation_detail WHERE request_id = ? ORDER BY allocation_id;
```

Always bind values (`PreparedStatement`); never concatenate IDs or blood groups into SQL. Validate `blood_group` against the eight allowed values before binding.

## 2. Nearby hospitals

`PRC_NEARBY_HOSPITALS(p_latitude, p_longitude, p_radius_km, p_recipient_group, p_result OUT SYS_REFCURSOR)`

| Param | Type | Notes |
|---|---|---|
| `p_latitude` / `p_longitude` | NUMBER | Required; -90..90 and -180..180. Resolved by the backend, not by this procedure (see `schema_gaps.md`). |
| `p_radius_km` | NUMBER | Required; greater than 0, at most 500. |
| `p_recipient_group` | VARCHAR2 | Optional. When set, only compatible donor groups are returned. Pass `NULL` for all groups. |

Cursor columns, in order: `HOSPITAL_ID, HOSPITAL_NAME, ADDRESS, DISTRICT, PINCODE, LATITUDE, LONGITUDE, DISTANCE_KM, BLOOD_GROUP, AVAILABLE_UNITS, NEAREST_EXPIRY_DATE`.

* One row per (hospital, blood group) with stock; group rows by `HOSPITAL_ID` in Java to build one card per hospital. Rows are sorted by distance, then hospital, then group.
* Hospitals without coordinates and groups with zero stock are excluded.
* Errors: `ORA-20010` bad coordinates, `ORA-20011` bad radius, `ORA-20012` unknown group (`SQLException.getErrorCode()` returns 20010 to 20012).

```java
try (CallableStatement cs = con.prepareCall("{call prc_nearby_hospitals(?,?,?,?,?)}")) {
    cs.setBigDecimal(1, lat); cs.setBigDecimal(2, lon); cs.setBigDecimal(3, radiusKm);
    cs.setString(4, recipientGroupOrNull);           // use setNull(4, Types.VARCHAR) for none
    cs.registerOutParameter(5, oracle.jdbc.OracleTypes.CURSOR);
    cs.execute();
    try (ResultSet rs = (ResultSet) cs.getObject(5)) { /* read rows */ }
}
```

Plain-SQL equivalent (for ad-hoc use):

```sql
SELECT * FROM (
  SELECT v.hospital_id, v.hospital_name, v.pincode, v.blood_group, v.available_units,
         fn_haversine_km(:lat, :lon, v.latitude, v.longitude) AS distance_km
    FROM v_dashboard_inventory v
   WHERE v.latitude IS NOT NULL AND v.longitude IS NOT NULL AND v.available_units > 0)
 WHERE distance_km <= :radius_km
 ORDER BY distance_km, hospital_id, blood_group;
```

## 3. Emergency dispatch

```
{call prc_emergency_dispatch(?, ?, ?, ?, ?, ?, ?, ?)}
```

| # | Name | Dir | Type | Notes |
|---|---|---|---|---|
| 1 | `p_request_id` | IN | NUMBER | Required |
| 2 | `p_actor` | IN | VARCHAR2(60) | Audit text, e.g. `USER:91011` |
| 3 | `p_actor_hospital_id` | IN | NUMBER | Hospital of the authenticated staff user; `NULL` skips the check (system jobs only) |
| 4 | `p_allow_partial` | IN | VARCHAR2 | `Y` or `N` |
| 5 | `p_outcome` | OUT | VARCHAR2 | See table |
| 6 | `p_units_requested` | OUT | NUMBER | |
| 7 | `p_units_allocated` | OUT | NUMBER | Units reserved by this call |
| 8 | `p_message` | OUT | VARCHAR2 | Text for logs or UI |

| `p_outcome` | Request status afterwards | Java action |
|---|---|---|
| `SUCCESS` | `FULFILLED` | `commit()`, return 200 |
| `PARTIAL` | `PARTIALLY_FULFILLED` | `commit()`, return 200 with shortfall |
| `INSUFFICIENT_STOCK` | `REJECTED` | `commit()` (the rejection is recorded), return 200/409 |
| `NOT_FOUND` | unchanged | `rollback()`, 404 |
| `NOT_ELIGIBLE` | unchanged | `rollback()`, 409 (already processed) |
| `NOT_AUTHORIZED` | unchanged | `rollback()`, 403 |
| `INVALID_QUANTITY`, `INVALID_HOSPITAL`, `INVALID_PARAMETER` | unchanged | `rollback()`, 400/422 |
| `BUSY` | unchanged | `rollback()`, 409/503, retry later |
| exception `ORA-20999` | unchanged (rolled back to start of call) | `rollback()`, 500 |

Rules:

* Bags become `RESERVED`, never `ISSUED`; physical issue is a separate step (update `ALLOCATION.STATUS` and `BLOOD_BAGS.STATUS` to `ISSUED` and add a log row).
* Calling it twice for the same request is safe: the second call returns `NOT_ELIGIBLE` and changes nothing.
* Stock is taken only from `REQUEST.HOSPITAL_ID`, in expiry order, among compatible, available, non-expired bags.

## 4. Authentication, registration and authorization queries

Passwords are never handled in SQL. The backend hashes with a dedicated password-hashing library (BCrypt, Argon2 or PBKDF2 via Spring Security or equivalent) and stores the resulting string in `USERS.PASSWORD_HASH`. No credentials appear in any script.

```sql
-- Login lookup (normalise the e-mail to lower case first)
SELECT u.user_id, u.password_hash, u.user_role, u.account_status,
       u.failed_login_count, hs.hospital_id
  FROM users u
  LEFT JOIN hospital_staff hs ON hs.user_id = u.user_id
 WHERE u.email = ?;
-- Java: reject unless account_status = 'ACTIVE'; verify the password with the hashing
-- library; return one generic "invalid credentials" message for unknown e-mail and wrong password.

-- After a successful login
UPDATE users SET last_login_at = SYSTIMESTAMP, failed_login_count = 0 WHERE user_id = ?;
-- After a failed login
UPDATE users SET failed_login_count = failed_login_count + 1 WHERE user_id = ?;
-- Optional lockout once the backend's threshold (for example 5) is reached
UPDATE users SET account_status = 'LOCKED'
 WHERE user_id = ? AND account_status = 'ACTIVE' AND failed_login_count >= ?;
-- Unlocking is an admin action; it resets failed_login_count and sets ACTIVE again.

-- Citizen registration (user_role fixed to CITIZEN by the server, never by the client)
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (seq_users.NEXTVAL, ?, LOWER(?), ?, ?, 'CITIZEN', 'ACTIVE');
-- ORA-00001 on UQ_USERS_EMAIL means the e-mail is already registered.

-- Staff creation (done by an admin, in one transaction)
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (seq_users.NEXTVAL, ?, LOWER(?), ?, ?, 'HOSPITAL_STAFF', 'ACTIVE');
INSERT INTO hospital_staff (staff_id, user_id, hospital_id, designation)
VALUES (seq_hospital_staff.NEXTVAL, seq_users.CURRVAL, ?, ?);
```

### Hospital staff must only see their own hospital

1. After login, put only the `user_id` (and role) into the session/JWT.
2. On every staff request, look the hospital up from that identity. The join to `USERS` makes a
   locked, disabled or re-roled account lose access immediately, even if its token is still valid:
   ```sql
   SELECT hs.hospital_id
     FROM hospital_staff hs
     JOIN users u ON u.user_id = hs.user_id
    WHERE hs.user_id = ?
      AND u.user_role = 'HOSPITAL_STAFF'
      AND u.account_status = 'ACTIVE';
   -- no row -> 403; never fall back to a hospital id from the request
   ```
3. Use that value, and only that value, as the `hospital_id` bind in inventory queries.
4. Ignore any `hospitalId` sent in the URL, body or headers for staff endpoints.
5. For dispatch, load the request, compare its `HOSPITAL_ID` to the derived one (return 403 on mismatch), and also pass the derived value as `p_actor_hospital_id`; the procedure repeats the check.
6. Citizen endpoints may read the nearby-hospital search and their own requests only (`WHERE requester_user_id = :authenticated_user_id`).
7. Authorization check for a staff action on one request (run before calling dispatch; return 404 rather than 403 if you do not want to reveal that the request exists):
   ```sql
   SELECT r.request_id, r.status
     FROM request r
    WHERE r.request_id = ? AND r.hospital_id = ?;   -- second bind = hospital id from step 2
   ```
8. Role checks (`CITIZEN`, `HOSPITAL_STAFF`, `ADMIN`) are enforced in the Java layer from `USERS.USER_ROLE`. The database holds the data for the decision but does not authenticate anyone: it has no knowledge of the logged-in person, and the dispatch procedure only trusts the `p_actor_hospital_id` the service passes in.
9. Password handling: hash with BCrypt, Argon2id or PBKDF2 through a maintained library and store only the output string (`PASSWORD_HASH`, 255 characters is enough for all three). Never log, return or select the hash anywhere except the login lookup. Seed accounts carry the placeholder `TEST-ONLY-NOT-A-REAL-HASH`, which no verifier accepts.
