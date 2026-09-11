package net.osmand.plus.roadcrew;

/**
 * What the server did with the author's "still need help" (ROADMAP 238, S2).
 *
 * The answer is a compare-and-set on a clock generation, so it has outcomes, not
 * just success: stopped, already active, too late for a newer clock, request
 * closed, request gone. And one the phone must be honest about - UNCONFIRMED,
 * when no reply came back and the server may or may not have applied it.
 */
public enum HelpAnswerOutcome {
	APPLIED, ALREADY_ACTIVE, STALE, NO_LONGER_ACTIVE, NOT_FOUND, UNCONFIRMED;

	/** From the HTTP status and the server's {@code result}; anything unrecognised is UNCONFIRMED. */
	public static HelpAnswerOutcome from(int httpCode, String result) {
		String value = result == null ? "" : result.trim();
		if (httpCode == 200 && "applied".equals(value)) {
			return APPLIED;
		}
		if (httpCode == 200 && "already_active".equals(value)) {
			return ALREADY_ACTIVE;
		}
		if (httpCode == 409 && "stale".equals(value)) {
			return STALE;
		}
		if (httpCode == 409 && "no_longer_active".equals(value)) {
			return NO_LONGER_ACTIVE;
		}
		if (httpCode == 404) {
			return NOT_FOUND;
		}
		return UNCONFIRMED;
	}

	/** The request is active on the server after this answer. */
	public boolean requestIsActive() {
		return this == APPLIED || this == ALREADY_ACTIVE;
	}

	/** The request is gone from the server's active set; the local copy should go too. */
	public boolean requestIsClosed() {
		return this == NO_LONGER_ACTIVE || this == NOT_FOUND;
	}
}
