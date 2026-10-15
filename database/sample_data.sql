-- sample_data.sql
-- Fictional TEST data for Tamil Nadu. Every person, hospital, phone number and
-- e-mail below is invented. Names carry "(TEST)", e-mails use the reserved
-- example.test domain, and the password hash is a placeholder that no
-- password verifier will accept (these accounts cannot log in).
--
-- Reseed behaviour: this script first deletes ONLY rows inside the reserved
-- test ID ranges below (plus the allocations/log rows that point at them),
-- then re-inserts them, then COMMITs. Rows created by the real application are
-- not touched as long as they use other IDs.
--
--   hospitals 90001-90009   users 91001-91099   hospital staff 92001-92099
--   donors 93001-93099      bags 95001-95999    requests 97001-97099
--
-- Bag dates are relative to today (TRUNC(SYSDATE)+/-n) so expiry cases stay
-- valid whenever the script is run. Donor dates use TO_DATE with a mask.
--
-- Designed scenarios (all at different hospitals so they do not interfere):
--   97001 B+  x3  Egmore       full allocation, FEFO + compatibility + tie-break
--   97002 AB- x4  Tambaram     only 3 compatible bags -> partial
--   97003 O-  x2  Coimbatore   only an expired and a reserved O- -> rejected
--   97004 O+  x2  Madurai      full, takes an O- bag because it expires first
--   97005 AB+ x2  Tiruchirappalli  universal recipient, full
--   97006 A-  x1  Salem        full
--   97014 A+  x5  Salem        run with partial fulfilment OFF -> rejected
--   97007 CANCELLED, 97009/97010 already FULFILLED (history), 97011 PENDING
--         (authorisation test), 97012 + 97013 B- x2 each at Vellore with only
--         3 B- bags (competing requests / concurrency demo)

SET DEFINE OFF
SET SQLBLANKLINES ON
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- ---- cleanup of previous test data (children first) ----
DELETE FROM bag_status_log WHERE bag_id BETWEEN 95001 AND 95999 OR request_id BETWEEN 97001 AND 97099;
DELETE FROM allocation     WHERE bag_id BETWEEN 95001 AND 95999 OR request_id BETWEEN 97001 AND 97099;
DELETE FROM request        WHERE request_id BETWEEN 97001 AND 97099;
DELETE FROM blood_bags     WHERE bag_id BETWEEN 95001 AND 95999;
DELETE FROM donor          WHERE donor_id BETWEEN 93001 AND 93099;
DELETE FROM hospital_staff WHERE staff_id BETWEEN 92001 AND 92099 OR hospital_id BETWEEN 90001 AND 90009;
DELETE FROM users          WHERE user_id BETWEEN 91001 AND 91099;
DELETE FROM hospital       WHERE hospital_id BETWEEN 90001 AND 90009;

-- ---- hospitals (coordinates are approximate city locations; sample only) ----
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90001, 'Marina Central Hospital (TEST)', '12 Test Road, Egmore, Chennai', 'Chennai', '600008', 13.0732, 80.2609, '04400000001', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90002, 'Tambaram Community Hospital (TEST)', '45 Sample Street, Tambaram, Chengalpattu', 'Chengalpattu', '600045', 12.9249, 80.1000, '04400000002', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90003, 'Kongu Regional Hospital (TEST)', '8 Demo Nagar, RS Puram, Coimbatore', 'Coimbatore', '641002', 11.0089, 76.9500, '04220000003', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90004, 'Vaigai Medical Centre (TEST)', '21 Placeholder Lane, Madurai', 'Madurai', '625001', 9.9252, 78.1198, '04520000004', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90005, 'Cauvery General Hospital (TEST)', '3 Fictional Avenue, Tiruchirappalli', 'Tiruchirappalli', '620001', 10.7905, 78.7047, '04310000005', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90006, 'Mango City Hospital (TEST)', '67 Example Road, Salem', 'Salem', '636001', 11.6643, 78.1460, '04270000006', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90007, 'Katpadi Care Hospital (TEST)', '5 Test Colony, Katpadi, Vellore', 'Vellore', '632007', 12.9714, 79.1456, '04160000007', 'Y');
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90008, 'Palar Valley Hospital (TEST)', '19 Sample Bazaar, Vellore', 'Vellore', '632001', 12.9165, 79.1325, '04160000008', 'Y');
-- No coordinates on purpose: must be excluded from distance searches.
INSERT INTO hospital (hospital_id, name, address, district, pincode, latitude, longitude, phone, is_active)
VALUES (90009, 'Temple Town Hospital (TEST)', '2 Dummy Street, Kancheepuram', 'Kancheepuram', '631501', NULL, NULL, '04427000009', 'Y');

