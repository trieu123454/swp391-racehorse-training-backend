-- Đảm bảo chuồng điều trị chấn thương là chuồng riêng biệt (1 ngựa/chuồng, capacity = 1) để tĩnh dưỡng và theo dõi
UPDATE stable_boxes SET capacity = 1 WHERE box_type = 'Injury';

-- Bổ sung thêm chuồng điều trị chấn thương I-03 (capacity = 1)
INSERT INTO stable_boxes (id, box_code, section, capacity, status, box_type)
SELECT '10000000-0000-0000-0000-0000000000b3', 'I-03', 'Khu điều trị chấn thương', 1, 'Available', 'Injury'
WHERE NOT EXISTS (SELECT 1 FROM stable_boxes WHERE box_code = 'I-03');

