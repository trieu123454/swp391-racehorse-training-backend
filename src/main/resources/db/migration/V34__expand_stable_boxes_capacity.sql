-- Expand stable box capacity to comfortably support 120-150+ horses across sections A-E.
-- Uses idempotent UPDATE and INSERT ... WHERE NOT EXISTS for both PostgreSQL and H2 compatibility.

UPDATE stable_boxes
SET capacity = 8
WHERE box_code IN ('A-01', 'A-02', 'B-01', 'B-02', 'C-01', 'C-03', 'D-04', 'E-05')
  AND capacity < 8;

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000006', 'A-03', 'Stable A', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'A-03');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000007', 'A-04', 'Stable A', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'A-04');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000008', 'A-05', 'Stable A', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'A-05');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000009', 'B-01', 'Stable B', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'B-01');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000010', 'B-02', 'Stable B', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'B-02');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000011', 'B-03', 'Stable B', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'B-03');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000012', 'B-04', 'Stable B', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'B-04');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000013', 'B-05', 'Stable B', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'B-05');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000014', 'C-01', 'Stable C', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'C-01');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000015', 'C-02', 'Stable C', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'C-02');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000016', 'C-04', 'Stable C', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'C-04');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000017', 'C-05', 'Stable C', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'C-05');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000018', 'D-01', 'Stable D', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'D-01');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000019', 'D-02', 'Stable D', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'D-02');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000020', 'D-03', 'Stable D', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'D-03');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000021', 'D-05', 'Stable D', 8, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'D-05');

