package net.osmand.plus.roadcrew;

/** Latest opening and its lookup result, confined to the UI thread; no UI owner is retained. */
final class RoadCrewPendingOpen<T> {

	enum Outcome { FOUND, CLOSED, NOT_FOUND, ERROR }

	static final class Request<T> {
		final String kind;
		final String referenceId;
		private boolean loading;
		Outcome outcome;
		T value;

		private Request(String kind, String referenceId) {
			this.kind = kind;
			this.referenceId = referenceId;
		}
	}

	private Request<T> current;

	Request<T> accept(String kind, String referenceId) {
		current = new Request<>(kind, referenceId);
		return current;
	}

	Request<T> current() {
		return current;
	}

	boolean beginLookup(Request<T> request) {
		if (request != current || request.loading || request.outcome != null) {
			return false;
		}
		request.loading = true;
		return true;
	}

	boolean completeLookup(Request<T> request, Outcome outcome, T value) {
		if (request != current || !request.loading || request.outcome != null) {
			return false;
		}
		request.outcome = outcome;
		request.value = value;
		return true;
	}

	boolean consume(Request<T> request) {
		if (request != current) {
			return false;
		}
		current = null;
		return true;
	}
}
