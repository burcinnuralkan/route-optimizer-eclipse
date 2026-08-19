package com.hitit.aviation.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.hitit.aviation.core.model.Flight;

public class AircraftDao {

    public record Seating(
            int econSeats,
            int busSeats,
            double cargoCapacityKg) {

        boolean sameAs(Seating other) {
            return econSeats == other.econSeats
                    && busSeats == other.busSeats
                    && Math.abs(cargoCapacityKg - other.cargoCapacityKg) < 0.005;
        }
    }

    public record State(
            String aircraftType,
            Seating seating) {

        boolean sameAs(State other) {
            return aircraftType.equals(other.aircraftType)
                    && seating.sameAs(other.seating);
        }

		public double mtow() {
			// TODO Auto-generated method stub
			return 0;
		}
    }

    static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

    private static final String COLUMNS =
            """
            tail_number,
            valid_from,
            aircraft_type,
            econ_seats,
            bus_seats,
            cargo_capacity_kg,
            mtow
            """;

    private final Database db;
    private final String upsert;

    public AircraftDao(Database db) {
        this.db = db;
        this.upsert = Sql.upsert(
                "aircraft",
                COLUMNS,
                "tail_number",
                "valid_from");
    }

    public Map<String, NavigableMap<LocalDate, State>> findAll()
            throws SQLException {

        try (Connection c = db.open()) {
            return findAll(c);
        }
    }

    Map<String, NavigableMap<LocalDate, State>> findAll(Connection c)
            throws SQLException {

        Map<String, NavigableMap<LocalDate, State>> result =
                new LinkedHashMap<>();

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS
                        + " FROM aircraft "
                        + "ORDER BY tail_number, valid_from");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {

                String tail = rs.getString("tail_number");

                LocalDate validFrom =
                        LocalDate.parse(rs.getString("valid_from"));

                State state = new State(
                        rs.getString("aircraft_type"),
                        new Seating(
                                rs.getInt("econ_seats"),
                                rs.getInt("bus_seats"),
                                rs.getDouble("cargo_capacity_kg")));

                result.computeIfAbsent(
                        tail,
                        k -> new TreeMap<>())
                      .put(validFrom, state);
            }
        }

        return result;
    }

    public Optional<State> find(
            String tailNumber,
            LocalDate date)
            throws SQLException {

        try (Connection c = db.open()) {

            NavigableMap<LocalDate, State> history =
                    findAll(c).get(tailNumber);

            if (history == null) {
                return Optional.empty();
            }

            Map.Entry<LocalDate, State> entry =
                    history.floorEntry(date);

            return entry == null
                    ? Optional.empty()
                    : Optional.of(entry.getValue());
        }
    }

    void syncAll(
            Connection c,
            Collection<Flight> flights)
            throws SQLException {

        for (Flight f : flights) {

            saveState(
                    c,
                    f.tailNumber(),
                    EPOCH,
                    new State(
                            f.aircraftType(),
                            new Seating(
                                    f.econSeats(),
                                    f.busSeats(),
                                    f.cargoCapacityKg())));
        }
    }

    private void saveState(
            Connection c,
            String tailNumber,
            LocalDate validFrom,
            State state)
            throws SQLException {

        try (PreparedStatement ps =
                     c.prepareStatement(upsert)) {

            ps.setString(1, tailNumber);
            ps.setString(2, validFrom.toString());
            ps.setString(3, state.aircraftType());
            ps.setInt(4, state.seating().econSeats());
            ps.setInt(5, state.seating().busSeats());
            ps.setDouble(6, state.seating().cargoCapacityKg());
            ps.setDouble(7,state.mtow());

            ps.executeUpdate();
        }
    }

    public int count() throws SQLException {

        try (Connection c = db.open();
             PreparedStatement ps =
                     c.prepareStatement(
                             "SELECT COUNT(*) FROM aircraft");
             ResultSet rs = ps.executeQuery()) {

            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}