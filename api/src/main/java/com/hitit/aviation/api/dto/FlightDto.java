package com.hitit.aviation.api.dto;

import java.time.LocalDateTime;

import com.hitit.aviation.core.model.Flight;

/**
 * Uçuşun API gösterimi — tarifedeki HAM girdi (gelir/gider kalemleri dahil).
 *
 * <p>Hesaplanmış büyüklükler için {@link FlightEvaluationDto}'ya bakın; buradaki
 * {@code paxRevenueUsd} gibi alanlar CSV'den okunan girdilerdir.
 */
public record FlightDto(
        String flightNo,
        String airlineCode,
        String tailNumber,
        String aircraftType,
        AirportDto from,
        AirportDto to,
        LocalDateTime schedDep,
        LocalDateTime schedArr,
        LocalDateTime actualDep,
        LocalDateTime actualArr,
        int econSeats,
        int busSeats,
        double econPax,
        double busPax,
        double cargoKg,
        double cargoCapacityKg,
        double paxRevenueUsd,
        double ancillaryRevenueUsd,
        double cargoRevenueUsd,
        double crewCostUsd,
        double ownershipCostUsd,
        double maintenanceCostUsd,
        double overheadCostUsd,
        double navCostUsd,
        double airportCostUsd,
        double fuelKg,
        double distanceKm,
        double mtow) {

    public static FlightDto of(Flight f) {
        return new FlightDto(
                f.flightNo(), f.airlineCode(), f.tailNumber(), f.aircraftType(),
                AirportDto.of(f.from()), AirportDto.of(f.to()),
                f.schedDep(), f.schedArr(), f.actualDep(), f.actualArr(),
                f.econSeats(), f.busSeats(), f.econPax(), f.busPax(),
                f.cargoKg(), f.cargoCapacityKg(),
                f.paxRevenueUsd(), f.ancillaryRevenueUsd(), f.cargoRevenueUsd(),
                f.crewCostUsd(), f.ownershipCostUsd(), f.maintenanceCostUsd(),
                f.overheadCostUsd(), f.navCostUsd(), f.airportCostUsd(),
                f.fuelKg(), f.distanceKm(), f.mtow());
    }
}
 