-- ---- user accounts (placeholder hash; not usable for login) ----
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91001, 'Test Citizen One', 'citizen1.test@example.test', '9000000001', 'TEST-ONLY-NOT-A-REAL-HASH', 'CITIZEN', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91002, 'Test Citizen Two', 'citizen2.test@example.test', '9000000002', 'TEST-ONLY-NOT-A-REAL-HASH', 'CITIZEN', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91003, 'Test Citizen Three', 'citizen3.test@example.test', '9000000003', 'TEST-ONLY-NOT-A-REAL-HASH', 'CITIZEN', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91011, 'Test Staff Egmore', 'staff.egmore.test@example.test', '9000000011', 'TEST-ONLY-NOT-A-REAL-HASH', 'HOSPITAL_STAFF', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91012, 'Test Staff Tambaram', 'staff.tambaram.test@example.test', '9000000012', 'TEST-ONLY-NOT-A-REAL-HASH', 'HOSPITAL_STAFF', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91013, 'Test Staff Coimbatore', 'staff.coimbatore.test@example.test', '9000000013', 'TEST-ONLY-NOT-A-REAL-HASH', 'HOSPITAL_STAFF', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91014, 'Test Staff Katpadi', 'staff.katpadi.test@example.test', '9000000014', 'TEST-ONLY-NOT-A-REAL-HASH', 'HOSPITAL_STAFF', 'ACTIVE');
INSERT INTO users (user_id, full_name, email, phone, password_hash, user_role, account_status)
VALUES (91021, 'Test Admin', 'admin.test@example.test', '9000000021', 'TEST-ONLY-NOT-A-REAL-HASH', 'ADMIN', 'ACTIVE');

INSERT INTO hospital_staff (staff_id, user_id, hospital_id, designation) VALUES (92001, 91011, 90001, 'Blood Bank Officer');
INSERT INTO hospital_staff (staff_id, user_id, hospital_id, designation) VALUES (92002, 91012, 90002, 'Blood Bank Officer');
INSERT INTO hospital_staff (staff_id, user_id, hospital_id, designation) VALUES (92003, 91013, 90003, 'Lab Technician');
INSERT INTO hospital_staff (staff_id, user_id, hospital_id, designation) VALUES (92004, 91014, 90007, 'Blood Bank Officer');

