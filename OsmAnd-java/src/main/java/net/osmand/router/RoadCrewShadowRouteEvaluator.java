package net.osmand.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only comparison of calculated routes against exact directed Shadow
 * evidence. Results preserve input order and cannot mutate routing costs.
 */
public final class RoadCrewShadowRouteEvaluator {

	private RoadCrewShadowRouteEvaluator() {
	}

	public static List<Result> evaluateAlternatives(
			List<? extends List<RouteSegmentResult>> alternatives, RoadCrewShadowIndex index) {
		if (alternatives == null || index == null) {
			throw new IllegalArgumentException("RoadCrew Shadow alternatives and index are required");
		}
		List<Result> results = new ArrayList<>(alternatives.size());
		for (int position = 0; position < alternatives.size(); position++) {
			results.add(evaluate(position, alternatives.get(position), index));
		}
		return Collections.unmodifiableList(results);
	}

	public static Result evaluate(List<RouteSegmentResult> route, RoadCrewShadowIndex index) {
		return evaluate(0, route, index);
	}

	private static Result evaluate(int position, List<RouteSegmentResult> route,
			RoadCrewShadowIndex index) {
		if (route == null || index == null) {
			throw new IllegalArgumentException("RoadCrew Shadow route and index are required");
		}
		Accumulator accumulator = new Accumulator(position, route.size());
		for (RouteSegmentResult segment : route) {
			accumulator.accept(segment, index);
		}
		return accumulator.result();
	}

	public static final class Result {
		private final int inputPosition;
		private final int routeSegmentCount;
		private final int identifiableSegmentCount;
		private final int evaluatedSegmentCount;
		private final int exactMatchCount;
		private final int collectingMatchCount;
		private final int candidateMatchCount;
		private final int matureMatchCount;
		private final double routeDistanceMeters;
		private final double evaluatedDistanceMeters;
		private final double exactMatchDistanceMeters;
		private final double matureMatchDistanceMeters;
		private final double confidenceDistanceMeters;

		private Result(Accumulator value) {
			inputPosition = value.inputPosition;
			routeSegmentCount = value.routeSegmentCount;
			identifiableSegmentCount = value.identifiableSegmentCount;
			evaluatedSegmentCount = value.evaluatedSegmentCount;
			exactMatchCount = value.exactMatchCount;
			collectingMatchCount = value.collectingMatchCount;
			candidateMatchCount = value.candidateMatchCount;
			matureMatchCount = value.matureMatchCount;
			routeDistanceMeters = value.routeDistanceMeters;
			evaluatedDistanceMeters = value.evaluatedDistanceMeters;
			exactMatchDistanceMeters = value.exactMatchDistanceMeters;
			matureMatchDistanceMeters = value.matureMatchDistanceMeters;
			confidenceDistanceMeters = value.confidenceDistanceMeters;
		}

		public int getInputPosition() {
			return inputPosition;
		}

		public int getRouteSegmentCount() {
			return routeSegmentCount;
		}

		public int getIdentifiableSegmentCount() {
			return identifiableSegmentCount;
		}

		public int getEvaluatedSegmentCount() {
			return evaluatedSegmentCount;
		}

		public int getExactMatchCount() {
			return exactMatchCount;
		}

		public int getCollectingMatchCount() {
			return collectingMatchCount;
		}

		public int getCandidateMatchCount() {
			return candidateMatchCount;
		}

		public int getMatureMatchCount() {
			return matureMatchCount;
		}

		public double getRouteDistanceMeters() {
			return routeDistanceMeters;
		}

		public double getEvaluatedDistanceMeters() {
			return evaluatedDistanceMeters;
		}

		public double getExactCoverage() {
			return ratio(exactMatchDistanceMeters, evaluatedDistanceMeters);
		}

		public double getMatureCoverage() {
			return ratio(matureMatchDistanceMeters, evaluatedDistanceMeters);
		}

		public double getConfidenceCoverage() {
			return ratio(confidenceDistanceMeters, evaluatedDistanceMeters);
		}

		private static double ratio(double numerator, double denominator) {
			return denominator > 0 ? Math.min(1, Math.max(0, numerator / denominator)) : 0;
		}
	}

	private static final class Accumulator {
		private final int inputPosition;
		private final int routeSegmentCount;
		private int identifiableSegmentCount;
		private int evaluatedSegmentCount;
		private int exactMatchCount;
		private int collectingMatchCount;
		private int candidateMatchCount;
		private int matureMatchCount;
		private double routeDistanceMeters;
		private double evaluatedDistanceMeters;
		private double exactMatchDistanceMeters;
		private double matureMatchDistanceMeters;
		private double confidenceDistanceMeters;

		private Accumulator(int inputPosition, int routeSegmentCount) {
			this.inputPosition = inputPosition;
			this.routeSegmentCount = routeSegmentCount;
		}

		private void accept(RouteSegmentResult segment, RoadCrewShadowIndex index) {
			double fallbackDistance = safeDistance(segment);
			try {
				if (segment == null || segment.getObject() == null) {
					routeDistanceMeters += fallbackDistance;
					return;
				}
				RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.create(
						segment.getObject(), segment.getStartPointIndex(), segment.getEndPointIndex());
				double distance = key.getLengthMeters();
				routeDistanceMeters += distance;
				identifiableSegmentCount++;
				if (!index.covers(key)) {
					return;
				}
				evaluatedSegmentCount++;
				evaluatedDistanceMeters += distance;
				RoadCrewShadowIndex.Entry entry = index.findExact(key);
				if (entry == null) {
					return;
				}
				exactMatchCount++;
				exactMatchDistanceMeters += distance;
				confidenceDistanceMeters += distance * entry.getConfidence();
				switch (entry.getLevel()) {
					case COLLECTING:
						collectingMatchCount++;
						break;
					case CANDIDATE:
						candidateMatchCount++;
						break;
					case MATURE_SHADOW:
						matureMatchCount++;
						matureMatchDistanceMeters += distance;
						break;
					default:
						throw new IllegalStateException("Unknown RoadCrew Shadow level");
				}
			} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
				routeDistanceMeters += fallbackDistance;
			}
		}

		private Result result() {
			return new Result(this);
		}

		private static double safeDistance(RouteSegmentResult segment) {
			if (segment == null) {
				return 0;
			}
			double distance = segment.getDistance();
			return Double.isFinite(distance) && distance > 0 ? distance : 0;
		}
	}
}
