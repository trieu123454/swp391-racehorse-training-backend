ALTER TABLE stable_incidents
    DROP CONSTRAINT ck_stable_incidents_assigned_role;

ALTER TABLE stable_incidents
    ADD CONSTRAINT ck_stable_incidents_assigned_role
    CHECK (assigned_role IS NULL OR assigned_role IN ('HEAD_TRAINER', 'VETERINARIAN', 'GROOM', 'CLUB_MANAGER'));
