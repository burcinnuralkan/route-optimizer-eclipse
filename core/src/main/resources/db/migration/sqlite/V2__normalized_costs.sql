CREATE TABLE cost_types (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO cost_types (id, name) VALUES
    (1, 'maintenance'),
    (2, 'crew'),
    (3, 'fuel'),
    (4, 'ownership'),
    (5, 'overhead'),
    (6, 'nav'),
    (7, 'airport');

-- Kur tablosu
CREATE TABLE exchange_rates (
    rate_date   TEXT             NOT NULL,
    currency    TEXT             NOT NULL,
    rate_to_usd DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (rate_date, currency)
);

-- ── flights: yapay anahtar
ALTER TABLE flights RENAME TO flights_old;
DROP INDEX IF EXISTS idx_flights_from;
DROP INDEX IF EXISTS idx_flights_sched_dep;

CREATE TABLE flights (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    flight_no             TEXT    NOT NULL,
    airline_code          TEXT    NOT NULL,
    tail_number           TEXT    NOT NULL,
    aircraft_type         TEXT    NOT NULL,
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
    flight_no, airline_code, tail_number, aircraft_type, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_seats, bus_seats, econ_pax, bus_pax, cargo_kg, cargo_capacity_kg,
    pax_revenue_usd, ancillary_revenue_usd, cargo_revenue_usd, fuel_kg, distance_km)
SELECT
    flight_no, airline_code, tail_number, aircraft_type, from_code, to_code,
    sched_dep, sched_arr, actual_dep, actual_arr,
    econ_seats, bus_seats, econ_pax, bus_pax, cargo_kg, cargo_capacity_kg,
    pax_revenue_usd, ancillary_revenue_usd, cargo_revenue_usd, fuel_kg, distance_km
FROM flights_old;

-- ── Maliyet satırları 
CREATE TABLE costs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    flight_id    INTEGER          NOT NULL REFERENCES flights(id) ON DELETE CASCADE,
    cost_type_id INTEGER          NOT NULL REFERENCES cost_types(id),
    amount       DOUBLE PRECISION NOT NULL,
    currency     TEXT             NOT NULL,
    rate_to_usd  DOUBLE PRECISION NOT NULL,
    amount_usd   DOUBLE PRECISION NOT NULL
);

INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT f.id, 2, o.crew_cost_usd, 'USD', 1.0, o.crew_cost_usd
FROM flights_old o JOIN flights f
  ON f.flight_no = o.flight_no AND f.sched_dep = o.sched_dep
WHERE o.crew_cost_usd <> 0;

INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT f.id, 4, o.ownership_cost_usd, 'USD', 1.0, o.ownership_cost_usd
FROM flights_old o JOIN flights f
  ON f.flight_no = o.flight_no AND f.sched_dep = o.sched_dep
WHERE o.ownership_cost_usd <> 0;

INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT f.id, 1, o.maintenance_cost_usd, 'USD', 1.0, o.maintenance_cost_usd
FROM flights_old o JOIN flights f
  ON f.flight_no = o.flight_no AND f.sched_dep = o.sched_dep
WHERE o.maintenance_cost_usd <> 0;

INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT f.id, 5, o.overhead_cost_usd, 'USD', 1.0, o.overhead_cost_usd
FROM flights_old o JOIN flights f
  ON f.flight_no = o.flight_no AND f.sched_dep = o.sched_dep
WHERE o.overhead_cost_usd <> 0;

INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT f.id, 6, o.nav_cost_usd, 'USD', 1.0, o.nav_cost_usd
FROM flights_old o JOIN flights f
  ON f.flight_no = o.flight_no AND f.sched_dep = o.sched_dep
WHERE o.nav_cost_usd <> 0;

INSERT INTO costs (flight_id, cost_type_id, amount, currency, rate_to_usd, amount_usd)
SELECT f.id, 7, o.airport_cost_usd, 'USD', 1.0, o.airport_cost_usd
FROM flights_old o JOIN flights f
  ON f.flight_no = o.flight_no AND f.sched_dep = o.sched_dep
WHERE o.airport_cost_usd <> 0;

DROP TABLE flights_old;

CREATE INDEX idx_flights_from ON flights(from_code);
CREATE INDEX idx_flights_sched_dep ON flights(sched_dep);
CREATE INDEX idx_costs_flight ON costs(flight_id);