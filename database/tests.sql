-- tests.sql
-- Repeatable tests. Run from SQL*Plus after schema, views.sql, procedures.sql:
--     sqlplus user/password@service @tests.sql
--
-- What it does:
--   * reloads the test data (@@sample_data.sql, which commits),
--   * runs every scenario, printing PASS/FAIL lines and before/after queries,
--   * ends with ROLLBACK, so the database returns to the freshly seeded state
--     and the script can be run again.
-- The procedures never commit, so the ROLLBACK undoes all test dispatches.
-- Exit status is non-zero if any check fails.

SET DEFINE OFF
SET SQLBLANKLINES ON
SET SERVEROUTPUT ON SIZE UNLIMITED
SET LINESIZE 220
SET PAGESIZE 100
SET FEEDBACK ON
SET TRIMSPOOL ON
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

COLUMN hospital_name FORMAT A38
COLUMN compatible_donor_groups FORMAT A40
COLUMN bag_code FORMAT A12
COLUMN note FORMAT A28
COLUMN remarks FORMAT A60
COLUMN reason FORMAT A48
COLUMN changed_by FORMAT A12
COLUMN allocated_by FORMAT A12

VARIABLE g_fail NUMBER
BEGIN :g_fail := 0; END;
/

@@sample_data.sql

PROMPT
PROMPT ===== 1. ABO/Rh red-cell compatibility matrix =====
SELECT r.bg AS recipient,
       LISTAGG(d.bg, ', ') WITHIN GROUP (ORDER BY d.bg) AS compatible_donor_groups
  FROM (SELECT 'A+' AS bg FROM dual UNION ALL SELECT 'A-' FROM dual UNION ALL
        SELECT 'B+' FROM dual UNION ALL SELECT 'B-' FROM dual UNION ALL
        SELECT 'AB+' FROM dual UNION ALL SELECT 'AB-' FROM dual UNION ALL
        SELECT 'O+' FROM dual UNION ALL SELECT 'O-' FROM dual) r
  JOIN (SELECT 'A+' AS bg FROM dual UNION ALL SELECT 'A-' FROM dual UNION ALL
        SELECT 'B+' FROM dual UNION ALL SELECT 'B-' FROM dual UNION ALL
        SELECT 'AB+' FROM dual UNION ALL SELECT 'AB-' FROM dual UNION ALL
        SELECT 'O+' FROM dual UNION ALL SELECT 'O-' FROM dual) d
    ON fn_is_rbc_compatible(d.bg, r.bg) = 'Y'
 GROUP BY r.bg
 ORDER BY r.bg;

DECLARE
  v_fail NUMBER := 0;
  v_n    NUMBER;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  SELECT COUNT(*)
    INTO v_n
    FROM (SELECT 'A+' AS bg FROM dual UNION ALL SELECT 'A-' FROM dual UNION ALL
          SELECT 'B+' FROM dual UNION ALL SELECT 'B-' FROM dual UNION ALL
          SELECT 'AB+' FROM dual UNION ALL SELECT 'AB-' FROM dual UNION ALL
          SELECT 'O+' FROM dual UNION ALL SELECT 'O-' FROM dual) r
    JOIN (SELECT 'A+' AS bg FROM dual UNION ALL SELECT 'A-' FROM dual UNION ALL
          SELECT 'B+' FROM dual UNION ALL SELECT 'B-' FROM dual UNION ALL
          SELECT 'AB+' FROM dual UNION ALL SELECT 'AB-' FROM dual UNION ALL
          SELECT 'O+' FROM dual UNION ALL SELECT 'O-' FROM dual) d
      ON fn_is_rbc_compatible(d.bg, r.bg) = 'Y';
  check_it('compatible pairs total 27 of 64', v_n = 27);
  check_it('O- donates to AB+',            fn_is_rbc_compatible('O-', 'AB+') = 'Y');
  check_it('AB+ does not donate to O-',    fn_is_rbc_compatible('AB+', 'O-') = 'N');
  check_it('A+ does not donate to B+',     fn_is_rbc_compatible('A+', 'B+') = 'N');
  check_it('B- donates to B+',             fn_is_rbc_compatible('B-', 'B+') = 'Y');
  check_it('B+ does not donate to B-',     fn_is_rbc_compatible('B+', 'B-') = 'N');
  check_it('O+ does not donate to O-',     fn_is_rbc_compatible('O+', 'O-') = 'N');
  check_it('NULL donor is not compatible', fn_is_rbc_compatible(NULL, 'A+') = 'N');
  check_it('unknown group is not compatible', fn_is_rbc_compatible('XX', 'A+') = 'N');
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== 2. V_DASHBOARD_INVENTORY (hospital 90001, before any dispatch) =====
SELECT hospital_id, blood_group, available_units, nearest_expiry_date, expiring_within_7_days
  FROM v_dashboard_inventory
 WHERE hospital_id = 90001
 ORDER BY blood_group;