-- ---- donors (each linked to at most one test bag; all eight groups present) ----
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93001, 'DNR-T-0001', 'Arun Kumar (TEST)',        'A+',  TO_DATE('1992-03-14', 'YYYY-MM-DD'), '9100000001', 'Chennai',   TO_DATE('2026-09-10', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93002, 'DNR-T-0002', 'Meenakshi Devi (TEST)',    'A-',  TO_DATE('1988-11-02', 'YYYY-MM-DD'), '9100000002', 'Chengalpattu', TO_DATE('2026-09-19', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93003, 'DNR-T-0003', 'Karthik Raja (TEST)',      'B+',  TO_DATE('1995-07-21', 'YYYY-MM-DD'), '9100000003', 'Chennai',   TO_DATE('2026-09-21', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93004, 'DNR-T-0004', 'Lakshmi Priya (TEST)',     'B-',  TO_DATE('1990-01-30', 'YYYY-MM-DD'), '9100000004', 'Chennai',   TO_DATE('2026-09-11', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93005, 'DNR-T-0005', 'Senthil Nathan (TEST)',    'AB+', TO_DATE('1985-05-09', 'YYYY-MM-DD'), '9100000005', 'Chengalpattu', TO_DATE('2026-09-10', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93006, 'DNR-T-0006', 'Divya Bharathi (TEST)',    'AB-', TO_DATE('1993-12-18', 'YYYY-MM-DD'), '9100000006', 'Chennai',   TO_DATE('2026-09-10', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93007, 'DNR-T-0007', 'Vignesh Waran (TEST)',     'O+',  TO_DATE('1991-08-25', 'YYYY-MM-DD'), '9100000007', 'Chennai',   TO_DATE('2026-09-29', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93008, 'DNR-T-0008', 'Revathi Selvam (TEST)',    'O-',  TO_DATE('1987-02-04', 'YYYY-MM-DD'), '9100000008', 'Chennai',   TO_DATE('2026-09-16', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93009, 'DNR-T-0009', 'Mohan Raj (TEST)',         'B+',  TO_DATE('1994-09-12', 'YYYY-MM-DD'), '9100000009', 'Chennai',   TO_DATE('2026-09-13', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93010, 'DNR-T-0010', 'Anitha Devi (TEST)',       'O-',  TO_DATE('1989-04-27', 'YYYY-MM-DD'), '9100000010', 'Chengalpattu', TO_DATE('2026-09-12', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93011, 'DNR-T-0011', 'Prakash Babu (TEST)',      'A+',  TO_DATE('1996-06-06', 'YYYY-MM-DD'), '9100000011', 'Coimbatore', TO_DATE('2026-09-24', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93012, 'DNR-T-0012', 'Gayathri Murugan (TEST)',  'B+',  TO_DATE('1992-10-01', 'YYYY-MM-DD'), '9100000012', 'Chennai',   TO_DATE('2026-09-06', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93013, 'DNR-T-0013', 'Suresh Pandian (TEST)',    'O+',  TO_DATE('1990-03-19', 'YYYY-MM-DD'), '9100000013', 'Chengalpattu', TO_DATE('2026-09-11', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93014, 'DNR-T-0014', 'Kavitha Rani (TEST)',      'A-',  TO_DATE('1986-12-22', 'YYYY-MM-DD'), '9100000014', 'Salem',     TO_DATE('2026-09-22', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93015, 'DNR-T-0015', 'Dinesh Kannan (TEST)',     'AB-', TO_DATE('1997-01-15', 'YYYY-MM-DD'), '9100000015', 'Chengalpattu', TO_DATE('2026-09-15', 'YYYY-MM-DD'));
INSERT INTO donor (donor_id, donor_code, full_name, blood_group, date_of_birth, phone, district, last_donation_date)
VALUES (93016, 'DNR-T-0016', 'Saranya Velu (TEST)',      'O-',  TO_DATE('1993-05-31', 'YYYY-MM-DD'), '9100000016', 'Vellore',   TO_DATE('2026-10-09', 'YYYY-MM-DD'));

-- ---- blood bags: E = TRUNC(SYSDATE). Columns: id, code, donor, hospital, group, collected, expires, status ----
-- Hospital 90001 (Egmore): scenario for request 97001 (B+ x3)
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95001, 'BB-T-95001', 93003, 90001, 'B+',  TRUNC(SYSDATE) - 23, TRUNC(SYSDATE) + 12, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95002, 'BB-T-95002', 93009, 90001, 'B+',  TRUNC(SYSDATE) - 31, TRUNC(SYSDATE) + 4,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95003, 'BB-T-95003', 93008, 90001, 'O-',  TRUNC(SYSDATE) - 28, TRUNC(SYSDATE) + 7,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95004, 'BB-T-95004', 93004, 90001, 'B-',  TRUNC(SYSDATE) - 33, TRUNC(SYSDATE) + 2,  'AVAILABLE');
-- same expiry date as 95002 but collected earlier -> must come first
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95005, 'BB-T-95005', 93012, 90001, 'B+',  TRUNC(SYSDATE) - 38, TRUNC(SYSDATE) + 4,  'AVAILABLE');
-- expired by date but still flagged AVAILABLE (stale status)
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95006, 'BB-T-95006', NULL,  90001, 'B+',  TRUNC(SYSDATE) - 40, TRUNC(SYSDATE) - 2,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95007, 'BB-T-95007', NULL,  90001, 'B+',  TRUNC(SYSDATE) - 45, TRUNC(SYSDATE) - 3,  'EXPIRED');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95008, 'BB-T-95008', NULL,  90001, 'B+',  TRUNC(SYSDATE) - 30, TRUNC(SYSDATE) + 5,  'RESERVED');
-- incompatible with a B+ patient but expiring earliest of all
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95009, 'BB-T-95009', 93001, 90001, 'A+',  TRUNC(SYSDATE) - 34, TRUNC(SYSDATE) + 1,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95010, 'BB-T-95010', 93006, 90001, 'AB-', TRUNC(SYSDATE) - 34, TRUNC(SYSDATE) + 1,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95011, 'BB-T-95011', 93007, 90001, 'O+',  TRUNC(SYSDATE) - 15, TRUNC(SYSDATE) + 20, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95012, 'BB-T-95012', NULL,  90001, 'B+',  TRUNC(SYSDATE) - 25, TRUNC(SYSDATE) + 10, 'ISSUED');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95013, 'BB-T-95013', NULL,  90001, 'B+',  TRUNC(SYSDATE) - 26, TRUNC(SYSDATE) + 9,  'DISCARDED');
-- Hospital 90002 (Tambaram): request 97002 (AB- x4), 3 compatible bags
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95101, 'BB-T-95101', 93015, 90002, 'AB-', TRUNC(SYSDATE) - 29, TRUNC(SYSDATE) + 6,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95102, 'BB-T-95102', 93010, 90002, 'O-',  TRUNC(SYSDATE) - 32, TRUNC(SYSDATE) + 3,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95103, 'BB-T-95103', 93002, 90002, 'A-',  TRUNC(SYSDATE) - 25, TRUNC(SYSDATE) + 10, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95104, 'BB-T-95104', 93005, 90002, 'AB+', TRUNC(SYSDATE) - 34, TRUNC(SYSDATE) + 1,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95105, 'BB-T-95105', 93013, 90002, 'O+',  TRUNC(SYSDATE) - 33, TRUNC(SYSDATE) + 2,  'AVAILABLE');
-- Hospital 90003 (Coimbatore): request 97003 (O- x2), no usable O-
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95201, 'BB-T-95201', NULL,  90003, 'O-',  TRUNC(SYSDATE) - 43, TRUNC(SYSDATE) - 1,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95202, 'BB-T-95202', NULL,  90003, 'O+',  TRUNC(SYSDATE) - 25, TRUNC(SYSDATE) + 10, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95203, 'BB-T-95203', 93011, 90003, 'A+',  TRUNC(SYSDATE) - 20, TRUNC(SYSDATE) + 15, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95204, 'BB-T-95204', NULL,  90003, 'O-',  TRUNC(SYSDATE) - 20, TRUNC(SYSDATE) + 15, 'RESERVED');
-- Hospital 90004 (Madurai): request 97004 (O+ x2)
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95301, 'BB-T-95301', NULL,  90004, 'O+',  TRUNC(SYSDATE) - 20, TRUNC(SYSDATE) + 15, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95302, 'BB-T-95302', NULL,  90004, 'O-',  TRUNC(SYSDATE) - 31, TRUNC(SYSDATE) + 4,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95303, 'BB-T-95303', NULL,  90004, 'O+',  TRUNC(SYSDATE) - 30, TRUNC(SYSDATE) + 5,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95304, 'BB-T-95304', NULL,  90004, 'A+',  TRUNC(SYSDATE) - 33, TRUNC(SYSDATE) + 2,  'AVAILABLE');
-- Hospital 90005 (Tiruchirappalli): request 97005 (AB+ x2)
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95401, 'BB-T-95401', NULL,  90005, 'A+',  TRUNC(SYSDATE) - 32, TRUNC(SYSDATE) + 3,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95402, 'BB-T-95402', NULL,  90005, 'O-',  TRUNC(SYSDATE) - 26, TRUNC(SYSDATE) + 9,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95403, 'BB-T-95403', NULL,  90005, 'B+',  TRUNC(SYSDATE) - 30, TRUNC(SYSDATE) + 5,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95404, 'BB-T-95404', NULL,  90005, 'AB+', TRUNC(SYSDATE) - 10, TRUNC(SYSDATE) + 25, 'AVAILABLE');
-- Hospital 90006 (Salem): requests 97006 (A- x1) and 97014 (A+ x5, partial off)
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95501, 'BB-T-95501', 93014, 90006, 'A-',  TRUNC(SYSDATE) - 22, TRUNC(SYSDATE) + 13, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95502, 'BB-T-95502', NULL,  90006, 'O-',  TRUNC(SYSDATE) - 30, TRUNC(SYSDATE) + 5,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95503, 'BB-T-95503', NULL,  90006, 'A+',  TRUNC(SYSDATE) - 34, TRUNC(SYSDATE) + 1,  'AVAILABLE');
-- Hospital 90007 (Katpadi): stock of all eight groups for dashboard tests
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95601, 'BB-T-95601', NULL,  90007, 'A+',  TRUNC(SYSDATE) - 24, TRUNC(SYSDATE) + 11, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95602, 'BB-T-95602', NULL,  90007, 'A+',  TRUNC(SYSDATE) - 29, TRUNC(SYSDATE) + 6,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95603, 'BB-T-95603', NULL,  90007, 'O+',  TRUNC(SYSDATE) - 12, TRUNC(SYSDATE) + 23, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95604, 'BB-T-95604', NULL,  90007, 'B+',  TRUNC(SYSDATE) - 28, TRUNC(SYSDATE) + 7,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95605, 'BB-T-95605', 93016, 90007, 'O-',  TRUNC(SYSDATE) - 5,  TRUNC(SYSDATE) + 30, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95606, 'BB-T-95606', NULL,  90007, 'AB+', TRUNC(SYSDATE) - 21, TRUNC(SYSDATE) + 14, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95607, 'BB-T-95607', NULL,  90007, 'AB-', TRUNC(SYSDATE) - 26, TRUNC(SYSDATE) + 9,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95608, 'BB-T-95608', NULL,  90007, 'A-',  TRUNC(SYSDATE) - 18, TRUNC(SYSDATE) + 17, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95609, 'BB-T-95609', NULL,  90007, 'B-',  TRUNC(SYSDATE) - 16, TRUNC(SYSDATE) + 19, 'AVAILABLE');
-- Hospital 90008 (Vellore): requests 97012 and 97013 compete for three B- bags
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95701, 'BB-T-95701', NULL,  90008, 'B-',  TRUNC(SYSDATE) - 32, TRUNC(SYSDATE) + 3,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95702, 'BB-T-95702', NULL,  90008, 'B-',  TRUNC(SYSDATE) - 29, TRUNC(SYSDATE) + 6,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95703, 'BB-T-95703', NULL,  90008, 'B-',  TRUNC(SYSDATE) - 26, TRUNC(SYSDATE) + 9,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95704, 'BB-T-95704', NULL,  90008, 'O+',  TRUNC(SYSDATE) - 31, TRUNC(SYSDATE) + 4,  'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95705, 'BB-T-95705', NULL,  90008, 'A-',  TRUNC(SYSDATE) - 20, TRUNC(SYSDATE) + 15, 'AVAILABLE');
-- Hospital 90009 (no coordinates)
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95801, 'BB-T-95801', NULL,  90009, 'O+',  TRUNC(SYSDATE) - 20, TRUNC(SYSDATE) + 15, 'AVAILABLE');
INSERT INTO blood_bags (bag_id, bag_code, donor_id, hospital_id, blood_group, collection_date, expiry_date, status) VALUES (95802, 'BB-T-95802', NULL,  90009, 'A+',  TRUNC(SYSDATE) - 20, TRUNC(SYSDATE) + 15, 'AVAILABLE');

