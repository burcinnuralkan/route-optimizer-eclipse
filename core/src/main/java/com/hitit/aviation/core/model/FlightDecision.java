package com.hitit.aviation.core.model;

public enum FlightDecision {
FLY,
REVIEW,
CANCEL;

	public static FlightDecision of(double netContributionMarginUsd, double reviewBandUsd) {
		double band = Math.abs(reviewBandUsd);
		if(netContributionMarginUsd > band) return FLY;
		if(netContributionMarginUsd < -band) return CANCEL;
		return REVIEW;
	}
}