DECLARE
  v_fail NUMBER := 0;
  v_n    NUMBER;
  v_raw  NUMBER;
  v_d    DATE;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  SELECT COUNT(*) INTO v_n FROM v_dashboard_inventory WHERE hospital_id = 90001;
  check_it('view has 8 blood-group rows per hospital', v_n = 8);

  SELECT available_units, nearest_expiry_date INTO v_n, v_d
    FROM v_dashboard_inventory WHERE hospital_id = 90001 AND blood_group = 'B+';
  check_it('90001 B+ available = 3 (stale, expired, reserved, issued, discarded excluded)', v_n = 3);
  check_it('90001 B+ nearest expiry = today + 4', v_d = TRUNC(SYSDATE) + 4);

  SELECT expiring_within_7_days INTO v_n
    FROM v_dashboard_inventory WHERE hospital_id = 90001 AND blood_group = 'B+';
  check_it('90001 B+ expiring within 7 days = 2', v_n = 2);

  SELECT available_units INTO v_n FROM v_dashboard_inventory WHERE hospital_id = 90001 AND blood_group = 'A-';
  check_it('90001 A- has zero stock row', v_n = 0);

  SELECT SUM(available_units) INTO v_n
    FROM v_dashboard_inventory WHERE hospital_id BETWEEN 90001 AND 90009;
  SELECT COUNT(*) INTO v_raw
    FROM blood_bags b JOIN hospital h ON h.hospital_id = b.hospital_id
   WHERE b.hospital_id BETWEEN 90001 AND 90009
     AND h.is_active = 'Y'
     AND b.status = 'AVAILABLE'
     AND b.expiry_date > TRUNC(SYSDATE);
  check_it('view total equals raw bag count (no double counting): ' || v_n || ' = ' || v_raw, v_n = v_raw);

  SELECT COUNT(*) INTO v_n FROM v_dashboard_totals;
  check_it('V_DASHBOARD_TOTALS has one row per blood group', v_n = 8);

  SELECT COUNT(*) INTO v_n
    FROM (SELECT DISTINCT blood_group FROM blood_bags WHERE bag_id BETWEEN 95001 AND 95999);
  check_it('test data covers all 8 blood groups', v_n = 8);

  -- the hospital id is looked up from the authenticated user, never taken from the client
  SELECT hs.hospital_id INTO v_n
    FROM hospital_staff hs JOIN users u ON u.user_id = hs.user_id
   WHERE hs.user_id = 91011 AND u.user_role = 'HOSPITAL_STAFF' AND u.account_status = 'ACTIVE';
  check_it('staff user 91011 resolves to hospital 90001', v_n = 90001);
  SELECT COUNT(DISTINCT v.hospital_id) INTO v_n
    FROM v_dashboard_inventory v
   WHERE v.hospital_id = (SELECT hs.hospital_id FROM hospital_staff hs WHERE hs.user_id = 91011);
  check_it('inventory query scoped by derived hospital id returns one hospital', v_n = 1);

  -- a disabled staff account must resolve to no hospital (change is rolled back at the end)
  UPDATE users SET account_status = 'DISABLED' WHERE user_id = 91011;
  SELECT COUNT(*) INTO v_n
    FROM hospital_staff hs JOIN users u ON u.user_id = hs.user_id
   WHERE hs.user_id = 91011 AND u.user_role = 'HOSPITAL_STAFF' AND u.account_status = 'ACTIVE';
  check_it('disabled staff account resolves to no hospital', v_n = 0);
  UPDATE users SET account_status = 'ACTIVE' WHERE user_id = 91011;
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== 3. FEFO dispatch for request 97001 (B+ x3 at hospital 90001) =====
PROMPT --- BEFORE: request ---
SELECT request_id, blood_group, units_required, units_allocated, status, hospital_id
  FROM request WHERE request_id = 97001;
