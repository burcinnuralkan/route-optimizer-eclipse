PRAGMA legacy_alter_table = ON;
ALTER TABLE aircraft RENAME TO aircraft_old;
PRAGMA legacy_alter_table = OFF;

CREATE TABLE aircraft (
    tail_number       TEXT             NOT NULL,
    valid_from        TEXT             NOT NULL,
    aircraft_type     TEXT             NOT NULL,
    econ_seats        INTEGER          NOT NULL,
    bus_seats         INTEGER          NOT NULL,
    cargo_capacity_kg DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tail_number, valid_from)
);

-- İki tablonun birleşimi. INNER JOIN bilinçli: aircraft_seating satırı olmayan
-- bir kuyruk, hiç uçuşu olmayan kuyruktur (V9 kapasiteyi flights'tan türetti),
-- yani taşınacak kapasite verisi YOKTUR. Böyle bir satır için uydurma bir
-- koltuk sayısı yazmak, eksik veriyi ölçülebilir bir sayı gibi göstermek olurdu.
INSERT INTO aircraft (tail_number, valid_from, aircraft_type,
                      econ_seats, bus_seats, cargo_capacity_kg)
SELECT s.tail_number, s.valid_from, o.aircraft_type,
       s.econ_seats, s.bus_seats, s.cargo_capacity_kg
FROM aircraft_seating s
JOIN aircraft_old o ON o.tail_number = s.tail_number;

DROP TABLE aircraft_seating;
DROP TABLE aircraft_old;

CREATE TRIGGER aircraft_type_consistent_insert
BEFORE INSERT ON aircraft
FOR EACH ROW
WHEN EXISTS (SELECT 1 FROM aircraft
             WHERE tail_number = NEW.tail_number AND aircraft_type <> NEW.aircraft_type)
BEGIN
    SELECT RAISE(ABORT,
        'aynı kuyruk numarası iki farklı uçak tipiyle kaydedilemez');
END;

PRAGMA legacy_alter_table = ON;
ALTER TABLE flights RENAME TO flights_old;
PRAGMA legacy_alter_table = OFF;

DROP INDEX IF EXISTS idx_flights_from;
DROP INDEX IF EXISTS idx_flights_sched_dep;

CREATE TABLE flights (
    id           INTEGER PRIMARY KEY,
    flight_no    TEXT NOT NULL,
    airline_code TEXT NOT NULL,
    tail_number  TEXT NOT NULL,          -- yabancı anahtar değil, bkz. trigger
    from_code    TEXT NOT NULL REFERENCES airports(code),
    to_code      TEXT NOT NULL REFERENCES airports(code),
    sched_dep    TEXT NOT NULL,
    sched_arr    TEXT NOT NULL,
    actual_dep   TEXT,
    actual_arr   TEXT,
    econ_pax     REAL NOT NULL,
    bus_pax      REAL NOT NULL,
    cargo_kg     REAL NOT NULL,
    fuel_kg      REAL NOT NULL,
    distance_km  REAL NOT NULL,
    UNIQUE (flight_no, sched_dep)
);

INSERT INTO flights (
    id, flight_no, airline_code, tail_number, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_pax, bus_pax, cargo_kg, fuel_kg, distance_km)
SELECT
    id, flight_no, airline_code, tail_number, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_pax, bus_pax, cargo_kg, fuel_kg, distance_km
FROM flights_old;

DROP TABLE flights_old;

CREATE INDEX idx_flights_from ON flights(from_code);
CREATE INDEX idx_flights_sched_dep ON flights(sched_dep);

CREATE TRIGGER flights_aircraft_exists_insert
BEFORE INSERT ON flights
FOR EACH ROW
WHEN NOT EXISTS (SELECT 1 FROM aircraft WHERE tail_number = NEW.tail_number)
BEGIN
    SELECT RAISE(ABORT,
        'uçuşun kuyruk numarası aircraft tablosunda yok');
END;

CREATE TRIGGER flights_aircraft_exists_update
BEFORE UPDATE OF tail_number ON flights
FOR EACH ROW
WHEN NOT EXISTS (SELECT 1 FROM aircraft WHERE tail_number = NEW.tail_number)
BEGIN
    SELECT RAISE(ABORT,
        'uçuşun kuyruk numarası aircraft tablosunda yok');
END;

-- Kuyruğun ARA satırı silinebilir (yanlış girilmiş bir kabin yenilemesi geri
-- alınır); silinemeyen, uçuşu olan bir kuyruğun SON satırıdır — o an uçuşlar
-- kapasitesiz kalırdı. Yabancı anahtarın varsayılan ON DELETE RESTRICT
-- davranışı buydu.
CREATE TRIGGER aircraft_last_row_referenced_delete
BEFORE DELETE ON aircraft
FOR EACH ROW
WHEN (SELECT COUNT(*) FROM aircraft WHERE tail_number = OLD.tail_number) = 1
 AND EXISTS (SELECT 1 FROM flights WHERE tail_number = OLD.tail_number)
BEGIN
    SELECT RAISE(ABORT,
        'uçuşu olan kuyruk numarasının son satırı silinemez');
END;
 