
ALTER TABLE cost_types DROP COLUMN ground;
ALTER TABLE cost_types DROP COLUMN insurance;
ALTER TABLE cost_types DROP COLUMN equipment;
ALTER TABLE cost_types DROP COLUMN catering;


DROP TRIGGER cost_types_bucket_required_insert;
DROP TRIGGER cost_types_bucket_required_update;

CREATE TRIGGER cost_types_bucket_required_insert
BEFORE INSERT ON cost_types
FOR EACH ROW
WHEN NEW.bucket IS NULL OR NEW.bucket NOT IN
     ('maintenance','crew','fuel','ownership','overhead','nav','airport',
      'ground','insurance','equipment','catering')
BEGIN
    SELECT RAISE(ABORT,
        'cost_types.bucket zorunlu ve şunlardan biri olmalı: maintenance, crew, fuel, ownership, overhead, nav, airport, ground, insurance, equipment, catering');
END;

CREATE TRIGGER cost_types_bucket_required_update
BEFORE UPDATE ON cost_types
FOR EACH ROW
WHEN NEW.bucket IS NULL OR NEW.bucket NOT IN
     ('maintenance','crew','fuel','ownership','overhead','nav','airport',
      'ground','insurance','equipment','catering')
BEGIN
    SELECT RAISE(ABORT,
        'cost_types.bucket zorunlu ve şunlardan biri olmalı: maintenance, crew, fuel, ownership, overhead, nav, airport, ground, insurance, equipment, catering');
END;


--Yeni maliyet tipleri 
INSERT INTO cost_types (id, name, bucket) VALUES
    (8,  'ground',    'ground'),
    (9,  'insurance', 'insurance'),
    (10, 'equipment', 'equipment'),
    (11, 'catering',  'catering');


--Yeni gelir tipleri
INSERT INTO revenue_types (id, name, bucket) VALUES
    (4, 'baggage', 'ancillary'),
    (5, 'mail',    'cargo');