PROMPT --- BEFORE: bags at hospital 90001 in FEFO order (expiry, collection, id) ---
SELECT bag_id, bag_code, blood_group, status, collection_date, expiry_date,
       expiry_date - TRUNC(SYSDATE) AS days_left,
       CASE
         WHEN status <> 'AVAILABLE'              THEN 'not available (' || status || ')'
         WHEN expiry_date <= TRUNC(SYSDATE)      THEN 'expired by date'
         WHEN fn_is_rbc_compatible(blood_group, 'B+') = 'N' THEN 'incompatible with B+'
         ELSE 'ELIGIBLE'
       END AS note
  FROM blood_bags
 WHERE hospital_id = 90001
 ORDER BY expiry_date, collection_date, bag_id;
PROMPT --- BEFORE: allocation rows and log rows for request 97001 ---
SELECT allocation_id, request_id, bag_id, status FROM allocation WHERE request_id = 97001;
SELECT log_id, bag_id, old_status, new_status, changed_by FROM bag_status_log WHERE request_id = 97001;

DECLARE
  v_fail    NUMBER := 0;
  v_outcome VARCHAR2(30);
  v_req     NUMBER;
  v_alloc   NUMBER;
  v_msg     VARCHAR2(500);
  v_bags    VARCHAR2(200);
  v_n       NUMBER;
  v_st      request.status%TYPE;
  v_ua      request.units_allocated%TYPE;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  prc_emergency_dispatch(
    p_request_id        => 97001,
    p_actor             => 'TEST_RUNNER',
    p_actor_hospital_id => 90001,
    p_allow_partial     => 'Y',
    p_outcome           => v_outcome,
    p_units_requested   => v_req,
    p_units_allocated   => v_alloc,
    p_message           => v_msg);
  DBMS_OUTPUT.PUT_LINE('outcome=' || v_outcome || ' requested=' || v_req || ' allocated=' || v_alloc || ' | ' || v_msg);

  check_it('R1 outcome SUCCESS', v_outcome = 'SUCCESS');
  check_it('R1 three units reserved', v_alloc = 3);

  SELECT LISTAGG(bag_id, ',') WITHIN GROUP (ORDER BY allocation_id) INTO v_bags
    FROM allocation WHERE request_id = 97001;
  check_it('R1 FEFO order is 95004,95005,95002 (got ' || v_bags || ')', v_bags = '95004,95005,95002');

  SELECT COUNT(*) INTO v_n FROM blood_bags WHERE bag_id IN (95004, 95005, 95002) AND status = 'RESERVED';
  check_it('R1 allocated bags are RESERVED (not ISSUED)', v_n = 3);

  SELECT COUNT(*) INTO v_n FROM blood_bags
   WHERE bag_id IN (95009, 95010, 95006, 95003, 95001, 95011) AND status = 'AVAILABLE';
  check_it('R1 incompatible/stale/later bags untouched', v_n = 6);

  SELECT status, units_allocated INTO v_st, v_ua FROM request WHERE request_id = 97001;
  check_it('R1 request FULFILLED with 3 units', v_st = 'FULFILLED' AND v_ua = 3);

  SELECT COUNT(*) INTO v_n FROM bag_status_log
   WHERE request_id = 97001 AND old_status = 'AVAILABLE' AND new_status = 'RESERVED'
     AND changed_by = 'TEST_RUNNER' AND bag_id IN (95004, 95005, 95002);
  check_it('R1 one AVAILABLE->RESERVED log row per bag', v_n = 3);

  SELECT COUNT(*) INTO v_n FROM bag_status_log WHERE request_id = 97001;
  check_it('R1 no extra log rows', v_n = 3);
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT --- AFTER: bags at hospital 90001 ---
SELECT bag_id, bag_code, blood_group, status, expiry_date
  FROM blood_bags WHERE hospital_id = 90001 ORDER BY expiry_date, collection_date, bag_id;
