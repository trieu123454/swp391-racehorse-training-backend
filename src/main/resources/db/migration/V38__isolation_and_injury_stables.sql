-- Flow 3: chuồng chuyên biệt cho ngựa cách ly (Quarantine) và ngựa chấn thương (Injured).
-- box_type: Normal = chuồng thường, Quarantine = chuồng cách ly, Injury = chuồng điều trị chấn thương.
ALTER TABLE stable_boxes ADD COLUMN box_type VARCHAR(20) NOT NULL DEFAULT 'Normal';
ALTER TABLE stable_boxes ADD CONSTRAINT ck_stable_boxes_type CHECK (box_type IN ('Normal', 'Quarantine', 'Injury'));

-- Lưu chuồng gốc để đưa ngựa về khi hết cách ly / hồi phục.
ALTER TABLE horses ADD COLUMN home_stable_box_id CHAR(36);
ALTER TABLE horses ADD CONSTRAINT fk_horses_home_stable_box_id
    FOREIGN KEY (home_stable_box_id) REFERENCES stable_boxes (id) ON DELETE SET NULL;

-- Chuồng cách ly: mỗi chuồng 1 ngựa để tránh lây chéo.
INSERT INTO stable_boxes (id, box_code, section, capacity, status, box_type)
SELECT '10000000-0000-0000-0000-0000000000a1', 'Q-01', 'Khu cách ly', 1, 'Available', 'Quarantine'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'Q-01');
INSERT INTO stable_boxes (id, box_code, section, capacity, status, box_type)
SELECT '10000000-0000-0000-0000-0000000000a2', 'Q-02', 'Khu cách ly', 1, 'Available', 'Quarantine'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'Q-02');
INSERT INTO stable_boxes (id, box_code, section, capacity, status, box_type)
SELECT '10000000-0000-0000-0000-0000000000a3', 'Q-03', 'Khu cách ly', 1, 'Available', 'Quarantine'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'Q-03');

-- Chuồng điều trị chấn thương.
INSERT INTO stable_boxes (id, box_code, section, capacity, status, box_type)
SELECT '10000000-0000-0000-0000-0000000000b1', 'I-01', 'Khu điều trị chấn thương', 2, 'Available', 'Injury'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'I-01');
INSERT INTO stable_boxes (id, box_code, section, capacity, status, box_type)
SELECT '10000000-0000-0000-0000-0000000000b2', 'I-02', 'Khu điều trị chấn thương', 2, 'Available', 'Injury'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'I-02');
