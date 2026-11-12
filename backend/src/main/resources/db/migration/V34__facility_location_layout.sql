-- Bản đồ + sơ đồ cơ sở.
-- facilities: toạ độ (ghim bản đồ, cả hai cùng có hoặc cùng NULL) và số tầng.
-- storage_units: vị trí trong cơ sở = tầng + bên lối đi (nhìn từ cửa vào) + thứ tự tính từ cửa vào (1 = sát cửa).
-- Khoang cũ để NULL cả ba (chưa xếp vị trí); một vị trí chỉ có một khoang.

ALTER TABLE facilities
    ADD COLUMN latitude    NUMERIC(9, 6),
    ADD COLUMN longitude   NUMERIC(9, 6),
    ADD COLUMN floor_count SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE facilities
    ADD CONSTRAINT ck_facilities_floor_count CHECK (floor_count BETWEEN 1 AND 20),
    ADD CONSTRAINT ck_facilities_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

ALTER TABLE storage_units
    ADD COLUMN floor_no       SMALLINT,
    ADD COLUMN aisle_side     VARCHAR(5),
    ADD COLUMN aisle_position SMALLINT;

ALTER TABLE storage_units
    ADD CONSTRAINT ck_storage_units_location CHECK (
        (floor_no IS NULL AND aisle_side IS NULL AND aisle_position IS NULL)
        OR (floor_no BETWEEN 1 AND 20 AND aisle_side IN ('Left', 'Right') AND aisle_position BETWEEN 1 AND 200));

CREATE UNIQUE INDEX uq_storage_units_slot ON storage_units (facility_id, floor_no, aisle_side, aisle_position)
    WHERE floor_no IS NOT NULL;

-- Dữ liệu mẫu cho 2 cơ sở seed (chỉ khi chưa có toạ độ / khoang chưa xếp vị trí).
UPDATE facilities SET latitude = 10.729650, longitude = 106.718900, floor_count = 2
WHERE code = 'Q7' AND latitude IS NULL;
UPDATE facilities SET latitude = 10.850600, longitude = 106.771900
WHERE code = 'TD' AND latitude IS NULL;

UPDATE storage_units u
SET floor_no = s.floor_no, aisle_side = s.aisle_side, aisle_position = s.aisle_position
FROM (VALUES ('A-01', 1, 'Left', 1), ('A-02', 1, 'Left', 2), ('A-03', 1, 'Right', 1), ('A-04', 1, 'Right', 2),
             ('B-01', 2, 'Left', 1), ('B-02', 2, 'Left', 2), ('B-03', 2, 'Right', 1), ('C-01', 2, 'Right', 2))
         AS s(code, floor_no, aisle_side, aisle_position)
WHERE u.code = s.code
  AND u.facility_id = (SELECT id FROM facilities WHERE code = 'Q7')
  AND u.floor_no IS NULL;