PROMPT --- AFTER: allocation rows for request 97001 ---
SELECT allocation_id, request_id, bag_id, allocated_by, status
  FROM allocation WHERE request_id = 97001 ORDER BY allocation_id;
PROMPT --- AFTER: request 97001 ---
SELECT request_id, blood_group, units_required, units_allocated, status, remarks
  FROM request WHERE request_id = 97001;
PROMPT --- AFTER: bag_status_log for request 97001 ---
SELECT log_id, bag_id, old_status, new_status, changed_by, reason
  FROM bag_status_log WHERE request_id = 97001 ORDER BY log_id;
PROMPT --- AFTER: dashboard view for 90001 (reserved bags no longer counted) ---
SELECT hospital_id, blood_group, available_units, nearest_expiry_date
  FROM v_dashboard_inventory WHERE hospital_id = 90001 ORDER BY blood_group;

DECLARE
  v_fail NUMBER := 0;
  v_n    NUMBER;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  SELECT available_units INTO v_n FROM v_dashboard_inventory WHERE hospital_id = 90001 AND blood_group = 'B+';
  check_it('90001 B+ available drops from 3 to 1', v_n = 1);
  SELECT available_units INTO v_n FROM v_dashboard_inventory WHERE hospital_id = 90001 AND blood_group = 'B-';
  check_it('90001 B- available drops from 1 to 0', v_n = 0);
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== 4. Remaining dispatch scenarios, duplicates and invalid input =====
DECLARE
  v_fail NUMBER := 0;
  v_out  VARCHAR2(30);
  v_rq   NUMBER;
  v_al   NUMBER;
  v_msg  VARCHAR2(500);
  v_bags VARCHAR2(200);
  v_st   request.status%TYPE;
  v_ua   request.units_allocated%TYPE;
  v_n    NUMBER;
  v_code NUMBER;

  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;

  PROCEDURE run_case (p_label IN VARCHAR2, p_req IN NUMBER, p_allow IN VARCHAR2,
                      p_exp_outcome IN VARCHAR2, p_exp_units IN NUMBER, p_exp_bags IN VARCHAR2,
                      p_exp_status IN VARCHAR2, p_exp_req_units IN NUMBER) IS
  BEGIN
    prc_emergency_dispatch(
      p_request_id    => p_req,
      p_actor         => 'TEST_RUNNER',
      p_allow_partial => p_allow,
      p_outcome       => v_out,
      p_units_requested => v_rq,
      p_units_allocated => v_al,
      p_message       => v_msg);
    DBMS_OUTPUT.PUT_LINE(p_label || ': outcome=' || v_out || ' allocated=' || v_al || ' | ' || v_msg);
    check_it(p_label || ' outcome ' || p_exp_outcome, v_out = p_exp_outcome);
    check_it(p_label || ' units returned ' || p_exp_units, v_al = p_exp_units);
    SELECT NVL(LISTAGG(bag_id, ',') WITHIN GROUP (ORDER BY allocation_id), '-')
      INTO v_bags FROM allocation WHERE request_id = p_req;
    check_it(p_label || ' bags [' || v_bags || '] expected [' || p_exp_bags || ']', v_bags = p_exp_bags);
    SELECT status, units_allocated INTO v_st, v_ua FROM request WHERE request_id = p_req;
    check_it(p_label || ' request ' || v_st || '/' || v_ua,
             v_st = p_exp_status AND v_ua = p_exp_req_units);
  END run_case;
