-- ============================================================================
-- SCRIPT: 03_seed_data.sql
-- DESCRIPTION: Master dataset for Phase 2 DML data population.
-- ============================================================================
SET DEFINE OFF;

-- 1. HOSPITALS (Bangalore & Vellore Medical Centers)
INSERT INTO HOSPITALS (hospital_id, name, latitude, longitude, address, phone)
VALUES ('HOSP-01', 'City Central Blood Bank & Hospital', 12.971598, 77.594562, '102 MG Road, Central District, Bangalore', '+918023456789');

INSERT INTO HOSPITALS (hospital_id, name, latitude, longitude, address, phone)
VALUES ('HOSP-02', 'St. Jude Care Multispecialty', 12.934440, 77.619230, '45 80-Feet Road, Koramangala 4th Block, Bangalore', '+918098765432');

INSERT INTO HOSPITALS (hospital_id, name, latitude, longitude, address, phone)
VALUES ('HOSP-03', 'Apex Trauma & Emergency Care', 12.916576, 77.599908, '78 Bannerghatta Main Road, BTM 2nd Stage, Bangalore', '+918012344321');

INSERT INTO HOSPITALS (hospital_id, name, latitude, longitude, address, phone)
VALUES ('HOSP-04', 'Apollo Tech Blood Center', 12.969819, 77.749977, 'ITPB Main Road, Whitefield, Bangalore', '+918055667788');

INSERT INTO HOSPITALS (hospital_id, name, latitude, longitude, address, phone)
VALUES ('HOSP-05', 'CMC Hospital & Research Blood Bank', 12.924900, 79.135400, 'Ida Scudder Road, Vellore Town, Vellore', '+914162281000');

-- 2. REGISTERED DONORS
INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-01', 'Rahul Sen', 'O', '+', 28, 'M', '+919876500001', TO_DATE('2026-06-10', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-02', 'Priya Rao', 'O', '-', 24, 'F', '+919876500002', TO_DATE('2026-05-15', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-03', 'Amit Patel', 'A', '+', 35, 'M', '+919876500003', TO_DATE('2026-07-20', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-04', 'Sneha Roy', 'A', '-', 29, 'F', '+919876500004', TO_DATE('2026-04-12', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-05', 'Karan Das', 'B', '+', 31, 'M', '+919876500005', TO_DATE('2026-06-25', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-06', 'Meera Iyer', 'B', '-', 26, 'F', '+919876500006', TO_DATE('2026-03-30', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-07', 'Arjun Nair', 'AB', '+', 27, 'M', '+919876500007', TO_DATE('2026-07-05', 'YYYY-MM-DD'));

INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date)
VALUES ('D-08', 'Divya Menon', 'AB', '-', 22, 'F', '+919876500008', TO_DATE('2026-05-18', 'YYYY-MM-DD'));

-- 3. BLOOD BAGS INVENTORY (Whole blood shelf life = 42 days)
-- City Central Hospital (HOSP-01) Stock
INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-101', 'O', '+', TO_DATE('2026-10-04', 'YYYY-MM-DD'), TO_DATE('2026-11-15', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-01');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-102', 'O', '-', TO_DATE('2026-09-24', 'YYYY-MM-DD'), TO_DATE('2026-11-05', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-02');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-103', 'A', '+', TO_DATE('2026-09-06', 'YYYY-MM-DD'), TO_DATE('2026-10-18', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-03');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-104', 'A', '-', TO_DATE('2026-10-09', 'YYYY-MM-DD'), TO_DATE('2026-11-20', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-04');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-105', 'B', '+', TO_DATE('2026-09-29', 'YYYY-MM-DD'), TO_DATE('2026-11-10', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-05');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-106', 'B', '-', TO_DATE('2026-10-02', 'YYYY-MM-DD'), TO_DATE('2026-11-13', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-06');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-107', 'AB', '+', TO_DATE('2026-10-06', 'YYYY-MM-DD'), TO_DATE('2026-11-17', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-07');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-108', 'AB', '-', TO_DATE('2026-09-04', 'YYYY-MM-DD'), TO_DATE('2026-10-16', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-01', 'D-08');

-- St. Jude Care (HOSP-02) Stock
INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-201', 'B', '+', TO_DATE('2026-09-30', 'YYYY-MM-DD'), TO_DATE('2026-11-11', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-02', 'D-05');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-202', 'AB', '-', TO_DATE('2026-09-26', 'YYYY-MM-DD'), TO_DATE('2026-11-07', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-02', 'D-08');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-203', 'O', '+', TO_DATE('2026-10-12', 'YYYY-MM-DD'), TO_DATE('2026-11-23', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-02', 'D-01');

INSERT INTO BLOOD_BAGS (bag_id, blood_group, rh_factor, collection_date, expiry_date, status, hospital_id, donor_id)
VALUES ('BAG-204', 'O', '-', TO_DATE('2026-09-05', 'YYYY-MM-DD'), TO_DATE('2026-10-17', 'YYYY-MM-DD'), 'AVAILABLE', 'HOSP-02', 'D-02');

-- Initial Intake Logs
INSERT INTO BAG_STATUS_LOG (bag_id, old_status, new_status, reason) VALUES ('BAG-101', 'COLLECTED', 'AVAILABLE', 'Screening passed and placed in quarantine storage');
INSERT INTO BAG_STATUS_LOG (bag_id, old_status, new_status, reason) VALUES ('BAG-102', 'COLLECTED', 'AVAILABLE', 'Screening passed and placed in quarantine storage');
INSERT INTO BAG_STATUS_LOG (bag_id, old_status, new_status, reason) VALUES ('BAG-103', 'COLLECTED', 'AVAILABLE', 'Screening passed and placed in quarantine storage');

-- Link staff to their respective hospitals
INSERT INTO HOSPITAL_STAFF (staff_id, user_id, hospital_id, role_title, department)
VALUES ('STF-001', 'USR-002', 'HOSP-01', 'Chief Blood Bank Officer', 'Transfusion Medicine');

INSERT INTO HOSPITAL_STAFF (staff_id, user_id, hospital_id, role_title, department)
VALUES ('STF-002', 'USR-003', 'HOSP-02', 'Blood Inventory Manager', 'Emergency Medicine');

COMMIT;
