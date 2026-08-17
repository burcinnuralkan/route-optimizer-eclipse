package com.hitit.aviation.api.dto;
import java.util.List;

import com.hitit.aviation.core.model.*;

public record RouteResponse (
	RouteResult route,
	FlightDecision decision,
	List<FlightDecision> legDecision,
	double reviewBandUsd	
) {
	public static RouteResponse of(RouteResult r, double reviewBandUsd) {
		List<FlightDecision> legs = r.legs().stream().map((ScoredFlight leg)->leg.decision(reviewBandUsd)).toList();
		return new RouteResponse(r, r.decision(reviewBandUsd), legs, reviewBandUsd);
	}
}