BEGIN
  -- duplicate processing of the request handled in section 3
  prc_emergency_dispatch(97001, 'TEST_RUNNER', 90001, 'Y', v_out, v_rq, v_al, v_msg);
  check_it('R1 processed twice -> NOT_ELIGIBLE', v_out = 'NOT_ELIGIBLE' AND v_al = 0);
  SELECT COUNT(*) INTO v_n FROM allocation WHERE request_id = 97001;
  check_it('R1 duplicate call added no allocation rows', v_n = 3);
  SELECT COUNT(*) INTO v_n FROM bag_status_log WHERE request_id = 97001;
  check_it('R1 duplicate call added no log rows', v_n = 3);

  -- partial fulfilment: AB- recipient, only 3 compatible bags for 4 units
  run_case('R2 partial', 97002, 'Y', 'PARTIAL', 3, '95102,95101,95103', 'PARTIALLY_FULFILLED', 3);
  SELECT COUNT(*) INTO v_n FROM blood_bags WHERE bag_id IN (95104, 95105) AND status = 'AVAILABLE';
  check_it('R2 incompatible AB+/O+ bags (expiring first) not taken', v_n = 2);
  run_case('R2 reprocessed', 97002, 'Y', 'NOT_ELIGIBLE', 0, '95102,95101,95103', 'PARTIALLY_FULFILLED', 3);

  -- insufficient stock: only an expired-by-date, a reserved and incompatible bags
  run_case('R3 insufficient', 97003, 'Y', 'INSUFFICIENT_STOCK', 0, '-', 'REJECTED', 0);
  SELECT COUNT(*) INTO v_n FROM blood_bags WHERE bag_id = 95201 AND status = 'AVAILABLE';
  check_it('R3 expired-by-date bag 95201 not allocated', v_n = 1);
  SELECT COUNT(*) INTO v_n FROM allocation WHERE bag_id = 95204;
  check_it('R3 already-reserved bag 95204 not re-allocated', v_n = 1);

  -- full allocations
  run_case('R4 O+ x2', 97004, 'Y', 'SUCCESS', 2, '95302,95303', 'FULFILLED', 2);
  run_case('R5 AB+ x2', 97005, 'Y', 'SUCCESS', 2, '95401,95403', 'FULFILLED', 2);

  -- partial fulfilment disabled: 3 compatible bags for 5 units -> nothing kept
  run_case('R14 partial off', 97014, 'N', 'INSUFFICIENT_STOCK', 0, '-', 'REJECTED', 0);
  SELECT COUNT(*) INTO v_n FROM blood_bags WHERE bag_id IN (95501, 95502, 95503) AND status = 'AVAILABLE';
  check_it('R14 rolled-back bags are AVAILABLE again', v_n = 3);
  SELECT COUNT(*) INTO v_n FROM bag_status_log WHERE request_id = 97014;
  check_it('R14 rolled-back dispatch left no log rows', v_n = 0);

  run_case('R6 A- x1', 97006, 'Y', 'SUCCESS', 1, '95502', 'FULFILLED', 1);

  -- two requests competing for three B- bags, processed one after the other
  run_case('R12 B- x2', 97012, 'Y', 'SUCCESS', 2, '95701,95702', 'FULFILLED', 2);
  run_case('R13 B- x2', 97013, 'Y', 'PARTIAL', 1, '95703', 'PARTIALLY_FULFILLED', 1);

  -- invalid and ineligible requests
  run_case('R7 cancelled', 97007, 'Y', 'NOT_ELIGIBLE', 0, '-', 'CANCELLED', 0);

  prc_emergency_dispatch(99999999, 'TEST_RUNNER', NULL, 'Y', v_out, v_rq, v_al, v_msg);
  check_it('unknown request id -> NOT_FOUND', v_out = 'NOT_FOUND' AND v_rq IS NULL);

  prc_emergency_dispatch(NULL, 'TEST_RUNNER', NULL, 'Y', v_out, v_rq, v_al, v_msg);
  check_it('NULL request id -> INVALID_PARAMETER', v_out = 'INVALID_PARAMETER');

  prc_emergency_dispatch(97011, 'TEST_RUNNER', NULL, 'X', v_out, v_rq, v_al, v_msg);
  check_it('bad p_allow_partial -> INVALID_PARAMETER', v_out = 'INVALID_PARAMETER');

  prc_emergency_dispatch(97011, 'TEST_RUNNER', 90001, 'Y', v_out, v_rq, v_al, v_msg);
  check_it('staff of another hospital -> NOT_AUTHORIZED', v_out = 'NOT_AUTHORIZED' AND v_al = 0);
  SELECT status INTO v_st FROM request WHERE request_id = 97011;
  SELECT COUNT(*) INTO v_n FROM allocation WHERE request_id = 97011;
  check_it('NOT_AUTHORIZED left request 97011 PENDING with no allocations', v_st = 'PENDING' AND v_n = 0);

  -- helper-level validation (units are also protected by CK_REQUEST_UNITS in the table)
  v_code := 0;
  BEGIN
    prc_allocate_fefo(97001, 90001, 'B+', 0, 'TEST_RUNNER', v_al);
  EXCEPTION WHEN OTHERS THEN v_code := SQLCODE;
  END;
  check_it('FEFO helper rejects 0 units (ORA-20003)', v_code = -20003);

  v_code := 0;
  BEGIN
    prc_allocate_fefo(97001, 90001, 'B+', 1.5, 'TEST_RUNNER', v_al);
  EXCEPTION WHEN OTHERS THEN v_code := SQLCODE;
  END;
  check_it('FEFO helper rejects fractional units (ORA-20003)', v_code = -20003);

  v_code := 0;
  BEGIN
    prc_allocate_fefo(97001, 90001, 'Z+', 1, 'TEST_RUNNER', v_al);
  EXCEPTION WHEN OTHERS THEN v_code := SQLCODE;
  END;
  check_it('FEFO helper rejects unknown blood group (ORA-20004)', v_code = -20004);

  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== 5. Integrity across everything this run reserved =====
