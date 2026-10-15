-- schema_baseline.sql
-- PROPOSED baseline schema. Use ONLY if the project has no database schema yet.
-- If your repository already defines these tables, do not run this file; follow
-- schema_gaps.md and adapt the column/status names in the other scripts instead.
--
-- Target: Oracle Database 12.1 or later. Identity columns are not used, so
-- sequences supply all primary keys.
--
-- Safety: the first block aborts the script if any of these tables already
-- exists in the current schema, so nothing existing is altered or dropped.

SET DEFINE OFF
SET SQLBLANKLINES ON
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*)
    INTO v_count
    FROM user_tables
   WHERE table_name IN ('HOSPITAL', 'USERS', 'HOSPITAL_STAFF', 'DONOR',
                        'BLOOD_BAGS', 'REQUEST', 'ALLOCATION', 'BAG_STATUS_LOG');
  IF v_count > 0 THEN
    RAISE_APPLICATION_ERROR(-20001,
      'schema_baseline.sql refused to run: ' || v_count ||
      ' of the expected tables already exist in this schema.');
  END IF;
END;
/

CREATE SEQUENCE seq_hospital       START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_users          START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_hospital_staff START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_donor          START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_blood_bags     START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_request        START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_allocation     START WITH 100000 INCREMENT BY 1 NOCYCLE;
CREATE SEQUENCE seq_bag_status_log START WITH 100000 INCREMENT BY 1 NOCYCLE;

CREATE TABLE hospital (
  hospital_id  NUMBER(10)    NOT NULL,
  name         VARCHAR2(150) NOT NULL,
  address      VARCHAR2(300) NOT NULL,
  district     VARCHAR2(60)  NOT NULL,
  pincode      VARCHAR2(6)   NOT NULL,
  latitude     NUMBER(9,6),
  longitude    NUMBER(9,6),
  phone        VARCHAR2(20),
  is_active    CHAR(1)       DEFAULT 'Y' NOT NULL,
  created_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_hospital PRIMARY KEY (hospital_id),
  CONSTRAINT ck_hospital_pincode CHECK (REGEXP_LIKE(pincode, '^[1-9][0-9]{5}$')),
  CONSTRAINT ck_hospital_active  CHECK (is_active IN ('Y', 'N')),
  CONSTRAINT ck_hospital_coords  CHECK (
        (latitude IS NULL AND longitude IS NULL)
     OR (latitude IS NOT NULL AND longitude IS NOT NULL
         AND latitude BETWEEN -90 AND 90
         AND longitude BETWEEN -180 AND 180))
);

CREATE TABLE users (
  user_id             NUMBER(10)    NOT NULL,
  full_name           VARCHAR2(100) NOT NULL,
  email               VARCHAR2(254) NOT NULL,
  phone               VARCHAR2(15),
  password_hash       VARCHAR2(255) NOT NULL,
  user_role           VARCHAR2(20)  NOT NULL,
  account_status      VARCHAR2(20)  DEFAULT 'ACTIVE' NOT NULL,
  failed_login_count  NUMBER(3)     DEFAULT 0 NOT NULL,
  last_login_at       TIMESTAMP,
  created_at          TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_users PRIMARY KEY (user_id),
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT ck_users_email_lower CHECK (email = LOWER(email)),
  CONSTRAINT ck_users_role CHECK (user_role IN ('CITIZEN', 'HOSPITAL_STAFF', 'ADMIN')),
  CONSTRAINT ck_users_status CHECK (account_status IN
        ('ACTIVE', 'PENDING_VERIFICATION', 'LOCKED', 'DISABLED'))
);

CREATE TABLE hospital_staff (
  staff_id     NUMBER(10)   NOT NULL,
  user_id      NUMBER(10)   NOT NULL,
  hospital_id  NUMBER(10)   NOT NULL,
  designation  VARCHAR2(80),
  created_at   TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_hospital_staff PRIMARY KEY (staff_id),
  CONSTRAINT uq_staff_user UNIQUE (user_id),
  CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_staff_hospital FOREIGN KEY (hospital_id) REFERENCES hospital (hospital_id)
);

