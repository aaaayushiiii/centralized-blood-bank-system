-- ============================================================================
-- SCRIPT: 00_phase1_core_schema.sql
-- DESCRIPTION: Phase 1 Core Relational Schema in Third Normal Form (3NF).
-- ============================================================================

-- Table 1: HOSPITALS
CREATE TABLE HOSPITALS (
    hospital_id     VARCHAR2(10)    PRIMARY KEY,
    name            VARCHAR2(100)   NOT NULL,
    latitude        NUMBER(9, 6)    NOT NULL,
    longitude       NUMBER(9, 6)    NOT NULL,
    address         VARCHAR2(255)   NOT NULL,
    phone           VARCHAR2(15)    NOT NULL UNIQUE
);

-- Table 2: DONORS
CREATE TABLE DONORS (
    donor_id            VARCHAR2(10)    PRIMARY KEY,
    full_name           VARCHAR2(100)   NOT NULL,
    blood_group         VARCHAR2(3)     NOT NULL CHECK (blood_group IN ('A', 'B', 'AB', 'O')),
    rh_factor           CHAR(1)         NOT NULL CHECK (rh_factor IN ('+', '-')),
    age                 NUMBER(3)       NOT NULL CHECK (age >= 18 AND age <= 65),
    gender              CHAR(1)         NOT NULL CHECK (gender IN ('M', 'F', 'O')),
    contact_number      VARCHAR2(15)    NOT NULL UNIQUE,
    last_donation_date  DATE
);

-- Table 3: BLOOD_BAGS
CREATE TABLE BLOOD_BAGS (
    bag_id              VARCHAR2(15)    PRIMARY KEY,
    blood_group         VARCHAR2(3)     NOT NULL CHECK (blood_group IN ('A', 'B', 'AB', 'O')),
    rh_factor           CHAR(1)         NOT NULL CHECK (rh_factor IN ('+', '-')),
    collection_date     DATE            DEFAULT SYSDATE NOT NULL,
    expiry_date         DATE            NOT NULL,
    status              VARCHAR2(15)    DEFAULT 'AVAILABLE' NOT NULL 
                                        CHECK (status IN ('AVAILABLE', 'RESERVED', 'DISPATCHED', 'EXPIRED', 'DISCARDED')),
    hospital_id         VARCHAR2(10)    NOT NULL,
    donor_id            VARCHAR2(10)    NOT NULL,
    CONSTRAINT fk_bag_hospital FOREIGN KEY (hospital_id) REFERENCES HOSPITALS(hospital_id) ON DELETE CASCADE,
    CONSTRAINT fk_bag_donor    FOREIGN KEY (donor_id)    REFERENCES DONORS(donor_id)    ON DELETE CASCADE,
    CONSTRAINT chk_bag_expiry  CHECK (expiry_date >= collection_date)
);

-- Table 4: REQUESTS
CREATE TABLE REQUESTS (
    request_id          VARCHAR2(15)    PRIMARY KEY,
    hospital_id         VARCHAR2(10)    NOT NULL,
    req_blood_group     VARCHAR2(3)     NOT NULL CHECK (req_blood_group IN ('A', 'B', 'AB', 'O')),
    req_rh_factor       CHAR(1)         NOT NULL CHECK (req_rh_factor IN ('+', '-')),
    units_requested     NUMBER(4)       NOT NULL CHECK (units_requested > 0),
    priority            VARCHAR2(10)    DEFAULT 'ROUTINE' NOT NULL 
                                        CHECK (priority IN ('ROUTINE', 'URGENT', 'CRITICAL')),
    status              VARCHAR2(15)    DEFAULT 'PENDING' NOT NULL 
                                        CHECK (status IN ('PENDING', 'PARTIALLY_FILLED', 'FULFILLED', 'CANCELLED')),
    request_time        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_req_hospital FOREIGN KEY (hospital_id) REFERENCES HOSPITALS(hospital_id) ON DELETE CASCADE
);

-- Table 5: ALLOCATIONS (M:N Junction Table)
CREATE TABLE ALLOCATIONS (
    allocation_id       VARCHAR2(20)    PRIMARY KEY,
    request_id          VARCHAR2(15)    NOT NULL,
    bag_id              VARCHAR2(15)    NOT NULL UNIQUE,
    allocated_at        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_alloc_request FOREIGN KEY (request_id) REFERENCES REQUESTS(request_id) ON DELETE CASCADE,
    CONSTRAINT fk_alloc_bag     FOREIGN KEY (bag_id)     REFERENCES BLOOD_BAGS(bag_id)   ON DELETE CASCADE
);

-- Table 6: BAG_STATUS_LOG (Audit Trail)
CREATE TABLE BAG_STATUS_LOG (
    log_id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bag_id              VARCHAR2(15)    NOT NULL,
    old_status          VARCHAR2(15)    NOT NULL,
    new_status          VARCHAR2(15)    NOT NULL,
    changed_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reason              VARCHAR2(255),
    CONSTRAINT fk_log_bag FOREIGN KEY (bag_id) REFERENCES BLOOD_BAGS(bag_id) ON DELETE CASCADE
);
