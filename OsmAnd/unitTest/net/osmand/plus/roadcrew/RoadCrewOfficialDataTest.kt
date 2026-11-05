package net.osmand.plus.roadcrew

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoadCrewOfficialDataTest {
    private val now = 1793820000000L
    private val version = "a".repeat(64)
    private fun manifest() = """{"schemaVersion":1,"kind":"official","version":"$version","generatedAt":$now,"validUntil":${now + 900000},"tiles":["0/0/0","8/146/94","8/147/93","8/0/100","8/255/100"]}"""
    private fun tile() = """{"schemaVersion":1,"type":"FeatureCollection","kind":"official","version":"$version","features":[{"id":"ria:test:0","geometry":{"type":"LineString","coordinates":[[25,42],[25.1,42.1]]},"properties":{"officialId":"ria:test:0","kind":"OFFICIAL_RESTRICTION","name":"I-5","expiresAt":${now+3600000},"official":{"source":"BG_RIA","routingEligible":false,"publicationTime":$now,"staleAt":${now+1800000},"stale":false,"descriptions":["Original description"],"conditions":[{"weight":12}]}}}]}"""
    private fun rejected(block: () -> Unit) { try { block() } catch (_: Exception) { return }; fail("Invalid input accepted") }

    @Test fun geometryAndSourceRetained() {
        val f = RoadCrewOfficialData.tile(tile(), version).single()
        assertEquals(42.0, f.points[0].lat, 0.0); assertEquals(25.0, f.points[0].lon, 0.0)
        assertEquals("Original description", f.descriptions.single()); assertTrue(f.conditions.contains("12"))
        assertFalse(f.detour); assertTrue(f.active(now)); assertFalse(f.stale(now))
    }
    @Test fun expiryAndStalenessAdvanceOffline() {
        val f = RoadCrewOfficialData.tile(tile(), version).single()
        assertFalse(f.active(now + 3600000)); assertTrue(f.stale(now + 1800000)); assertTrue(f.stale(now - 1))
    }
    @Test fun malformedPointRejectsEntireTileInsteadOfJoiningGap() {
        rejected { RoadCrewOfficialData.tile(tile().replace("[25.1,42.1]", "[25.1,92]"), version) }
        rejected { RoadCrewOfficialData.tile(tile().replace("[25.1,42.1]", "[null,42.1]"), version) }
        rejected { RoadCrewOfficialData.tile(tile().replace("[25.1,42.1]", "[\"25.1\",42.1]"), version) }
    }
    @Test fun wrongVersionOriginAndRoutingAuthorityRejected() {
        rejected { RoadCrewOfficialData.tile(tile(), "b".repeat(64)) }
        rejected { RoadCrewOfficialData.tile(tile().replace("BG_RIA", "other"), version) }
        rejected { RoadCrewOfficialData.tile(tile().replace("\"routingEligible\":false", "\"routingEligible\":true"), version) }
    }
    @Test fun duplicateIdsRejected() {
        val o = JSONObject(tile()); o.getJSONArray("features").put(o.getJSONArray("features").get(0))
        rejected { RoadCrewOfficialData.tile(o.toString(), version) }
    }
    @Test fun detoursAreNotConvertedIntoRestrictions() {
        assertTrue(RoadCrewOfficialData.tile(tile().replace("OFFICIAL_RESTRICTION", "OFFICIAL_DETOUR"), version).single().detour)
    }
    @Test fun manifestPathsAndTimesValidated() {
        rejected { RoadCrewOfficialData.manifest(manifest().replace("8/146/94", "../private"), now) }
        rejected { RoadCrewOfficialData.manifest(manifest().replace("8/146/94", "8/999/94"), now) }
        rejected { RoadCrewOfficialData.manifest(manifest(), now + 900000) }
        assertEquals(version, RoadCrewOfficialData.manifest(manifest(), now + 900000, cached = true).version)
        rejected { RoadCrewOfficialData.manifest(manifest(), now - 600000) }
    }
    @Test fun viewportSelectsOnlyAvailableDetailedTilesAndHandlesDateLine() {
        val m = RoadCrewOfficialData.manifest(manifest(), now)
        assertEquals(listOf("8/146/94"), RoadCrewOfficialData.tilesFor(m, 25.4, 42.0, 25.5, 42.1))
        assertEquals(emptyList<String>(), RoadCrewOfficialData.tilesFor(m, 2.0, 48.0, 3.0, 49.0))
        assertEquals(listOf("8/0/100", "8/255/100"), RoadCrewOfficialData.tilesFor(m, 179.0, 35.0, -179.0, 38.0))
    }
}
