CREATE TABLE aircraft_seating (
    tail_number       TEXT             NOT NULL REFERENCES aircraft(tail_number),
    -- Bu düzenin geçerli olmaya BAŞLADIĞI gün (ISO, YYYY-MM-DD). Bitiş tarihi
    -- yok ve olmamalı: bir sonraki satırın valid_from'u zaten bitişi belirtir,
    -- iki yerde tutmak ikisinin çelişmesine kapı açardı.
    valid_from        TEXT             NOT NULL,
    econ_seats        INTEGER          NOT NULL,
    bus_seats         INTEGER          NOT NULL,
    cargo_capacity_kg DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tail_number, valid_from)
);

CREATE TABLE seating_days_tmp AS
SELECT tail_number, day, econ_seats, bus_seats, cargo_capacity_kg
FROM (
    SELECT tail_number,
           substr(sched_dep, 1, 10) AS day,
           econ_seats, bus_seats, cargo_capacity_kg,
           -- Aynı kuyruk aynı gün iki farklı düzenle geçiyorsa (veri hatası)
           -- İLK KALKAN kazanır. Reddetmek de bir seçenekti ama tek bozuk
           -- satır yüzünden hiç göç edememek daha kötü.
           ROW_NUMBER() OVER (PARTITION BY tail_number, substr(sched_dep, 1, 10)
                              ORDER BY sched_dep, flight_no) AS rn
      FROM flights
) WHERE rn = 1;

INSERT INTO aircraft_seating (tail_number, valid_from, econ_seats, bus_seats, cargo_capacity_kg)
SELECT tail_number,
       CASE WHEN prev_day IS NULL THEN '1970-01-01' ELSE day END,
       econ_seats, bus_seats, cargo_capacity_kg
FROM (
    SELECT tail_number, day, econ_seats, bus_seats, cargo_capacity_kg,
           LAG(day)               OVER (PARTITION BY tail_number ORDER BY day) AS prev_day,
           LAG(econ_seats)        OVER (PARTITION BY tail_number ORDER BY day) AS prev_econ,
           LAG(bus_seats)         OVER (PARTITION BY tail_number ORDER BY day) AS prev_bus,
           LAG(cargo_capacity_kg) OVER (PARTITION BY tail_number ORDER BY day) AS prev_cargo
      FROM seating_days_tmp
)
WHERE prev_day IS NULL
   OR econ_seats <> prev_econ
   OR bus_seats <> prev_bus
   OR cargo_capacity_kg <> prev_cargo;

DROP TABLE seating_days_tmp;

PRAGMA legacy_alter_table = ON;
ALTER TABLE flights RENAME TO flights_old;
PRAGMA legacy_alter_table = OFF;

DROP INDEX IF EXISTS idx_flights_from;
DROP INDEX IF EXISTS idx_flights_sched_dep;

CREATE TABLE flights (
    id           INTEGER PRIMARY KEY,
    flight_no    TEXT NOT NULL,
    airline_code TEXT NOT NULL,
    tail_number  TEXT NOT NULL REFERENCES aircraft(tail_number),
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
 