-- ---- emergency requests ----
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97001, 91001, 'Patient A (TEST)', '9200000001', 'B+',  3, 0, 'CRITICAL', 90001, 'PENDING', SYSTIMESTAMP - INTERVAL '20' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97002, 91002, 'Patient B (TEST)', '9200000002', 'AB-', 4, 0, 'CRITICAL', 90002, 'PENDING', SYSTIMESTAMP - INTERVAL '25' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97003, 91003, 'Patient C (TEST)', '9200000003', 'O-',  2, 0, 'HIGH',     90003, 'PENDING', SYSTIMESTAMP - INTERVAL '30' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97004, 91001, 'Patient D (TEST)', '9200000004', 'O+',  2, 0, 'HIGH',     90004, 'PENDING', SYSTIMESTAMP - INTERVAL '35' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97005, 91002, 'Patient E (TEST)', '9200000005', 'AB+', 2, 0, 'NORMAL',   90005, 'PENDING', SYSTIMESTAMP - INTERVAL '40' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97006, 91003, 'Patient F (TEST)', '9200000006', 'A-',  1, 0, 'HIGH',     90006, 'PENDING', SYSTIMESTAMP - INTERVAL '45' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97007, 91001, 'Patient G (TEST)', '9200000007', 'B+',  1, 0, 'NORMAL',   90007, 'CANCELLED', SYSTIMESTAMP - INTERVAL '2' DAY, SYSTIMESTAMP - INTERVAL '2' DAY, 'Cancelled by requester (TEST)');
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97009, 91002, 'Patient H (TEST)', '9200000009', 'B+',  2, 2, 'HIGH',     90001, 'FULFILLED', SYSTIMESTAMP - INTERVAL '4' DAY, SYSTIMESTAMP - INTERVAL '4' DAY, 'Historical request (TEST)');
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97010, 91003, 'Patient I (TEST)', '9200000010', 'O-',  1, 1, 'CRITICAL', 90003, 'FULFILLED', SYSTIMESTAMP - INTERVAL '1' DAY, SYSTIMESTAMP - INTERVAL '1' DAY, 'Historical request (TEST)');
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97011, 91001, 'Patient J (TEST)', '9200000011', 'O-',  2, 0, 'HIGH',     90007, 'PENDING', SYSTIMESTAMP - INTERVAL '50' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97012, 91002, 'Patient K (TEST)', '9200000012', 'B-',  2, 0, 'CRITICAL', 90008, 'PENDING', SYSTIMESTAMP - INTERVAL '15' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97013, 91003, 'Patient L (TEST)', '9200000013', 'B-',  2, 0, 'CRITICAL', 90008, 'PENDING', SYSTIMESTAMP - INTERVAL '14' MINUTE, NULL, NULL);
INSERT INTO request (request_id, requester_user_id, patient_name, contact_phone, blood_group, units_required, units_allocated, priority, hospital_id, status, request_time, processed_at, remarks)
VALUES (97014, 91001, 'Patient M (TEST)', '9200000014', 'A+',  5, 0, 'NORMAL',   90006, 'PENDING', SYSTIMESTAMP - INTERVAL '55' MINUTE, NULL, NULL);

