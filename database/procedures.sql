-- procedures.sql
-- Functions and procedures for compatibility, FEFO allocation, emergency
-- dispatch, stale-bag expiry and nearby-hospital search.
-- Run after the tables and views.sql (PRC_NEARBY_HOSPITALS uses V_DASHBOARD_INVENTORY).
--
-- Transaction strategy: NONE of these procedures commits or rolls back the
-- caller's transaction. The Java service owns COMMIT/ROLLBACK. Internally,
-- PRC_EMERGENCY_DISPATCH uses savepoints so that a failed or refused dispatch
-- leaves no partial changes behind.
--
-- Availability rule (same as views.sql): STATUS = 'AVAILABLE' AND
-- EXPIRY_DATE > TRUNC(SYSDATE).

SET DEFINE OFF
SET SQLBLANKLINES ON
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- ---------------------------------------------------------------------------
-- FN_IS_RBC_COMPATIBLE
-- Red-cell compatibility. Returns 'Y' if red cells from p_donor_group can be
-- given to a patient of p_recipient_group, otherwise 'N' (also for NULL or
-- unknown groups).
--
--   recipient  accepts red cells from
--   A+         A+, A-, O+, O-
--   A-         A-, O-
--   B+         B+, B-, O+, O-
--   B-         B-, O-
--   AB+        A+, A-, B+, B-, AB+, AB-, O+, O-
--   AB-        A-, B-, AB-, O-
--   O+         O+, O-
--   O-         O-
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_is_rbc_compatible (
  p_donor_group     IN VARCHAR2,
  p_recipient_group IN VARCHAR2
) RETURN VARCHAR2 DETERMINISTIC
IS
  v_donor VARCHAR2(3) := UPPER(TRIM(p_donor_group));
  v_recip VARCHAR2(3) := UPPER(TRIM(p_recipient_group));
