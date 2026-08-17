ALTER TABLE cost_types ADD COLUMN bucket TEXT;

-- Var olan yedi tip kendi adına eşlenir.
UPDATE cost_types SET bucket = name;

-- Bundan sonra eklenen her tip geçerli bir kova belirtmek zorunda.
-- (SQLite bir sütunu sonradan NOT NULL yapamaz; kısıt tetikleyiciyle kurulur.)
CREATE TRIGGER cost_types_bucket_required_insert
BEFORE INSERT ON cost_types
FOR EACH ROW
WHEN NEW.bucket IS NULL
     OR NEW.bucket NOT IN ('crew','ownership','maintenance','overhead','nav','airport','fuel')
BEGIN
    SELECT RAISE(ABORT,
        'cost_types.bucket zorunlu ve şunlardan biri olmalı: crew, ownership, maintenance, overhead, nav, airport, fuel');
END;

CREATE TRIGGER cost_types_bucket_required_update
BEFORE UPDATE ON cost_types
FOR EACH ROW
WHEN NEW.bucket IS NULL
     OR NEW.bucket NOT IN ('crew','ownership','maintenance','overhead','nav','airport','fuel')
BEGIN
    SELECT RAISE(ABORT,
        'cost_types.bucket zorunlu ve şunlardan biri olmalı: crew, ownership, maintenance, overhead, nav, airport, fuel');
END;