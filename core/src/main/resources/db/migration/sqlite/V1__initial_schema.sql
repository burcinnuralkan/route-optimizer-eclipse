CREATE TABLE airports (
    code    TEXT PRIMARY KEY,
    name    TEXT NOT NULL,
    city    TEXT NOT NULL,
    country TEXT NOT NULL,
    lat     REAL NOT NULL,
    lon     REAL NOT NULL
);

CREATE TABLE flights (
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
    crew_cost_usd         REAL    NOT NULL,
    ownership_cost_usd    REAL    NOT NULL,
    maintenance_cost_usd  REAL    NOT NULL,
    overhead_cost_usd     REAL    NOT NULL,
    nav_cost_usd          REAL    NOT NULL,
    airport_cost_usd      REAL    NOT NULL,
    fuel_kg               REAL    NOT NULL,
    distance_km           REAL    NOT NULL,
    PRIMARY KEY (flight_no, sched_dep)
);

CREATE INDEX idx_flights_from ON flights(from_code);
CREATE INDEX idx_flights_sched_dep ON flights(sched_dep);