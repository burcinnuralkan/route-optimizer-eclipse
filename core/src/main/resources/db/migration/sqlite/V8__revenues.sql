CREATE TABLE revenue_types (
    id     INTEGER PRIMARY KEY,
    name   TEXT NOT NULL UNIQUE,
    bucket TEXT NOT NULL
);

INSERT INTO revenue_types (id, name, bucket) VALUES
    (1, 'pax',       'pax'),
    (2, 'ancillary', 'ancillary'),
    (3, 'cargo',     'cargo');

-- Kova zorunlu ve kapalı bir listeden. V3'teki cost_types kuralının aynısı:
-- SQLite sonradan NOT NULL yapamadığı için değil — burada tablo yeni, ama
-- CHECK yerine tetikleyici kullanmak iki tablonun hata mesajını da aynı
-- biçimde veriyor.
CREATE TRIGGER revenue_types_bucket_required_insert
BEFORE INSERT ON revenue_types
FOR EACH ROW
WHEN NEW.bucket IS NULL OR NEW.bucket NOT IN ('pax','ancillary','cargo')
BEGIN
    SELECT RAISE(ABORT,
        'revenue_types.bucket zorunlu ve şunlardan biri olmalı: pax, ancillary, cargo');
END;

CREATE TRIGGER revenue_types_bucket_required_update
BEFORE UPDATE ON revenue_types
FOR EACH ROW
WHEN NEW.bucket IS NULL OR NEW.bucket NOT IN ('pax','ancillary','cargo')
BEGIN
    SELECT RAISE(ABORT,
        'revenue_types.bucket zorunlu ve şunlardan biri olmalı: pax, ancillary, cargo');
END;

-- ── Gelir satırları ──────────────────────────────────────────────────────
-- rate_to_usd ve amount_usd SAKLANIR, her okumada yeniden hesaplanmaz —
-- costs'taki kararın aynısı: kur tablosundaki bir düzeltme geçen ayın gelirini
-- sessizce değiştirmesin.
CREATE TABLE revenues (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    flight_id       INTEGER          NOT NULL REFERENCES flights(id) ON DELETE CASCADE,
    revenue_type_id INTEGER          NOT NULL REFERENCES revenue_types(id),
    amount          DOUBLE PRECISION NOT NULL,
    currency        TEXT             NOT NULL,
    rate_to_usd     DOUBLE PRECISION NOT NULL,
    amount_usd      DOUBLE PRECISION NOT NULL
    -- (flight_id, revenue_type_id) üzerinde UNIQUE YOK ve olmamalı: bir uçuşun
    -- aynı tipten birden çok geliri olabilir (iki ayrı kargo faturası, biri
    -- EURO biri USD). Toplama okuma tarafında kovaya göre yapılıyor.
);

-- V6/V7'deki ISO 4217 kuralı buraya da geliyor. Gerekçe aynı ve zincirin
-- tamamını ilgilendiriyor: revenues.currency da exchange_rates.currency ile
-- eşleştiriliyor; tek bir tablonun serbest kalması kuralı işlevsiz bırakır.
CREATE TRIGGER revenues_currency_format_insert
BEFORE INSERT ON revenues
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'revenues.currency üç büyük harfli ISO 4217 kodu olmalı (ör. USD, EUR, TRY)');
END;

CREATE TRIGGER revenues_currency_format_update
BEFORE UPDATE ON revenues
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'revenues.currency üç büyük harfli ISO 4217 kodu olmalı (ör. USD, EUR, TRY)');
END;

-- Var olan gelirler zaten dolardı: kur 1, çevrilmiş tutar kendisi.
-- Sıfır olan gelirler satır olarak YAZILMAZ — "gelir yok" ile "gelir sıfır"
-- arasında fark yok. Tip başına ayrı INSERT: V2'deki gibi, veri taşıyan bir
-- migration'ın gözle doğrulanabilir olması akıllı sorgudan önemli.
INSERT INTO revenues (flight_id, revenue_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT id, 1, pax_revenue_usd, 'USD', 1.0, pax_revenue_usd
FROM flights WHERE pax_revenue_usd <> 0;

INSERT INTO revenues (flight_id, revenue_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT id, 2, ancillary_revenue_usd, 'USD', 1.0, ancillary_revenue_usd
FROM flights WHERE ancillary_revenue_usd <> 0;

INSERT INTO revenues (flight_id, revenue_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT id, 3, cargo_revenue_usd, 'USD', 1.0, cargo_revenue_usd
FROM flights WHERE cargo_revenue_usd <> 0;

CREATE INDEX idx_revenues_flight ON revenues(flight_id);

PRAGMA legacy_alter_table = ON;
ALTER TABLE flights RENAME TO flights_old;
PRAGMA legacy_alter_table = OFF;

DROP INDEX IF EXISTS idx_flights_from;
DROP INDEX IF EXISTS idx_flights_sched_dep;

CREATE TABLE flights (
    id                    INTEGER PRIMARY KEY,
    flight_no             TEXT    NOT NULL,
    airline_code          TEXT    NOT NULL,
    tail_number           TEXT    NOT NULL REFERENCES aircraft(tail_number),
    from_code             TEXT    NOT NULL REFERENCES airports(code),
    to_code               TEXT    NOT NULL REFERENCES airports(code),
    sched_dep             TEXT    NOT NULL,
    sched_arr             TEXT    NOT NULL,
    actual_dep            TEXT,
    actual_arr            TEXT,
    econ_seats            INTEGER NOT NULL,
    bus_seats             INTEGER NOT NULL,
    econ_pax              REAL    NOT NULL,
    bus_pax               REAL    NOT NULL,
    cargo_kg              REAL    NOT NULL,
    cargo_capacity_kg     REAL    NOT NULL,
    fuel_kg               REAL    NOT NULL,
    distance_km           REAL    NOT NULL,
    UNIQUE (flight_no, sched_dep)
);

-- id AÇIKÇA taşınıyor: costs.flight_id ve revenues.flight_id bu değerlere
-- bakıyor, yeniden numaralandırılsalardı her satır başka bir uçuşa bağlanırdı.
INSERT INTO flights (
    id, flight_no, airline_code, tail_number, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_seats, bus_seats, econ_pax, bus_pax, cargo_kg, cargo_capacity_kg,
    fuel_kg, distance_km)
SELECT
    id, flight_no, airline_code, tail_number, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_seats, bus_seats, econ_pax, bus_pax, cargo_kg, cargo_capacity_kg,
    fuel_kg, distance_km
FROM flights_old;

DROP TABLE flights_old;

-- Sayaç tablosundaki flights satırı da gidiyor; AUTOINCREMENT'siz tablo onu
-- okumaz ama kalan satır bir sonraki okuyanı yanıltır.
DELETE FROM sqlite_sequence WHERE name = 'flights';

CREATE INDEX idx_flights_from ON flights(from_code);
CREATE INDEX idx_flights_sched_dep ON flights(sched_dep);
 