-- Recovery ONLY for the observed ORA-54034 partial installation of migration 001.
-- Owner connection in local XEPDB1; no SYSDBA or CURRENT_SCHEMA substitution.
-- Do not run the original migration on a partial installation.
-- Creates exactly two missing indexes. No cleanup, DML, table/sequence DDL.
-- A second run deliberately fails preflight BEFORE DDL; it does not skip objects.
SET DEFINE OFF
SET SQLBLANKLINES ON
SET ECHO ON
SET SERVEROUTPUT ON
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

DECLARE
  n PLS_INTEGER;
  key_expression LONG;
BEGIN
  IF SYS_CONTEXT('USERENV','CON_NAME') <> 'XEPDB1'
     OR SYS_CONTEXT('USERENV','SESSION_USER') <> SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
     OR SYS_CONTEXT('USERENV','ISDBA') = 'TRUE' THEN
    RAISE_APPLICATION_ERROR(-20121, 'Connect directly as the existing owner in XEPDB1, without SYSDBA.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_tables WHERE table_name IN
    ('HOSPITAL','USERS','HOSPITAL_STAFF','DONOR','BLOOD_BAGS','REQUEST','ALLOCATION','BAG_STATUS_LOG');
  IF n <> 8 THEN
    RAISE_APPLICATION_ERROR(-20122, 'Expected all eight original application tables.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_objects WHERE object_name IN
    ('UQ_HRR_OPEN_ID','IX_HRR_QUEUE');
  IF n <> 0 THEN
    RAISE_APPLICATION_ERROR(-20123, 'A recovery target already exists. STOP; inspect it. No automatic rerun/cleanup.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_objects WHERE status = 'VALID' AND
    ((object_name = 'HOSPITAL_REGISTRATION_REQUEST' AND object_type = 'TABLE')
     OR (object_name = 'SEQ_HOSP_REG_REQUEST' AND object_type = 'SEQUENCE'));
  IF n <> 2 THEN
    RAISE_APPLICATION_ERROR(-20124, 'Expected the existing valid onboarding table and sequence.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_sequences WHERE sequence_name = 'SEQ_HOSP_REG_REQUEST'
    AND min_value = 1 AND max_value = 9999999999 AND increment_by = 1
    AND cycle_flag = 'N' AND cache_size = 20;
  IF n <> 1 THEN
    RAISE_APPLICATION_ERROR(-20125, 'Existing onboarding sequence configuration differs.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_tab_cols
    WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST' AND hidden_column = 'NO';
  IF n <> 24 THEN
    RAISE_APPLICATION_ERROR(-20126, 'Unexpected onboarding table shape.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_tab_cols WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST'
    AND virtual_column = 'NO' AND hidden_column = 'NO' AND nullable = 'N' AND data_type = 'VARCHAR2'
    AND ((column_name = 'STATUS' AND data_length = 10)
      OR (column_name = 'REGISTRATION_AUTHORITY' AND data_length = 80)
      OR (column_name = 'JURISDICTION_CODE' AND data_length = 30)
      OR (column_name = 'REGISTRATION_NUMBER' AND data_length = 100));
  IF n <> 4 THEN
    RAISE_APPLICATION_ERROR(-20127, 'Stored duplicate-key columns differ from the reviewed definition.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_tab_cols WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST'
    AND column_name = 'REGISTRATION_KEY' AND virtual_column = 'YES';
  IF n <> 1 THEN
    RAISE_APPLICATION_ERROR(-20128, 'Expected the existing virtual registration key.');
  END IF;
  SELECT data_default INTO key_expression FROM user_tab_cols
    WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST' AND column_name = 'REGISTRATION_KEY';
  IF REPLACE(key_expression, '"', '') <> 'UPPER(TRANSLATE(REGISTRATION_NUMBER,''x -/.'',''x''))'
     OR key_expression IS NULL THEN
    RAISE_APPLICATION_ERROR(-20129, 'Registration key normalization differs; STOP for review.');
  END IF;

  SELECT COUNT(*) INTO n FROM user_constraints WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST'
    AND status = 'ENABLED' AND validated = 'VALIDATED';
  IF n <> 27 THEN
    RAISE_APPLICATION_ERROR(-20130, 'Expected all 27 existing onboarding constraints enabled/validated.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_constraints WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST'
    AND constraint_name IN ('PK_HRR','UQ_HRR_HOSPITAL','FK_HRR_HOSPITAL','CK_HRR_TEXT','CK_HRR_IDENTITY',
      'CK_HRR_EMAIL','CK_HRR_PHONE','CK_HRR_PINCODE','CK_HRR_COORDS','CK_HRR_STATUS','CK_HRR_DECISION');
  IF n <> 11 THEN
    RAISE_APPLICATION_ERROR(-20131, 'Named onboarding constraints differ.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_constraints
    WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST' AND constraint_name = 'FK_HRR_HOSPITAL'
      AND constraint_type = 'R' AND r_owner = USER AND r_constraint_name = 'PK_HOSPITAL'
      AND delete_rule = 'NO ACTION';
  IF n <> 1 THEN
    RAISE_APPLICATION_ERROR(-20132, 'Hospital foreign key differs.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_indexes WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST';
  IF n <> 2 THEN
    RAISE_APPLICATION_ERROR(-20133, 'Unexpected indexes on onboarding table.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_indexes WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST'
    AND index_name IN ('PK_HRR','UQ_HRR_HOSPITAL') AND status = 'VALID' AND uniqueness = 'UNIQUE';
  IF n <> 2 THEN
    RAISE_APPLICATION_ERROR(-20134, 'Expected valid existing PK and hospital-link indexes.');
  END IF;
  SELECT COUNT(*) INTO n FROM user_triggers WHERE table_name = 'HOSPITAL_REGISTRATION_REQUEST';
  IF n <> 0 THEN
    RAISE_APPLICATION_ERROR(-20135, 'Unexpected onboarding trigger.');
  END IF;
  SELECT COUNT(*) INTO n FROM hospital_registration_request;
  IF n <> 0 THEN
    RAISE_APPLICATION_ERROR(-20136, 'Recovery reviewed for an empty application table only. STOP for data review.');
  END IF;
  DBMS_OUTPUT.PUT_LINE('Recovery preflight passed. Only the two missing indexes will be created.');
END;
/

CREATE UNIQUE INDEX uq_hrr_open_id ON hospital_registration_request (
  CASE WHEN status IN ('PENDING','APPROVED') THEN registration_authority END,
  CASE WHEN status IN ('PENDING','APPROVED') THEN jurisdiction_code END,
  CASE WHEN status IN ('PENDING','APPROVED')
    THEN UPPER(TRANSLATE(registration_number, 'x -/.', 'x')) END
);
CREATE INDEX ix_hrr_queue ON hospital_registration_request (status, submitted_at, registration_request_id);

PROMPT Recovery DDL completed. Verify both indexes and original tables read-only before declaring success.
EXIT SUCCESS
