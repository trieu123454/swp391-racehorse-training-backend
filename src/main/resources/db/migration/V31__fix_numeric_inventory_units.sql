UPDATE inventory_items
SET unit = CASE
    WHEN LOWER(category) = 'medicine' THEN 'chai'
    WHEN LOWER(item_name) LIKE '%khô%' THEN 'bao'
    ELSE 'kg'
END
WHERE unit ~ '^[0-9]+(\.[0-9]+)?$';
