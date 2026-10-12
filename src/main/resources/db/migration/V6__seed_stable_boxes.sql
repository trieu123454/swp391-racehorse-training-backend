-- Provide the initial stable catalog required by Flow 1.
-- Existing rows are preserved when this migration is applied to a non-empty database.
INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000001', 'A-01', 'Stable A', 4, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'A-01');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000002', 'A-02', 'Stable A', 4, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'A-02');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000003', 'C-03', 'Stable C', 4, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'C-03');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000004', 'D-04', 'Stable D', 3, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'D-04');

INSERT INTO stable_boxes (id, box_code, section, capacity, status)
SELECT '10000000-0000-0000-0000-000000000005', 'E-05', 'Stable E', 5, 'Available'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'E-05');