PROMPT --- allocations made by TEST_RUNNER (expected 14) ---
SELECT a.request_id, a.bag_id, b.blood_group AS bag_group, r.blood_group AS patient_group,
       b.expiry_date, b.status AS bag_status
  FROM allocation a
  JOIN blood_bags b ON b.bag_id = a.bag_id
  JOIN request r    ON r.request_id = a.request_id
 WHERE a.allocated_by = 'TEST_RUNNER'
 ORDER BY a.request_id, a.allocation_id;

DECLARE
  v_fail NUMBER := 0;
  v_n    NUMBER;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  SELECT COUNT(*) INTO v_n FROM allocation WHERE allocated_by = 'TEST_RUNNER';
  check_it('14 bags reserved in total by this run', v_n = 14);

  SELECT COUNT(*) INTO v_n
    FROM allocation a
    JOIN blood_bags b ON b.bag_id = a.bag_id
    JOIN request r    ON r.request_id = a.request_id
   WHERE a.allocated_by = 'TEST_RUNNER'
     AND (b.expiry_date <= TRUNC(SYSDATE)
          OR fn_is_rbc_compatible(b.blood_group, r.blood_group) = 'N'
          OR b.hospital_id <> r.hospital_id);
  check_it('no allocated bag is expired, incompatible or from another hospital', v_n = 0);

  SELECT COUNT(*) INTO v_n
    FROM blood_bags b
   WHERE b.bag_id BETWEEN 95001 AND 95999
     AND b.status IN ('RESERVED', 'ISSUED')
     AND (SELECT COUNT(*) FROM allocation a
           WHERE a.bag_id = b.bag_id AND a.status IN ('RESERVED', 'ISSUED')) <> 1;
  check_it('every RESERVED/ISSUED bag has exactly one live allocation', v_n = 0);

  SELECT COUNT(*) INTO v_n
    FROM allocation a
    JOIN blood_bags b ON b.bag_id = a.bag_id
   WHERE a.request_id BETWEEN 97001 AND 97099
     AND a.status IN ('RESERVED', 'ISSUED')
     AND b.status NOT IN ('RESERVED', 'ISSUED');
  check_it('every live allocation points at a RESERVED/ISSUED bag', v_n = 0);

  SELECT COUNT(*) INTO v_n
    FROM request r
   WHERE r.request_id BETWEEN 97001 AND 97099
     AND r.status IN ('FULFILLED', 'PARTIALLY_FULFILLED')
     AND r.units_allocated <> (SELECT COUNT(*) FROM allocation a
                                WHERE a.request_id = r.request_id AND a.status IN ('RESERVED', 'ISSUED'));
  check_it('request.units_allocated equals live allocation rows', v_n = 0);

  SELECT COUNT(*) INTO v_n
    FROM allocation a
   WHERE a.allocated_by = 'TEST_RUNNER'
     AND NOT EXISTS (SELECT 1 FROM bag_status_log l
                      WHERE l.bag_id = a.bag_id AND l.request_id = a.request_id
                        AND l.old_status = 'AVAILABLE' AND l.new_status = 'RESERVED');
  check_it('every new allocation has its status-log row', v_n = 0);

  SELECT COUNT(*) INTO v_n
    FROM (SELECT bag_id FROM allocation WHERE status IN ('RESERVED', 'ISSUED')
           GROUP BY bag_id HAVING COUNT(*) > 1);
  check_it('no bag has two live allocations', v_n = 0);
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== 6. Nearby hospitals =====
PROMPT --- within 25 km of Katpadi (12.9714, 79.1456) ---
VARIABLE rc REFCURSOR
BEGIN
  prc_nearby_hospitals(12.9714, 79.1456, 25, NULL, :rc);
