-- views.sql
-- Inventory and reporting views. Run after the tables exist.
--
-- Availability rule used everywhere in this project (views and procedures):
--   a bag counts as available only when STATUS = 'AVAILABLE'
--   AND EXPIRY_DATE > TRUNC(SYSDATE).
-- A bag whose expiry date is today is treated as already expired (conservative).
-- One row in BLOOD_BAGS is one unit, so units are counted with COUNT(*).

SET DEFINE OFF
SET SQLBLANKLINES ON
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- V_DASHBOARD_INVENTORY
-- Grain: one row per active hospital per blood group (all 8 groups always
-- appear, with 0 units when there is no stock).
-- Stock is aggregated from BLOOD_BAGS in a subquery BEFORE it is joined to
-- HOSPITAL, so no donor/request/allocation join can multiply the counts.
--
-- Backend usage:
--   hospital stock : WHERE hospital_id = :hospital_id [AND blood_group = :bg]
--   dashboard      : no hospital filter, or filter by district/pincode
CREATE OR REPLACE VIEW v_dashboard_inventory AS
SELECT h.hospital_id,
       h.name                              AS hospital_name,
       h.address,
       h.district,
       h.pincode,
       h.latitude,
       h.longitude,
       g.blood_group,
       NVL(s.available_units, 0)           AS available_units,
       s.nearest_expiry_date,
       s.latest_expiry_date,
       NVL(s.expiring_within_7_days, 0)    AS expiring_within_7_days
  FROM hospital h
 CROSS JOIN (SELECT 'A+'  AS blood_group FROM dual UNION ALL
             SELECT 'A-'  FROM dual UNION ALL
             SELECT 'B+'  FROM dual UNION ALL
             SELECT 'B-'  FROM dual UNION ALL
             SELECT 'AB+' FROM dual UNION ALL
             SELECT 'AB-' FROM dual UNION ALL
             SELECT 'O+'  FROM dual UNION ALL
             SELECT 'O-'  FROM dual) g
  LEFT JOIN (SELECT b.hospital_id,
                    b.blood_group,
                    COUNT(*)                                   AS available_units,
                    MIN(b.expiry_date)                         AS nearest_expiry_date,
                    MAX(b.expiry_date)                         AS latest_expiry_date,
                    SUM(CASE WHEN b.expiry_date <= TRUNC(SYSDATE) + 7
                             THEN 1 ELSE 0 END)                AS expiring_within_7_days
               FROM blood_bags b
              WHERE b.status = 'AVAILABLE'
                AND b.expiry_date > TRUNC(SYSDATE)
              GROUP BY b.hospital_id, b.blood_group) s
    ON s.hospital_id = h.hospital_id
   AND s.blood_group = g.blood_group
 WHERE h.is_active = 'Y';

-- V_DASHBOARD_TOTALS
-- Consolidated stock per blood group across all active hospitals.
CREATE OR REPLACE VIEW v_dashboard_totals AS
SELECT blood_group,
       SUM(available_units)                                AS total_available_units,
       COUNT(CASE WHEN available_units > 0 THEN 1 END)     AS hospitals_with_stock,
       MIN(nearest_expiry_date)                            AS nearest_expiry_date,
       SUM(expiring_within_7_days)                         AS expiring_within_7_days
  FROM v_dashboard_inventory
 GROUP BY blood_group;

-- V_AVAILABLE_BAG_DETAIL
-- Bag-level view for expiry monitoring. FEFO_RANK is the order in which the
-- allocation procedure would consider bags of the same group at a hospital
-- (compatible groups are merged at allocation time, so the real allocation
-- order can interleave groups; see PRC_ALLOCATE_FEFO).
CREATE OR REPLACE VIEW v_available_bag_detail AS
SELECT b.bag_id,
       b.bag_code,
       b.hospital_id,
       h.name                              AS hospital_name,
       b.blood_group,
       b.collection_date,
       b.expiry_date,
       b.expiry_date - TRUNC(SYSDATE)      AS days_to_expiry,
       ROW_NUMBER() OVER (PARTITION BY b.hospital_id, b.blood_group
                          ORDER BY b.expiry_date, b.collection_date, b.bag_id) AS fefo_rank
  FROM blood_bags b
  JOIN hospital h ON h.hospital_id = b.hospital_id
 WHERE b.status = 'AVAILABLE'
   AND b.expiry_date > TRUNC(SYSDATE)
   AND h.is_active = 'Y';

-- V_REQUEST_ALLOCATION_DETAIL
-- Bags held by each request (for the request-detail screen and for audit).
CREATE OR REPLACE VIEW v_request_allocation_detail AS
SELECT a.request_id,
       a.allocation_id,
       a.bag_id,
       b.bag_code,
       b.blood_group,
       b.hospital_id,
       b.collection_date,
       b.expiry_date,
       a.status          AS allocation_status,
       b.status          AS bag_status,
       a.allocated_at,
       a.allocated_by
  FROM allocation a
  JOIN blood_bags b ON b.bag_id = a.bag_id;

PROMPT views.sql finished.
