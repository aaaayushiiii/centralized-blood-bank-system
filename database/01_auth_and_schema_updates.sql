-- ============================================================================
-- SCRIPT: 01_auth_and_schema_updates.sql
-- DESCRIPTION: Authentication tables (USERS, HOSPITAL_STAFF) and Geo-Pincode
--              lookup tables for Oracle Database (BACSE202 Course Project).
-- ============================================================================

-- 1. USERS TABLE (Citizen & Hospital Staff Authentication)
CREATE TABLE USERS (
    user_id         VARCHAR2(20)    PRIMARY KEY,
    email           VARCHAR2(100)   NOT NULL UNIQUE,
    password_hash   VARCHAR2(255)   NOT NULL,
    role            VARCHAR2(20)    NOT NULL CHECK (role IN ('CITIZEN', 'HOSPITAL_STAFF', 'ADMIN')),
    full_name       VARCHAR2(100)   NOT NULL,
    phone           VARCHAR2(15)    NOT NULL,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. HOSPITAL_STAFF TABLE (Links Staff User Account to a specific Hospital)
-- Ensures hospital staff only manage their own hospital's inventory
CREATE TABLE HOSPITAL_STAFF (
    staff_id        VARCHAR2(20)    PRIMARY KEY,
    user_id         VARCHAR2(20)    NOT NULL UNIQUE,
    hospital_id     VARCHAR2(10)    NOT NULL,
    role_title      VARCHAR2(50)    DEFAULT 'Blood Bank Officer',
    department      VARCHAR2(50)    DEFAULT 'Transfusion Medicine',
    CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES USERS(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_hosp FOREIGN KEY (hospital_id) REFERENCES HOSPITALS(hospital_id) ON DELETE CASCADE
);

-- 3. LINK DONORS TO CITIZEN USER ACCOUNT (Optional for Citizen profile integration)
ALTER TABLE DONORS ADD (user_id VARCHAR2(20));
ALTER TABLE DONORS ADD CONSTRAINT fk_donor_user FOREIGN KEY (user_id) REFERENCES USERS(user_id) ON DELETE SET NULL;

-- 4. PINCODE TO COORDINATES LOOKUP (Enables search by pincode)
CREATE TABLE PINCODE_COORDINATES (
    pincode         VARCHAR2(10)    PRIMARY KEY,
    area_name       VARCHAR2(100)   NOT NULL,
    city            VARCHAR2(50)    NOT NULL,
    latitude        NUMBER(9, 6)    NOT NULL,
    longitude       NUMBER(9, 6)    NOT NULL
);

-- ============================================================================
-- SEED DATA: Pincodes (Bangalore / Vellore / Chennai sample coordinates)
-- ============================================================================
INSERT INTO PINCODE_COORDINATES VALUES ('560001', 'MG Road / Bangalore Central', 'Bangalore', 12.971598, 77.594562);
INSERT INTO PINCODE_COORDINATES VALUES ('560034', 'Koramangala 4th Block', 'Bangalore', 12.934440, 77.619230);
INSERT INTO PINCODE_COORDINATES VALUES ('560066', 'Whitefield Main Road', 'Bangalore', 12.969819, 77.749977);
INSERT INTO PINCODE_COORDINATES VALUES ('560029', 'Bannerghatta Road / BTM', 'Bangalore', 12.916576, 77.599908);
INSERT INTO PINCODE_COORDINATES VALUES ('632014', 'VIT Campus / Katpadi', 'Vellore', 12.969200, 79.155900);
INSERT INTO PINCODE_COORDINATES VALUES ('632001', 'Vellore Town / Fort', 'Vellore', 12.924900, 79.135400);

-- ============================================================================
-- SEED DATA: Demo Users & Staff
-- ============================================================================
-- Password for all demo accounts: 'password123' (SHA-256: ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f)
INSERT INTO USERS (user_id, email, password_hash, role, full_name, phone)
VALUES ('USR-001', 'citizen@demo.com', 'ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f', 'CITIZEN', 'Ananya Sharma', '+919876543210');

INSERT INTO USERS (user_id, email, password_hash, role, full_name, phone)
VALUES ('USR-002', 'staff.central@cityhospital.org', 'ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f', 'HOSPITAL_STAFF', 'Dr. Ramesh Kumar', '+919811223344');

INSERT INTO USERS (user_id, email, password_hash, role, full_name, phone)
VALUES ('USR-003', 'staff.stjude@stjude.org', 'ef92b778bafe771e89245b89ecbc08a44a4e166c06659911881f383d4473e94f', 'HOSPITAL_STAFF', 'Sister Mary Varghese', '+919822334455');

-- Link staff to their respective hospitals (matches HOSPITALS table from Phase 1)
-- City Central Hospital: 'HOSP-01'
-- St. Jude Care Hospital: 'HOSP-02'
INSERT INTO HOSPITAL_STAFF (staff_id, user_id, hospital_id, role_title, department)
VALUES ('STF-001', 'USR-002', 'HOSP-01', 'Chief Blood Bank Officer', 'Transfusion Medicine');

INSERT INTO HOSPITAL_STAFF (staff_id, user_id, hospital_id, role_title, department)
VALUES ('STF-002', 'USR-003', 'HOSP-02', 'Blood Inventory Manager', 'Emergency Medicine');

COMMIT;
