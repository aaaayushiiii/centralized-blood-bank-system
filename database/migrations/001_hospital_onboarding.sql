-- Migration 001: hospital applications only. Operator-run; never application startup.
-- Run in a fresh SQL*Plus session connected as the existing schema owner in XEPDB1.
-- No existing object or row is altered. DDL commits implicitly: see README.md.
SET DEFINE OFF
SET SQLBLANKLINES ON
SET ECHO ON
SET SERVEROUTPUT ON
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- Fail closed before the first DDL. A rerun or partial installation is an error,
-- not evidence that a migration of the expected shape has already succeeded.
DECLARE
  n PLS_INTEGER;
BEGIN
  IF SYS_CONTEXT('USERENV','CON_NAME') <> 'XEPDB1'
     OR SYS_CONTEXT('USERENV','SESSION_USER') <> SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
     OR SYS_CONTEXT('USERENV','ISDBA') = 'TRUE' THEN
    RAISE_APPLICATION_ERROR(-20101, 'Connect directly as the existing schema owner in XEPDB1, without SYSDBA.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_tables WHERE table_name IN
    ('HOSPITAL','USERS','HOSPITAL_STAFF','DONOR','BLOOD_BAGS','REQUEST','ALLOCATION','BAG_STATUS_LOG');
  IF n <> 8 THEN
    RAISE_APPLICATION_ERROR(-20102, 'Expected all eight existing application tables in this schema.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'HOSPITAL' AND (
       (column_name = 'HOSPITAL_ID' AND data_type = 'NUMBER' AND data_precision = 10 AND data_scale = 0 AND nullable = 'N')
    OR (column_name = 'NAME' AND data_type = 'VARCHAR2' AND data_length = 150 AND nullable = 'N')
    OR (column_name = 'ADDRESS' AND data_type = 'VARCHAR2' AND data_length = 300 AND nullable = 'N')
    OR (column_name = 'DISTRICT' AND data_type = 'VARCHAR2' AND data_length = 60 AND nullable = 'N')
    OR (column_name = 'PINCODE' AND data_type = 'VARCHAR2' AND data_length = 6 AND nullable = 'N')
    OR (column_name IN ('LATITUDE','LONGITUDE') AND data_type = 'NUMBER' AND data_precision = 9 AND data_scale = 6 AND nullable = 'Y')
    OR (column_name = 'PHONE' AND data_type = 'VARCHAR2' AND data_length = 20)
    OR (column_name = 'IS_ACTIVE' AND data_type = 'CHAR' AND data_length = 1 AND nullable = 'N')
    OR (column_name = 'CREATED_AT' AND data_type LIKE 'TIMESTAMP%' AND nullable = 'N'));
  IF n <> 10 THEN
    RAISE_APPLICATION_ERROR(-20103, 'HOSPITAL column contract differs; inspect before adapting this migration.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_constraints c
   WHERE c.table_name = 'HOSPITAL' AND c.constraint_type = 'P'
     AND c.status = 'ENABLED' AND c.validated = 'VALIDATED'
     AND (SELECT COUNT(*) FROM user_cons_columns k WHERE k.constraint_name = c.constraint_name) = 1
     AND EXISTS (SELECT 1 FROM user_cons_columns k WHERE k.constraint_name = c.constraint_name AND k.column_name = 'HOSPITAL_ID');
  IF n <> 1 THEN
    RAISE_APPLICATION_ERROR(-20104, 'Expected enabled, validated HOSPITAL(HOSPITAL_ID) primary key.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_constraints WHERE table_name = 'HOSPITAL'
    AND constraint_name IN ('CK_HOSPITAL_ACTIVE','CK_HOSPITAL_PINCODE','CK_HOSPITAL_COORDS')
    AND status = 'ENABLED' AND validated = 'VALIDATED';
  IF n <> 3 THEN
    RAISE_APPLICATION_ERROR(-20105, 'Expected existing hospital checks; inspect their definitions.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_sequences WHERE sequence_name IN
    ('SEQ_HOSPITAL','SEQ_USERS','SEQ_HOSPITAL_STAFF','SEQ_DONOR','SEQ_BLOOD_BAGS','SEQ_REQUEST','SEQ_ALLOCATION','SEQ_BAG_STATUS_LOG')
    AND increment_by = 1 AND cycle_flag = 'N';
  IF n <> 8 THEN
    RAISE_APPLICATION_ERROR(-20106, 'Expected eight existing noncycling application sequences.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_objects WHERE object_name IN
    ('HOSPITAL_REGISTRATION_REQUEST','SEQ_HOSP_REG_REQUEST','PK_HRR','UQ_HRR_HOSPITAL','UQ_HRR_OPEN_ID','IX_HRR_QUEUE');
  IF n <> 0 THEN
    RAISE_APPLICATION_ERROR(-20107, 'Migration target object exists. Stop and inspect; do not rerun or drop it automatically.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_constraints WHERE constraint_name IN
    ('PK_HRR','UQ_HRR_HOSPITAL','FK_HRR_HOSPITAL','CK_HRR_TEXT','CK_HRR_IDENTITY',
     'CK_HRR_EMAIL','CK_HRR_PHONE','CK_HRR_PINCODE','CK_HRR_COORDS','CK_HRR_STATUS','CK_HRR_DECISION');
  IF n <> 0 THEN
    RAISE_APPLICATION_ERROR(-20108, 'Migration constraint name already exists.');
  END IF;
  DBMS_OUTPUT.PUT_LINE('Preflight passed. Creating only new onboarding objects.');
END;
/

CREATE SEQUENCE seq_hosp_reg_request
  START WITH 100000 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NOCYCLE CACHE 20;

CREATE TABLE hospital_registration_request (
  registration_request_id NUMBER(10) NOT NULL,
  hospital_name           VARCHAR2(150 BYTE) NOT NULL,
  address                 VARCHAR2(300 BYTE) NOT NULL,
  district                VARCHAR2(60 BYTE) NOT NULL,
  pincode                 VARCHAR2(6 BYTE) NOT NULL,
  latitude                NUMBER(9,6),
  longitude               NUMBER(9,6),
  official_phone          VARCHAR2(20 BYTE) NOT NULL,
  official_email          VARCHAR2(254 BYTE) NOT NULL,
  registration_authority  VARCHAR2(80 BYTE) NOT NULL,
  jurisdiction_code       VARCHAR2(30 BYTE) NOT NULL,
  registration_number     VARCHAR2(100 BYTE) NOT NULL,
  -- Deterministic normalization; do not use REGEXP_REPLACE in an indexed key.
  registration_key GENERATED ALWAYS AS
    (UPPER(TRANSLATE(registration_number, 'x -/.', 'x'))) VIRTUAL,
  representative_name     VARCHAR2(100 BYTE) NOT NULL,
  representative_title    VARCHAR2(80 BYTE) NOT NULL,
  representative_email    VARCHAR2(254 BYTE) NOT NULL,
  representative_phone    VARCHAR2(20 BYTE) NOT NULL,
  status                  VARCHAR2(10 BYTE) DEFAULT 'PENDING' NOT NULL,
  submitted_at            TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
  reviewed_at             TIMESTAMP(6),
  reviewed_by             VARCHAR2(128 BYTE),
  verification_notes      VARCHAR2(2000 BYTE),
  rejection_reason        VARCHAR2(1000 BYTE),
  approved_hospital_id    NUMBER(10),
  CONSTRAINT pk_hrr PRIMARY KEY (registration_request_id),
  CONSTRAINT uq_hrr_hospital UNIQUE (approved_hospital_id),
  CONSTRAINT fk_hrr_hospital FOREIGN KEY (approved_hospital_id)
    REFERENCES hospital (hospital_id),
  CONSTRAINT ck_hrr_text CHECK (
    REGEXP_LIKE(hospital_name, '[^[:space:]]') AND REGEXP_LIKE(address, '[^[:space:]]')
    AND REGEXP_LIKE(district, '[^[:space:]]') AND REGEXP_LIKE(representative_name, '[^[:space:]]')
    AND REGEXP_LIKE(representative_title, '[^[:space:]]')),
  -- Authority/jurisdiction are canonical codes, not free-text names or aliases.
  CONSTRAINT ck_hrr_identity CHECK (
    REGEXP_LIKE(registration_authority, '^[A-Z0-9_]+$', 'c')
    AND REGEXP_LIKE(jurisdiction_code, '^[A-Z0-9_]+$', 'c')
    AND REGEXP_LIKE(registration_number, '^[A-Za-z0-9 ./-]+$', 'c')
    AND registration_key IS NOT NULL),
  CONSTRAINT ck_hrr_email CHECK (
    official_email = LOWER(TRIM(official_email))
    AND representative_email = LOWER(TRIM(representative_email))
    AND REGEXP_LIKE(official_email, '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$')
    AND REGEXP_LIKE(representative_email, '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$')),
  CONSTRAINT ck_hrr_phone CHECK (
    REGEXP_LIKE(official_phone, '^[+]?[0-9 ()-]{7,20}$')
    AND REGEXP_LIKE(representative_phone, '^[+]?[0-9 ()-]{7,20}$')
    AND REGEXP_LIKE(official_phone, '[0-9]') AND REGEXP_LIKE(representative_phone, '[0-9]')),
  CONSTRAINT ck_hrr_pincode CHECK (REGEXP_LIKE(pincode, '^[1-9][0-9]{5}$')),
  CONSTRAINT ck_hrr_coords CHECK (
    (latitude IS NULL AND longitude IS NULL)
    OR (latitude IS NOT NULL AND longitude IS NOT NULL
        AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
  CONSTRAINT ck_hrr_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  -- Explicit NULL tests avoid Oracle CHECK's UNKNOWN-is-accepted behavior.
  CONSTRAINT ck_hrr_decision CHECK (
    (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL
      AND verification_notes IS NULL AND rejection_reason IS NULL AND approved_hospital_id IS NULL)
    OR (status = 'APPROVED' AND reviewed_at IS NOT NULL AND reviewed_at >= submitted_at
      AND reviewed_by IS NOT NULL AND REGEXP_LIKE(reviewed_by, '[^[:space:]]')
      AND verification_notes IS NOT NULL AND REGEXP_LIKE(verification_notes, '[^[:space:]]')
      AND rejection_reason IS NULL AND approved_hospital_id IS NOT NULL)
    OR (status = 'REJECTED' AND reviewed_at IS NOT NULL AND reviewed_at >= submitted_at
      AND reviewed_by IS NOT NULL AND REGEXP_LIKE(reviewed_by, '[^[:space:]]')
      AND verification_notes IS NOT NULL AND REGEXP_LIKE(verification_notes, '[^[:space:]]')
      AND rejection_reason IS NOT NULL AND REGEXP_LIKE(rejection_reason, '[^[:space:]]')
      AND approved_hospital_id IS NULL))
);

-- All expressions are NULL for rejected rows: history permits reapplication.
-- Pending AND approved applications reserve the same identity, even under races.
-- Oracle ORA-54034 forbids using virtual REGISTRATION_KEY inside CASE here.
-- Repeat its deterministic expression over the stored REGISTRATION_NUMBER.
CREATE UNIQUE INDEX uq_hrr_open_id ON hospital_registration_request (
  CASE WHEN status IN ('PENDING','APPROVED') THEN registration_authority END,
  CASE WHEN status IN ('PENDING','APPROVED') THEN jurisdiction_code END,
  CASE WHEN status IN ('PENDING','APPROVED')
    THEN UPPER(TRANSLATE(registration_number, 'x -/.', 'x')) END
);
CREATE INDEX ix_hrr_queue ON hospital_registration_request (status, submitted_at, registration_request_id);

PROMPT Migration 001 DDL completed. Run the read-only postflight in migrations/README.md.
EXIT SUCCESS