-- ---- allocations for the historical requests (consistent with bag status) ----
INSERT INTO allocation (allocation_id, request_id, bag_id, allocated_at, allocated_by, status)
VALUES (seq_allocation.NEXTVAL, 97009, 95008, SYSTIMESTAMP - INTERVAL '4' DAY, 'SEED_SCRIPT', 'RESERVED');
INSERT INTO allocation (allocation_id, request_id, bag_id, allocated_at, allocated_by, status)
VALUES (seq_allocation.NEXTVAL, 97009, 95012, SYSTIMESTAMP - INTERVAL '4' DAY, 'SEED_SCRIPT', 'ISSUED');
INSERT INTO allocation (allocation_id, request_id, bag_id, allocated_at, allocated_by, status)
VALUES (seq_allocation.NEXTVAL, 97010, 95204, SYSTIMESTAMP - INTERVAL '1' DAY, 'SEED_SCRIPT', 'RESERVED');

-- ---- status log: initial stock entry for every test bag, then the extra transitions ----
INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
SELECT seq_bag_status_log.NEXTVAL, b.bag_id, NULL, 'AVAILABLE', CAST(b.collection_date + 1 AS TIMESTAMP),
       'SEED_SCRIPT', 'Initial stock entry (TEST DATA)', NULL
  FROM blood_bags b
 WHERE b.bag_id BETWEEN 95001 AND 95999;

INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
VALUES (seq_bag_status_log.NEXTVAL, 95007, 'AVAILABLE', 'EXPIRED',   SYSTIMESTAMP - INTERVAL '3' DAY, 'SEED_SCRIPT', 'Expiry date passed (TEST DATA)', NULL);
INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
VALUES (seq_bag_status_log.NEXTVAL, 95013, 'AVAILABLE', 'DISCARDED', SYSTIMESTAMP - INTERVAL '5' DAY, 'SEED_SCRIPT', 'Haemolysed bag discarded (TEST DATA)', NULL);
INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
VALUES (seq_bag_status_log.NEXTVAL, 95008, 'AVAILABLE', 'RESERVED',  SYSTIMESTAMP - INTERVAL '4' DAY, 'SEED_SCRIPT', 'Reserved for request 97009 (TEST DATA)', 97009);
INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
VALUES (seq_bag_status_log.NEXTVAL, 95012, 'AVAILABLE', 'RESERVED',  SYSTIMESTAMP - INTERVAL '4' DAY, 'SEED_SCRIPT', 'Reserved for request 97009 (TEST DATA)', 97009);
INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
VALUES (seq_bag_status_log.NEXTVAL, 95012, 'RESERVED', 'ISSUED',     SYSTIMESTAMP - INTERVAL '3' DAY, 'SEED_SCRIPT', 'Issued to ward (TEST DATA)', 97009);
INSERT INTO bag_status_log (log_id, bag_id, old_status, new_status, changed_at, changed_by, reason, request_id)
VALUES (seq_bag_status_log.NEXTVAL, 95204, 'AVAILABLE', 'RESERVED',  SYSTIMESTAMP - INTERVAL '1' DAY, 'SEED_SCRIPT', 'Reserved for request 97010 (TEST DATA)', 97010);

COMMIT;

PROMPT sample_data.sql finished: test data loaded and committed.