CREATE TABLE donor (
  donor_id            NUMBER(10)    NOT NULL,
  donor_code          VARCHAR2(30)  NOT NULL,
  full_name           VARCHAR2(100) NOT NULL,
  blood_group         VARCHAR2(3)   NOT NULL,
  date_of_birth       DATE,
  phone               VARCHAR2(15),
  district            VARCHAR2(60),
  last_donation_date  DATE,
  CONSTRAINT pk_donor PRIMARY KEY (donor_id),
  CONSTRAINT uq_donor_code UNIQUE (donor_code),
  CONSTRAINT uq_donor_id_group UNIQUE (donor_id, blood_group),
  CONSTRAINT ck_donor_group CHECK (blood_group IN
        ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'))
);

-- One row = one blood bag = one unit. Bags without a linked donor are allowed
-- (camp collections, inter-bank transfers); the composite FK is not enforced
-- when donor_id is NULL.
CREATE TABLE blood_bags (
  bag_id           NUMBER(10)   NOT NULL,
  bag_code         VARCHAR2(40) NOT NULL,
  donor_id         NUMBER(10),
  hospital_id      NUMBER(10)   NOT NULL,
  blood_group      VARCHAR2(3)  NOT NULL,
  collection_date  DATE         NOT NULL,
  expiry_date      DATE         NOT NULL,
  status           VARCHAR2(15) DEFAULT 'AVAILABLE' NOT NULL,
  volume_ml        NUMBER(4)    DEFAULT 350 NOT NULL,
  CONSTRAINT pk_blood_bags PRIMARY KEY (bag_id),
  CONSTRAINT uq_bag_code UNIQUE (bag_code),
  CONSTRAINT fk_bags_hospital FOREIGN KEY (hospital_id) REFERENCES hospital (hospital_id),
  CONSTRAINT fk_bags_donor FOREIGN KEY (donor_id, blood_group)
        REFERENCES donor (donor_id, blood_group),
  CONSTRAINT ck_bags_group CHECK (blood_group IN
        ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
  CONSTRAINT ck_bags_status CHECK (status IN
        ('AVAILABLE', 'RESERVED', 'ISSUED', 'EXPIRED', 'DISCARDED')),
  CONSTRAINT ck_bags_dates CHECK (expiry_date > collection_date)
);

CREATE TABLE request (
  request_id         NUMBER(10)    NOT NULL,
  requester_user_id  NUMBER(10),
  patient_name       VARCHAR2(100) NOT NULL,
  contact_phone      VARCHAR2(15),
  blood_group        VARCHAR2(3)   NOT NULL,
  units_required     NUMBER(3)     NOT NULL,
  units_allocated    NUMBER(3)     DEFAULT 0 NOT NULL,
  priority           VARCHAR2(10)  DEFAULT 'HIGH' NOT NULL,
  hospital_id        NUMBER(10)    NOT NULL,
  status             VARCHAR2(20)  DEFAULT 'PENDING' NOT NULL,
  request_time       TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  processed_at       TIMESTAMP,
  remarks            VARCHAR2(300),
  CONSTRAINT pk_request PRIMARY KEY (request_id),
  CONSTRAINT fk_request_user FOREIGN KEY (requester_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_request_hospital FOREIGN KEY (hospital_id) REFERENCES hospital (hospital_id),
  CONSTRAINT ck_request_group CHECK (blood_group IN
        ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
  CONSTRAINT ck_request_units CHECK (units_required > 0),
  CONSTRAINT ck_request_alloc CHECK (units_allocated >= 0 AND units_allocated <= units_required),
  CONSTRAINT ck_request_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'NORMAL')),
  CONSTRAINT ck_request_status CHECK (status IN
        ('PENDING', 'FULFILLED', 'PARTIALLY_FULFILLED', 'REJECTED', 'CANCELLED'))
);

CREATE TABLE allocation (
  allocation_id  NUMBER(10)    NOT NULL,
  request_id     NUMBER(10)    NOT NULL,
  bag_id         NUMBER(10)    NOT NULL,
  allocated_at   TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  allocated_by   VARCHAR2(60)  NOT NULL,
  status         VARCHAR2(10)  DEFAULT 'RESERVED' NOT NULL,
  CONSTRAINT pk_allocation PRIMARY KEY (allocation_id),
  CONSTRAINT uq_alloc_request_bag UNIQUE (request_id, bag_id),
  CONSTRAINT fk_alloc_request FOREIGN KEY (request_id) REFERENCES request (request_id),
  CONSTRAINT fk_alloc_bag FOREIGN KEY (bag_id) REFERENCES blood_bags (bag_id),
  CONSTRAINT ck_alloc_status CHECK (status IN ('RESERVED', 'ISSUED', 'RELEASED'))
);

-- A bag may be held by at most one live (RESERVED/ISSUED) allocation at a time.
-- RELEASED rows map to NULL and are not indexed, so history is unrestricted.
CREATE UNIQUE INDEX uq_alloc_active_bag ON allocation
  (CASE WHEN status IN ('RESERVED', 'ISSUED') THEN bag_id END);

CREATE TABLE bag_status_log (
  log_id      NUMBER(10)    NOT NULL,
  bag_id      NUMBER(10)    NOT NULL,
  old_status  VARCHAR2(15),
  new_status  VARCHAR2(15)  NOT NULL,
  changed_at  TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  changed_by  VARCHAR2(60)  NOT NULL,
  reason      VARCHAR2(200),
  request_id  NUMBER(10),
  CONSTRAINT pk_bag_status_log PRIMARY KEY (log_id),
  CONSTRAINT fk_log_bag FOREIGN KEY (bag_id) REFERENCES blood_bags (bag_id),
  CONSTRAINT fk_log_request FOREIGN KEY (request_id) REFERENCES request (request_id)
);

CREATE INDEX ix_bags_fefo        ON blood_bags (hospital_id, status, blood_group, expiry_date, collection_date, bag_id);
CREATE INDEX ix_bags_donor       ON blood_bags (donor_id);
CREATE INDEX ix_staff_hospital   ON hospital_staff (hospital_id);
CREATE INDEX ix_request_hospital ON request (hospital_id, status);
CREATE INDEX ix_request_user     ON request (requester_user_id);
CREATE INDEX ix_alloc_bag        ON allocation (bag_id);
CREATE INDEX ix_log_bag          ON bag_status_log (bag_id, changed_at);
CREATE INDEX ix_log_request      ON bag_status_log (request_id);

PROMPT schema_baseline.sql finished.
