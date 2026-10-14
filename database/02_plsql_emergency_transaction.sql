-- ============================================================================
-- SCRIPT: 02_plsql_emergency_transaction.sql
-- DESCRIPTION: PL/SQL Stored Procedure implementing the FEFO (First-Expiring,
--              First-Out) Emergency Blood Matching & Allocation Transaction.
--              Updates 4 tables atomically: REQUESTS, ALLOCATIONS, BLOOD_BAGS,
--              and BAG_STATUS_LOG.
-- ============================================================================

CREATE SEQUENCE seq_allocation_id START WITH 1000 INCREMENT BY 1;

CREATE OR REPLACE PROCEDURE PROC_ALLOCATE_EMERGENCY_BLOOD (
    p_request_id        IN  VARCHAR2,
    p_hospital_id       IN  VARCHAR2,
    p_blood_group       IN  VARCHAR2,
    p_rh_factor         IN  CHAR,
    p_units_requested   IN  NUMBER,
    p_priority          IN  VARCHAR2,
    p_units_allocated   OUT NUMBER,
    p_request_status    OUT VARCHAR2,
    p_message           OUT VARCHAR2
) AS
    v_allocated_count   NUMBER := 0;
    v_alloc_id          VARCHAR2(20);
    
    -- Cursor selecting available, unexpired blood bags matching type (FEFO order)
    CURSOR cur_available_bags IS
        SELECT bag_id
        FROM BLOOD_BAGS
        WHERE blood_group = p_blood_group
          AND rh_factor = p_rh_factor
          AND status = 'AVAILABLE'
          AND expiry_date >= TRUNC(SYSDATE)
        ORDER BY expiry_date ASC
        FOR UPDATE;
        
BEGIN
    -- 1. Insert Initial Request record with status PENDING
    INSERT INTO REQUESTS (
        request_id, hospital_id, req_blood_group, req_rh_factor,
        units_requested, priority, status, request_time
    ) VALUES (
        p_request_id, p_hospital_id, p_blood_group, p_rh_factor,
        p_units_requested, p_priority, 'PENDING', SYSTIMESTAMP
    );

    -- 2. Iterate through available bags in FEFO order up to requested units
    FOR bag_rec IN cur_available_bags LOOP
        EXIT WHEN v_allocated_count >= p_units_requested;
        
        v_alloc_id := 'ALC-' || seq_allocation_id.NEXTVAL;

        -- 2a. Update Bag Status to RESERVED
        UPDATE BLOOD_BAGS
        SET status = 'RESERVED'
        WHERE bag_id = bag_rec.bag_id;

        -- 2b. Insert into ALLOCATIONS junction table
        INSERT INTO ALLOCATIONS (
            allocation_id, request_id, bag_id, allocated_at
        ) VALUES (
            v_alloc_id, p_request_id, bag_rec.bag_id, SYSTIMESTAMP
        );

        -- 2c. Log the transition in BAG_STATUS_LOG for audit and hemovigilance
        INSERT INTO BAG_STATUS_LOG (
            bag_id, old_status, new_status, changed_at, reason
        ) VALUES (
            bag_rec.bag_id, 'AVAILABLE', 'RESERVED', SYSTIMESTAMP,
            'Auto-allocated to Emergency Request ' || p_request_id || ' (' || p_priority || ')'
        );

        v_allocated_count := v_allocated_count + 1;
    END LOOP;

    -- 3. Determine Final Request Status
    IF v_allocated_count = 0 THEN
        p_request_status := 'PENDING';
        p_message := 'No matching available units found in network. Request logged as PENDING.';
    ELSIF v_allocated_count < p_units_requested THEN
        p_request_status := 'PARTIALLY_FILLED';
        p_message := 'Partial allocation: ' || v_allocated_count || ' of ' || p_units_requested || ' units allocated.';
    ELSE
        p_request_status := 'FULFILLED';
        p_message := 'Success: All ' || v_allocated_count || ' units allocated via FEFO matching.';
    END IF;

    -- Update the request status
    UPDATE REQUESTS
    SET status = p_request_status
    WHERE request_id = p_request_id;

    p_units_allocated := v_allocated_count;
    COMMIT;

EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        p_units_allocated := 0;
        p_request_status := 'FAILED';
        p_message := 'Database Transaction Error: ' || SQLERRM;
END PROC_ALLOCATE_EMERGENCY_BLOOD;
/
