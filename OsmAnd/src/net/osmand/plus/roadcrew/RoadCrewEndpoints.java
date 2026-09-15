package net.osmand.plus.roadcrew;

/**
 * The one address of the RoadCrew API. Since Test 107 the phone talks to the
 * project's own server directly, not through Cloudflare; the old Cloudflare
 * address only forwards the requests of older versions until they are gone.
 */
final class RoadCrewEndpoints {
	static final String API_BASE_URL = "https://api.roadcrew.meriltrans.com";

	private RoadCrewEndpoints() {
	}
}
