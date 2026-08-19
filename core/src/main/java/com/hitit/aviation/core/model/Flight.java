package com.hitit.aviation.core.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.OptionalLong;

/** Zaman int dakika , LocalTime değil.
 * Gece yarısı geçişini (arrMin>1440) basit tamsayı aritmetiğiyle yönetmek için kullandım
 *  LocalTime'ın sıfırlama sorunu vardı
**/
public record Flight(
        String flightNo,
        String airlineCode,
        String tailNumber,
        String aircraftType,
        Airport from,
        Airport to,
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
        double mtow
) {
	private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM HH.mm");
	
	public Flight{
		Objects.requireNonNull(flightNo, "flightno");
		Objects.requireNonNull(airlineCode, "airlineCode");
		if(airlineCode.isBlank()) {
			throw new IllegalArgumentException(flightNo + "airlinecode boş olamaz");
		}
		Objects.requireNonNull(tailNumber, "tailnumber");
		Objects.requireNonNull(aircraftType, "aircraft");
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(schedDep, "scheddep");
		Objects.requireNonNull(schedArr, "schedarr");
		if(!schedArr.isAfter(schedDep)) {
			throw new IllegalArgumentException(flightNo + "varış kalkış sonrası olmalı");
		}
		if((actualDep==null) != (actualArr==null)) {
			throw new IllegalArgumentException(flightNo + "birlikte verilmeli ya dolu ya boş");
		}
		if(actualArr!=null && !actualArr.isAfter(actualDep)) {
			throw new IllegalArgumentException(flightNo + "ata (varış) atd sonrası olmalı");
		}
		if(econSeats < 0 || busSeats < 0 || econSeats + busSeats <= 0){
			throw new IllegalArgumentException(flightNo + "koltuk sayısı geçersiz");
		}
		if(econPax < 0 || econPax > econSeats) {
			throw new IllegalArgumentException(flightNo + "ekonomi yolcu 0 ile koltuk sayısı arasında olmalı");
		}
		if(busPax < 0 || busPax > busSeats) {
			throw new IllegalArgumentException(flightNo + "bus yolcu 0 ile koltuk sayısı arasında olmalı");
		}
		if(cargoKg < 0 || fuelKg < 0) {
			throw new IllegalArgumentException(flightNo + "kargo ve yakıt negatif olamaz");
		}
		if(cargoCapacityKg < 0 || cargoKg > cargoCapacityKg) {
			throw new IllegalArgumentException(flightNo + "taşınan kargo 0 ile kargo kapasitesi arasında olmalı");
		}
	}
	public int seats() { return econSeats + busSeats;}
	public double totalPax() {return econPax + busPax;}
	public double cargoLoadFactor() {return cargoCapacityKg > 0 ? cargoKg/cargoCapacityKg:0;}
	public double avgFareUsd() {
		double pax = totalPax();
		return pax > 0 ? paxRevenueUsd/pax : 0;
	}
	public LocalDate flightDate() { return schedDep.toLocalDate();}
	
    public long blockMinutes() {
        return Duration.between(schedDep, schedArr).toMinutes();
    }
    public boolean hasActuals() {return actualDep != null && actualArr != null;}

    public double blockHours() {
        return blockMinutes() / 60.0;
    }
    
    public OptionalLong actualBlockMinutes() {return hasActuals() ? OptionalLong.of(Duration.between(actualDep, actualArr).toMinutes()) : OptionalLong.empty();}
    public OptionalLong depDelayMinutes() {return actualDep !=null ? OptionalLong.of(Duration.between(schedDep, actualDep).toMinutes()) : OptionalLong.empty();}
    public OptionalLong arrDelayMinutes() { return actualArr != null ? OptionalLong.of(Duration.between(schedArr, actualArr).toMinutes()) : OptionalLong.empty();}

    public String timeString() {
        return schedDep.format(T) + "-" + schedArr.format(T);}
    public String scheduledString() {return schedDep.format(DT) + " -> " + schedArr.format(T);}
    public String actualString() {return hasActuals() ? actualDep.format(DT) + " -> " + actualArr.format(T): "-";}
   

    	/**
    	 * Elle uçuş eklerken kullanılan kısa yol: ticari alanlar tipik değerlerle
    	 * doldurulur, mesafe koordinatlardan hesaplanır, yakıt 0 bırakılır ki
    	 * EmissionCalculator parametrik tahmini devreye girsin.
    	 */
    	public static Flight of(
    	        String flightNo,
    	        Airport from,
    	        Airport to,
    	        LocalDateTime dep,
    	        LocalDateTime arr) {

    	    return builder()
    	            .flightNo(flightNo)
    	            .airlineCode("TK")
    	            .tailNumber("UNKNOWN")
    	            .aircraftType("A320")
    	            .from(from)
    	            .to(to)
    	            .schedDep(dep)
    	            .schedArr(arr)
    	            .econSeats(180)
    	            .busSeats(12)
    	            .econPax(150)
    	            .busPax(8)
    	            .cargoKg(2000)
    	            .cargoCapacityKg(5000)
    	            .paxRevenueUsd(25000)
    	            .ancillaryRevenueUsd(3000)
    	            .cargoRevenueUsd(2000)
    	            .crewCostUsd(1500)
    	            .ownershipCostUsd(1000)
    	            .maintenanceCostUsd(1200)
    	            .overheadCostUsd(800)
    	            .navCostUsd(700)
    	            .airportCostUsd(600)
    	            .fuelKg(0)                       // parametrik tahmin edilecek
    	            .distanceKm(from.distanceTo(to))
    	            .mtow(0)
    	            .build();
    	}

    	public static Builder builder() {
    	    return new Builder();
    	}

    	/**
    	 * 27 alanı sırayla dizmek yerine adıyla doldurmak için. Kurucudaki tüm
    	 * doğrulamalar {@link #build()} sırasında aynen çalışır; verilmeyen sayısal
    	 * alanlar 0, gerçekleşen (actual) zamanlar null kalır.
    	 *
    	 * <p>Kayıt (record) bileşen adlarındaki yazım hatalarını da gizler:
    	 * burada {@code ownershipCostUsd} / {@code maintenanceCostUsd} kullanılır.
    	 */
    	public static final class Builder {

    	    private String flightNo;
    	    private String airlineCode;
    	    private String tailNumber = "UNKNOWN";
    	    private String aircraftType = "UNKNOWN";
    	    private Airport from;
    	    private Airport to;
    	    private LocalDateTime schedDep;
    	    private LocalDateTime schedArr;
    	    private LocalDateTime actualDep;
    	    private LocalDateTime actualArr;
    	    private int econSeats;
    	    private int busSeats;
    	    private double econPax;
    	    private double busPax;
    	    private double cargoKg;
    	    private double cargoCapacityKg;
    	    private double paxRevenueUsd;
    	    private double ancillaryRevenueUsd;
    	    private double cargoRevenueUsd;
    	    private double crewCostUsd;
    	    private double ownershipCostUsd;
    	    private double maintenanceCostUsd;
    	    private double overheadCostUsd;
    	    private double navCostUsd;
    	    private double airportCostUsd;
    	    private double fuelKg;
    	    private double distanceKm;
    	    private double mtow;

    	    private Builder() { }

    	    public Builder flightNo(String v)            { this.flightNo = v; return this; }
    	    public Builder airlineCode(String v)         { this.airlineCode = v; return this; }
    	    public Builder tailNumber(String v)          { this.tailNumber = v; return this; }
    	    public Builder aircraftType(String v)        { this.aircraftType = v; return this; }
    	    public Builder from(Airport v)               { this.from = v; return this; }
    	    public Builder to(Airport v)                 { this.to = v; return this; }
    	    public Builder schedDep(LocalDateTime v)     { this.schedDep = v; return this; }
    	    public Builder schedArr(LocalDateTime v)     { this.schedArr = v; return this; }
    	    public Builder actualDep(LocalDateTime v)    { this.actualDep = v; return this; }
    	    public Builder actualArr(LocalDateTime v)    { this.actualArr = v; return this; }
    	    public Builder econSeats(int v)              { this.econSeats = v; return this; }
    	    public Builder busSeats(int v)               { this.busSeats = v; return this; }
    	    public Builder econPax(double v)             { this.econPax = v; return this; }
    	    public Builder busPax(double v)              { this.busPax = v; return this; }
    	    public Builder cargoKg(double v)             { this.cargoKg = v; return this; }
    	    public Builder cargoCapacityKg(double v)     { this.cargoCapacityKg = v; return this; }
    	    public Builder paxRevenueUsd(double v)       { this.paxRevenueUsd = v; return this; }
    	    public Builder ancillaryRevenueUsd(double v) { this.ancillaryRevenueUsd = v; return this; }
    	    public Builder cargoRevenueUsd(double v)     { this.cargoRevenueUsd = v; return this; }
    	    public Builder crewCostUsd(double v)         { this.crewCostUsd = v; return this; }
    	    public Builder ownershipCostUsd(double v)    { this.ownershipCostUsd = v; return this; }
    	    public Builder maintenanceCostUsd(double v)  { this.maintenanceCostUsd = v; return this; }
    	    public Builder overheadCostUsd(double v)     { this.overheadCostUsd = v; return this; }
    	    public Builder navCostUsd(double v)          { this.navCostUsd = v; return this; }
    	    public Builder airportCostUsd(double v)      { this.airportCostUsd = v; return this; }
    	    public Builder fuelKg(double v)              { this.fuelKg = v; return this; }
    	    public Builder distanceKm(double v)          { this.distanceKm = v; return this; }
    	    public Builder mtow(double v)				 { this.mtow = v; return this;}

    	    public Flight build() {
    	        return new Flight(
    	                flightNo, airlineCode, tailNumber, aircraftType,
    	                from, to,
    	                schedDep, schedArr, actualDep, actualArr,
    	                econSeats, busSeats, econPax, busPax,
    	                cargoKg, cargoCapacityKg,
    	                paxRevenueUsd, ancillaryRevenueUsd, cargoRevenueUsd,
    	                crewCostUsd, ownershipCostUsd, maintenanceCostUsd,
    	                overheadCostUsd, navCostUsd, airportCostUsd,
    	                fuelKg, distanceKm, mtow);
    	    }
    	}
    	 
}