END;
/
PRINT rc

DECLARE
  v_fail  NUMBER := 0;
  v_cur   SYS_REFCURSOR;
  v_id    NUMBER;  v_name VARCHAR2(150); v_addr VARCHAR2(300); v_district VARCHAR2(60); v_pin VARCHAR2(6);
  v_lat   NUMBER;  v_lon  NUMBER;  v_km NUMBER;  v_bg VARCHAR2(3);  v_units NUMBER;  v_exp DATE;
  v_ids   VARCHAR2(400) := ',';
  v_prev  NUMBER := -1;
  v_sorted BOOLEAN := TRUE;
  v_dup   NUMBER := 0;
  v_zero  NUMBER := 0;
  v_rows  NUMBER := 0;
  v_code  NUMBER;
  TYPE t_seen IS TABLE OF NUMBER INDEX BY VARCHAR2(20);
  v_seen  t_seen;
  v_key   VARCHAR2(20);
  v_d     NUMBER;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  -- 200 km around Chennai: Egmore, Tambaram, Katpadi and Vellore only
  prc_nearby_hospitals(13.0827, 80.2707, 200, NULL, v_cur);
  LOOP
    FETCH v_cur INTO v_id, v_name, v_addr, v_district, v_pin, v_lat, v_lon, v_km, v_bg, v_units, v_exp;
    EXIT WHEN v_cur%NOTFOUND;
    v_rows := v_rows + 1;
    -- only test hospitals are listed, so real rows in a shared database do not affect the check
    IF v_id BETWEEN 90001 AND 90009 AND INSTR(v_ids, ',' || v_id || ',') = 0 THEN
      v_ids := v_ids || v_id || ',';
    END IF;
    IF v_km < v_prev THEN v_sorted := FALSE; END IF;
    v_prev := v_km;
    v_key := v_id || '|' || v_bg;
    IF v_seen.EXISTS(v_key) THEN v_dup := v_dup + 1; END IF;
    v_seen(v_key) := 1;
    IF v_units <= 0 THEN v_zero := v_zero + 1; END IF;
  END LOOP;
  CLOSE v_cur;

  check_it('200 km from Chennai returns hospitals ' || v_ids,
           v_ids = ',90001,90002,90007,90008,' OR v_ids = ',90001,90002,90008,90007,');
  check_it('hospital without coordinates (90009) is excluded', INSTR(v_ids, ',90009,') = 0);
  check_it('far hospitals (Coimbatore, Madurai, Trichy, Salem) are excluded',
           INSTR(v_ids, ',90003,') + INSTR(v_ids, ',90004,') + INSTR(v_ids, ',90005,') + INSTR(v_ids, ',90006,') = 0);
  check_it('rows sorted by distance', v_sorted);
  check_it('no duplicate (hospital, blood group) rows', v_dup = 0);
  check_it('only groups with stock are returned', v_zero = 0 AND v_rows > 0);

  -- recipient filter: an O- patient can only use O- bags
  prc_nearby_hospitals(12.9714, 79.1456, 25, 'O-', v_cur);
  v_rows := 0; v_zero := 0;
  LOOP
    FETCH v_cur INTO v_id, v_name, v_addr, v_district, v_pin, v_lat, v_lon, v_km, v_bg, v_units, v_exp;
    EXIT WHEN v_cur%NOTFOUND;
    v_rows := v_rows + 1;
    IF v_bg <> 'O-' THEN v_zero := v_zero + 1; END IF;
  END LOOP;
  CLOSE v_cur;
  check_it('recipient O- filter returns only O- stock', v_rows >= 1 AND v_zero = 0);

  -- distance function
  v_d := fn_haversine_km(0, 0, 1, 0);
  check_it('1 degree of latitude is about 111.2 km (got ' || ROUND(v_d, 2) || ')', v_d BETWEEN 111.0 AND 111.4);
  check_it('distance of a point to itself is 0', fn_haversine_km(13.0827, 80.2707, 13.0827, 80.2707) = 0);
  check_it('distance with NULL input is NULL', fn_haversine_km(NULL, 80, 13, 80) IS NULL);

  -- argument validation
  v_code := 0;
  BEGIN prc_nearby_hospitals(95, 80, 10, NULL, v_cur);
  EXCEPTION WHEN OTHERS THEN v_code := SQLCODE; END;
  check_it('latitude 95 rejected (ORA-20010)', v_code = -20010);
  v_code := 0;
  BEGIN prc_nearby_hospitals(13, 80, 0, NULL, v_cur);
  EXCEPTION WHEN OTHERS THEN v_code := SQLCODE; END;
  check_it('radius 0 rejected (ORA-20011)', v_code = -20011);
  v_code := 0;
  BEGIN prc_nearby_hospitals(13, 80, 10, 'Q+', v_cur);
  EXCEPTION WHEN OTHERS THEN v_code := SQLCODE; END;
  check_it('unknown recipient group rejected (ORA-20012)', v_code = -20012);
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== 7. Expiring stale bags (run last: it changes bag status) =====
DECLARE
  v_fail NUMBER := 0;
  v_cnt  NUMBER;
  v_n    NUMBER;
  v_st1  blood_bags.status%TYPE;
  v_st2  blood_bags.status%TYPE;
  PROCEDURE check_it (p_name IN VARCHAR2, p_ok IN BOOLEAN) IS
  BEGIN
    IF p_ok THEN
      DBMS_OUTPUT.PUT_LINE('PASS  ' || p_name);
    ELSE
      DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_name);
      v_fail := v_fail + 1;
    END IF;
  END check_it;