BEGIN
  IF v_donor IS NULL OR v_recip IS NULL THEN
    RETURN 'N';
  END IF;

  IF v_recip = 'A+' AND v_donor IN ('A+', 'A-', 'O+', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'A-' AND v_donor IN ('A-', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'B+' AND v_donor IN ('B+', 'B-', 'O+', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'B-' AND v_donor IN ('B-', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'AB+' AND v_donor IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'AB-' AND v_donor IN ('A-', 'B-', 'AB-', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'O+' AND v_donor IN ('O+', 'O-') THEN
    RETURN 'Y';
  ELSIF v_recip = 'O-' AND v_donor = 'O-' THEN
    RETURN 'Y';
  END IF;

  RETURN 'N';
END fn_is_rbc_compatible;
/
SHOW ERRORS

-- ---------------------------------------------------------------------------
-- FN_HAVERSINE_KM
-- Great-circle distance in kilometres between two latitude/longitude points
-- (mean Earth radius 6371.0088 km). Returns NULL if any input is NULL.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_haversine_km (
  p_lat1 IN NUMBER,
  p_lon1 IN NUMBER,
  p_lat2 IN NUMBER,
  p_lon2 IN NUMBER
) RETURN NUMBER DETERMINISTIC
IS
  c_deg_to_rad CONSTANT NUMBER := 0.017453292519943295;
  c_earth_km   CONSTANT NUMBER := 6371.0088;
  v_a          NUMBER;
BEGIN
  IF p_lat1 IS NULL OR p_lon1 IS NULL OR p_lat2 IS NULL OR p_lon2 IS NULL THEN
    RETURN NULL;
  END IF;

  v_a := POWER(SIN((p_lat2 - p_lat1) * c_deg_to_rad / 2), 2)
       + COS(p_lat1 * c_deg_to_rad) * COS(p_lat2 * c_deg_to_rad)
         * POWER(SIN((p_lon2 - p_lon1) * c_deg_to_rad / 2), 2);

  RETURN c_earth_km * 2 * ASIN(LEAST(1, SQRT(v_a)));
END fn_haversine_km;
/
SHOW ERRORS

-- ---------------------------------------------------------------------------
-- PRC_ALLOCATE_FEFO
-- Reserves up to p_units_needed bags at one hospital for one request, taking
-- compatible, available, non-expired bags in this order:
--     EXPIRY_DATE, COLLECTION_DATE, BAG_ID
-- For every bag it: inserts an ALLOCATION row (RESERVED), sets the bag to
-- RESERVED and writes a BAG_STATUS_LOG row.
--
-- It does NOT update REQUEST; PRC_EMERGENCY_DISPATCH does that.
-- It does not commit.
--
-- Concurrency: candidates are listed without locks (so only the rows that are
-- actually needed get locked), then each candidate is locked individually with
-- FOR UPDATE SKIP LOCKED and its status/expiry re-checked. A bag locked by
-- another session is skipped, so two sessions never reserve the same bag. The
-- trade-off: while another session holds the earliest-expiring bags
-- uncommitted, this session takes the next-earliest ones, so strict global
-- FEFO order only holds among bags that are not being allocated at that moment.
--
-- Errors:  ORA-20003 units not a positive whole number
--          ORA-20004 unknown blood group
--          ORA-20005 hospital id is NULL
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE prc_allocate_fefo (
  p_request_id      IN  NUMBER,
  p_hospital_id     IN  NUMBER,
  p_recipient_group IN  VARCHAR2,
  p_units_needed    IN  NUMBER,
  p_actor           IN  VARCHAR2,
  p_units_allocated OUT NUMBER
) AS
  CURSOR c_candidates (cp_hospital NUMBER, cp_group VARCHAR2) IS
    SELECT b.bag_id
      FROM blood_bags b
     WHERE b.hospital_id = cp_hospital
       AND b.status = 'AVAILABLE'
       AND b.expiry_date > TRUNC(SYSDATE)
       AND fn_is_rbc_compatible(b.blood_group, cp_group) = 'Y'
     ORDER BY b.expiry_date, b.collection_date, b.bag_id;

  v_bag_id  blood_bags.bag_id%TYPE;
  v_got_row BOOLEAN;
  v_count   NUMBER := 0;
BEGIN
  p_units_allocated := 0;

  IF p_units_needed IS NULL OR p_units_needed <= 0
     OR p_units_needed <> TRUNC(p_units_needed) THEN
    RAISE_APPLICATION_ERROR(-20003, 'Units needed must be a positive whole number.');
  END IF;

  IF p_recipient_group IS NULL
     OR p_recipient_group NOT IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-') THEN
    RAISE_APPLICATION_ERROR(-20004, 'Unknown recipient blood group: ' || NVL(p_recipient_group, 'NULL'));
  END IF;

  IF p_hospital_id IS NULL THEN
    RAISE_APPLICATION_ERROR(-20005, 'Hospital id is required.');
  END IF;

  FOR r IN c_candidates(p_hospital_id, p_recipient_group) LOOP
    EXIT WHEN v_count >= p_units_needed;

    v_got_row := TRUE;
    BEGIN
      SELECT b.bag_id
        INTO v_bag_id
        FROM blood_bags b
       WHERE b.bag_id = r.bag_id
         AND b.status = 'AVAILABLE'
         AND b.expiry_date > TRUNC(SYSDATE)
         FOR UPDATE SKIP LOCKED;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        v_got_row := FALSE;   -- locked by someone else, or no longer eligible
    END;

    IF v_got_row THEN
      INSERT INTO allocation (allocation_id, request_id, bag_id, allocated_at, allocated_by, status)
      VALUES (seq_allocation.NEXTVAL, p_request_id, v_bag_id, SYSTIMESTAMP, p_actor, 'RESERVED');

      UPDATE blood_bags
         SET status = 'RESERVED'
       WHERE bag_id = v_bag_id;

      INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
      VALUES (seq_bag_status_log.NEXTVAL, v_bag_id, 'AVAILABLE', 'RESERVED', SYSTIMESTAMP, p_actor,
              'FEFO reservation for emergency request ' || p_request_id, p_request_id);

      v_count := v_count + 1;
    END IF;
  END LOOP;

  p_units_allocated := v_count;
END prc_allocate_fefo;
/
SHOW ERRORS

-- ---------------------------------------------------------------------------
-- PRC_EMERGENCY_DISPATCH
-- Reserves blood for one emergency request from the hospital stored in
-- REQUEST.HOSPITAL_ID. RESERVED is not ISSUED: this procedure never marks a
-- bag ISSUED; physical release is a separate step.
--
-- Parameters
--   p_request_id         request to process (required)
--   p_actor              audit text written to ALLOCATION/BAG_STATUS_LOG
--                        (e.g. 'USER:91011'); default 'SYSTEM'
--   p_actor_hospital_id  hospital the authenticated staff member belongs to.
--                        When not NULL it must equal REQUEST.HOSPITAL_ID,
--                        otherwise outcome NOT_AUTHORIZED. The Java service
--                        derives it from the login, never from the client.
--   p_allow_partial      'Y' (default) keep a partial reservation;
--                        'N' reserve nothing unless the whole quantity exists
--   p_outcome            see below
--   p_units_requested    units asked for (NULL if the request was not found)
--   p_units_allocated    units reserved by THIS call
--   p_message            human-readable detail
--
-- Outcomes (returned in p_outcome; no exception is raised for these)
--   SUCCESS             all units reserved, request -> FULFILLED
--   PARTIAL             some units reserved, request -> PARTIALLY_FULFILLED
--   INSUFFICIENT_STOCK  nothing reserved, request -> REJECTED
--   NOT_FOUND           no such request
--   NOT_ELIGIBLE        request is not PENDING or already holds allocations
--   NOT_AUTHORIZED      p_actor_hospital_id does not match the request's hospital
--   INVALID_QUANTITY    stored units_required is not a positive whole number
--   INVALID_HOSPITAL    request's hospital is missing or inactive
--   INVALID_PARAMETER   NULL request id or bad p_allow_partial
--   BUSY                request row locked by another session for over 5 seconds
-- Unexpected database errors roll back to the start of the call and raise
-- ORA-20999 (OUT parameters are not delivered when an exception propagates).
--
-- Status values used on REQUEST are held in constants below; change them there
-- if your schema uses different words. The "no stock" status is REJECTED.
--
-- Duplicate processing: the REQUEST row is locked first (FOR UPDATE WAIT 5) and
-- must be PENDING with no allocations. A second call for the same request waits
-- for the first to finish and then gets NOT_ELIGIBLE.
--
-- Nothing is committed here. The caller commits after SUCCESS/PARTIAL/
-- INSUFFICIENT_STOCK, and rolls back on an exception.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE prc_emergency_dispatch (
  p_request_id        IN  NUMBER,
  p_actor             IN  VARCHAR2 DEFAULT 'SYSTEM',
  p_actor_hospital_id IN  NUMBER   DEFAULT NULL,
  p_allow_partial     IN  VARCHAR2 DEFAULT 'Y',
  p_outcome           OUT VARCHAR2,
  p_units_requested   OUT NUMBER,
  p_units_allocated   OUT NUMBER,
  p_message           OUT VARCHAR2
) AS
  c_status_pending   CONSTANT VARCHAR2(20) := 'PENDING';
  c_status_fulfilled CONSTANT VARCHAR2(20) := 'FULFILLED';
  c_status_partial   CONSTANT VARCHAR2(20) := 'PARTIALLY_FULFILLED';
  c_status_no_stock  CONSTANT VARCHAR2(20) := 'REJECTED';

  e_lock_timeout EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_lock_timeout, -30006);

  v_req           request%ROWTYPE;
  v_actor         VARCHAR2(60);
  v_hosp_active   hospital.is_active%TYPE;
  v_active_allocs NUMBER;
  v_found         NUMBER := 0;
  v_allocated     NUMBER := 0;
  v_status        VARCHAR2(20);
  v_err           VARCHAR2(2000);

  -- Refusal path: nothing was changed, so undo to the savepoint (this also
  -- releases any row lock taken on the request) and report the reason.
  PROCEDURE finish_no_change (p_code IN VARCHAR2, p_text IN VARCHAR2) IS
  BEGIN
    ROLLBACK TO SAVEPOINT sp_dispatch;
    p_outcome := p_code;
    p_message := p_text;
  END finish_no_change;
BEGIN
  p_outcome         := NULL;
  p_units_requested := NULL;
  p_units_allocated := 0;
  p_message         := NULL;

  SAVEPOINT sp_dispatch;

  IF p_request_id IS NULL THEN
    finish_no_change('INVALID_PARAMETER', 'Request id is required.');
    RETURN;
  END IF;
  IF p_allow_partial IS NULL OR p_allow_partial NOT IN ('Y', 'N') THEN
    finish_no_change('INVALID_PARAMETER', 'p_allow_partial must be Y or N.');
    RETURN;
  END IF;

  v_actor := SUBSTR(NVL(TRIM(p_actor), 'SYSTEM'), 1, 60);

  -- Lock the request first: this serialises concurrent calls for the same request.
  BEGIN
    SELECT *
      INTO v_req
      FROM request
     WHERE request_id = p_request_id
       FOR UPDATE WAIT 5;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      finish_no_change('NOT_FOUND', 'Request ' || p_request_id || ' does not exist.');
      RETURN;
    WHEN e_lock_timeout THEN
      finish_no_change('BUSY', 'Request ' || p_request_id || ' is being processed by another session.');
      RETURN;
  END;

  IF p_actor_hospital_id IS NOT NULL AND p_actor_hospital_id <> v_req.hospital_id THEN
    finish_no_change('NOT_AUTHORIZED', 'Caller is not linked to the hospital that owns request ' || p_request_id || '.');
    RETURN;
  END IF;

  p_units_requested := v_req.units_required;

  IF v_req.status <> c_status_pending THEN
    finish_no_change('NOT_ELIGIBLE', 'Request ' || p_request_id || ' is already ' || v_req.status || '.');
    RETURN;
  END IF;

  SELECT COUNT(*)
    INTO v_active_allocs
    FROM allocation
   WHERE request_id = p_request_id
     AND status IN ('RESERVED', 'ISSUED');

  IF v_active_allocs > 0 OR NVL(v_req.units_allocated, 0) > 0 THEN
    finish_no_change('NOT_ELIGIBLE', 'Request ' || p_request_id || ' already holds allocations.');
    RETURN;
  END IF;

  IF v_req.units_required IS NULL OR v_req.units_required <= 0
     OR v_req.units_required <> TRUNC(v_req.units_required) THEN
    finish_no_change('INVALID_QUANTITY', 'Request ' || p_request_id || ' has an invalid units_required value.');
    RETURN;
  END IF;

  BEGIN
    SELECT is_active INTO v_hosp_active FROM hospital WHERE hospital_id = v_req.hospital_id;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      v_hosp_active := 'N';
  END;
  IF v_hosp_active <> 'Y' THEN
    finish_no_change('INVALID_HOSPITAL', 'Hospital for request ' || p_request_id || ' is missing or inactive.');
    RETURN;
  END IF;

  -- From here on, only the allocation work can be undone separately from the
  -- request lock.
  SAVEPOINT sp_alloc;

  prc_allocate_fefo(
    p_request_id      => p_request_id,
    p_hospital_id     => v_req.hospital_id,
    p_recipient_group => v_req.blood_group,
    p_units_needed    => v_req.units_required,
    p_actor           => v_actor,
    p_units_allocated => v_allocated);

  IF v_allocated = v_req.units_required THEN
    v_status    := c_status_fulfilled;
    p_outcome   := 'SUCCESS';
    p_message   := 'Reserved ' || v_allocated || ' of ' || v_req.units_required
                   || ' unit(s) for request ' || p_request_id || '.';
  ELSIF v_allocated > 0 AND p_allow_partial = 'Y' THEN
    v_status    := c_status_partial;
    p_outcome   := 'PARTIAL';
    p_message   := 'Only ' || v_allocated || ' of ' || v_req.units_required
                   || ' compatible unit(s) available; reserved ' || v_allocated || '.';
  ELSE
    v_found := v_allocated;
    IF v_found > 0 THEN
      -- Partial fulfilment disabled: give the bags back.
      ROLLBACK TO SAVEPOINT sp_alloc;
      v_allocated := 0;
      p_message := 'Only ' || v_found || ' of ' || v_req.units_required
                   || ' compatible unit(s) available and partial fulfilment is off; nothing reserved.';
    ELSE
      p_message := 'No compatible, available, non-expired stock for request ' || p_request_id || '.';
    END IF;
    v_status  := c_status_no_stock;
    p_outcome := 'INSUFFICIENT_STOCK';
  END IF;

  UPDATE request
     SET units_allocated = v_allocated,
         status          = v_status,
         processed_at    = SYSTIMESTAMP,
         remarks         = SUBSTR(p_message, 1, 300)
   WHERE request_id = p_request_id;

  p_units_allocated := v_allocated;

EXCEPTION
  WHEN OTHERS THEN
    v_err := SQLERRM;
    ROLLBACK TO SAVEPOINT sp_dispatch;
    p_outcome         := 'ERROR';
    p_units_allocated := 0;
    p_message         := SUBSTR(v_err, 1, 500);
    RAISE_APPLICATION_ERROR(-20999,
      'PRC_EMERGENCY_DISPATCH failed for request ' || p_request_id || ': ' || SUBSTR(v_err, 1, 1500));
END prc_emergency_dispatch;
/
SHOW ERRORS

-- ---------------------------------------------------------------------------
-- PRC_EXPIRE_STALE_BAGS
-- Maintenance: flips AVAILABLE bags whose expiry date has passed to EXPIRED
-- and logs each change. Bags that are RESERVED/ISSUED are not touched. Bags
-- locked by another session are skipped. Does not commit. Run it from a
-- scheduled job; the dashboard and allocation logic do not depend on it
-- because they already filter by expiry date.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE prc_expire_stale_bags (
  p_actor         IN  VARCHAR2 DEFAULT 'SYSTEM',
  p_expired_count OUT NUMBER
) AS
  CURSOR c_stale IS
    SELECT bag_id
      FROM blood_bags
     WHERE status = 'AVAILABLE'
       AND expiry_date <= TRUNC(SYSDATE)
       FOR UPDATE SKIP LOCKED;
  v_actor VARCHAR2(60) := SUBSTR(NVL(TRIM(p_actor), 'SYSTEM'), 1, 60);
BEGIN
  p_expired_count := 0;
  FOR r IN c_stale LOOP
    UPDATE blood_bags
       SET status = 'EXPIRED'
     WHERE CURRENT OF c_stale;

    INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
    VALUES (seq_bag_status_log.NEXTVAL, r.bag_id, 'AVAILABLE', 'EXPIRED', SYSTIMESTAMP, v_actor,
            'Expiry date passed', NULL);

    p_expired_count := p_expired_count + 1;
  END LOOP;
END prc_expire_stale_bags;
/
SHOW ERRORS

-- ---------------------------------------------------------------------------
-- PRC_NEARBY_HOSPITALS
-- Hospitals within p_radius_km of a coordinate, with their available stock.
-- Result grain: one row per (hospital, blood group) that has available units,
-- so a hospital appears once per blood group and never twice for the same
-- group (V_DASHBOARD_INVENTORY is already aggregated to that grain).
-- Hospitals with NULL coordinates are excluded. Sorted by distance, then
-- hospital id, then blood group.
--
-- p_recipient_group (optional): when given, only groups whose red cells are
-- compatible with that recipient are returned.
--
-- The coordinate comes from the caller. A pincode is not converted here;
-- see schema_gaps.md for the pincode lookup the backend needs.
--
-- Errors: ORA-20010 invalid latitude/longitude, ORA-20011 invalid radius
--         (must be > 0 and <= 500), ORA-20012 unknown recipient group.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE prc_nearby_hospitals (
  p_latitude        IN  NUMBER,
  p_longitude       IN  NUMBER,
  p_radius_km       IN  NUMBER,
  p_recipient_group IN  VARCHAR2 DEFAULT NULL,
  p_result          OUT SYS_REFCURSOR
) AS
BEGIN
  IF p_latitude IS NULL OR p_longitude IS NULL
     OR p_latitude NOT BETWEEN -90 AND 90
     OR p_longitude NOT BETWEEN -180 AND 180 THEN
    RAISE_APPLICATION_ERROR(-20010, 'Latitude/longitude missing or out of range.');
  END IF;
  IF p_radius_km IS NULL OR p_radius_km <= 0 OR p_radius_km > 500 THEN
    RAISE_APPLICATION_ERROR(-20011, 'Radius must be greater than 0 and at most 500 km.');
  END IF;
  IF p_recipient_group IS NOT NULL
     AND p_recipient_group NOT IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-') THEN
    RAISE_APPLICATION_ERROR(-20012, 'Unknown recipient blood group: ' || p_recipient_group);
  END IF;

  OPEN p_result FOR
    SELECT x.hospital_id,
           x.hospital_name,
           x.address,
           x.district,
           x.pincode,
           x.latitude,
           x.longitude,
           ROUND(x.raw_distance_km, 2) AS distance_km,
           x.blood_group,
           x.available_units,
           x.nearest_expiry_date
      FROM (SELECT v.hospital_id,
                   v.hospital_name,
                   v.address,
                   v.district,
                   v.pincode,
                   v.latitude,
                   v.longitude,
                   v.blood_group,
                   v.available_units,
                   v.nearest_expiry_date,
                   fn_haversine_km(p_latitude, p_longitude, v.latitude, v.longitude) AS raw_distance_km
              FROM v_dashboard_inventory v
             WHERE v.latitude  IS NOT NULL
               AND v.longitude IS NOT NULL
               AND v.available_units > 0
               AND (p_recipient_group IS NULL
                    OR fn_is_rbc_compatible(v.blood_group, p_recipient_group) = 'Y')) x
     WHERE x.raw_distance_km <= p_radius_km
     ORDER BY x.raw_distance_km, x.hospital_id, x.blood_group;
END prc_nearby_hospitals;
/
SHOW ERRORS

-- SQL*Plus does not treat PL/SQL compile errors as SQL errors, so check them
-- explicitly and fail the script if any object in this file did not compile.
DECLARE
  v_bad NUMBER;
BEGIN
  SELECT COUNT(*)
    INTO v_bad
    FROM user_errors
   WHERE name IN ('FN_IS_RBC_COMPATIBLE', 'FN_HAVERSINE_KM', 'PRC_ALLOCATE_FEFO',
                  'PRC_EMERGENCY_DISPATCH', 'PRC_EXPIRE_STALE_BAGS', 'PRC_NEARBY_HOSPITALS')
     AND attribute = 'ERROR';
  IF v_bad > 0 THEN
    RAISE_APPLICATION_ERROR(-20002,
      'procedures.sql: ' || v_bad || ' compile error(s). Run: SELECT name, line, text FROM user_errors ORDER BY name, sequence;');
  END IF;
END;
/

PROMPT procedures.sql finished: all objects compiled.
