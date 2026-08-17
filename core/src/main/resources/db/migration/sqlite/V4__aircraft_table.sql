CREATE TABLE aircraft (
    tail_number   TEXT PRIMARY KEY,
    aircraft_type TEXT NOT NULL
);

INSERT INTO aircraft (tail_number, aircraft_type)
SELECT DISTINCT tail_number, aircraft_type FROM flights;
PRAGMA legacy_alter_table = ON; --flights tablosunu flights_old yaparken costs tablosundaki foreign key tanımlarını otomatik değiştirme diyor sqlite a
ALTER TABLE flights RENAME TO flights_old; --sadece isim değişiyor
PRAGMA legacy_alter_table = OFF;

DROP INDEX IF EXISTS idx_flights_from;
DROP INDEX IF EXISTS idx_flights_sched_dep;

CREATE TABLE flights (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
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
    pax_revenue_usd       REAL    NOT NULL,
    ancillary_revenue_usd REAL    NOT NULL,
    cargo_revenue_usd     REAL    NOT NULL,
    fuel_kg               REAL    NOT NULL,
    distance_km           REAL    NOT NULL,
    UNIQUE (flight_no, sched_dep)
);

INSERT INTO flights (
    id, flight_no, airline_code, tail_number, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_seats, bus_seats, econ_pax, bus_pax, cargo_kg, cargo_capacity_kg,
    pax_revenue_usd, ancillary_revenue_usd, cargo_revenue_usd, fuel_kg, distance_km)
SELECT
    id, flight_no, airline_code, tail_number, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_seats, bus_seats, econ_pax, bus_pax, cargo_kg, cargo_capacity_kg,
    pax_revenue_usd, ancillary_revenue_usd, cargo_revenue_usd, fuel_kg, distance_km
FROM flights_old;

DROP TABLE flights_old;

CREATE INDEX idx_flights_from ON flights(from_code);
CREATE INDEX idx_flights_sched_dep ON flights(sched_dep);