BEGIN
  prc_expire_stale_bags('TEST_RUNNER', v_cnt);
  DBMS_OUTPUT.PUT_LINE('bags marked EXPIRED: ' || v_cnt);
  check_it('at least the two stale test bags were expired', v_cnt >= 2);
  SELECT status INTO v_st1 FROM blood_bags WHERE bag_id = 95006;
  SELECT status INTO v_st2 FROM blood_bags WHERE bag_id = 95201;
  check_it('95006 and 95201 are now EXPIRED', v_st1 = 'EXPIRED' AND v_st2 = 'EXPIRED');
  SELECT COUNT(*) INTO v_n FROM bag_status_log
   WHERE bag_id IN (95006, 95201) AND old_status = 'AVAILABLE' AND new_status = 'EXPIRED'
     AND changed_by = 'TEST_RUNNER';
  check_it('expiry transitions were logged', v_n = 2);
  SELECT COUNT(*) INTO v_n FROM blood_bags
   WHERE bag_id IN (95001, 95003, 95009, 95011) AND status = 'AVAILABLE';
  check_it('non-expired available bags untouched', v_n = 4);
  :g_fail := :g_fail + v_fail;
END;
/

PROMPT
PROMPT ===== SUMMARY =====
BEGIN
  IF :g_fail = 0 THEN
    DBMS_OUTPUT.PUT_LINE('ALL TESTS PASSED');
  ELSE
    DBMS_OUTPUT.PUT_LINE(:g_fail || ' TEST(S) FAILED');
    RAISE_APPLICATION_ERROR(-20900, :g_fail || ' test(s) failed. See FAIL lines above.');
  END IF;
END;
/

-- Undo every dispatch and status change made by this run; test data returns to
-- the freshly seeded state (sample_data.sql committed it before the tests).
ROLLBACK;
PROMPT tests.sql finished; changes rolled back.
