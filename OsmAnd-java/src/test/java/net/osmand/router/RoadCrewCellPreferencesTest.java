package net.osmand.router;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.osm.MapRenderingTypes;
import net.osmand.router.BinaryRoutePlanner.FinalRouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegmentPoint;
import net.osmand.util.MapUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RoadCrewCellPreferencesTest {
    private static final long NOW = System.currentTimeMillis();
    private static final long DAY = 86_400_000L;

    @Test public void turnOnlyTileSurvivesAndExpiresWithoutInventingCellEvidence() {
        RoadCrewCellPreferences preferences = parse(document(road(101, 0, -1, 0, 0),
                road(103, 0, 0, 0, 1)), NOW);
        Assert.assertFalse(preferences.isEmpty());
        Assert.assertFalse(preferences.newMatcher(NOW).hasEvidence());
        Assert.assertTrue(preferences.fresh(NOW + DAY).isEmpty());
    }

    @Test public void realAStarUsesOnlyFreshLegalCloseTurnAlternatives() throws Exception {
        for (int searchDirection : new int[]{1, -1, 0}) {
            Assert.assertTrue(search(false, false, .3, false, false, searchDirection).contains(102L << 6));
            Assert.assertTrue("search direction " + searchDirection,
                    search(true, false, .3, false, false, searchDirection).contains(103L << 6));
            Assert.assertTrue(search(true, true, .3, false, false, searchDirection).contains(102L << 6));
            Assert.assertTrue(search(true, false, 3, false, false, searchDirection).contains(102L << 6));
            Assert.assertTrue(search(true, false, .3, true, false, searchDirection).contains(102L << 6));
            Assert.assertTrue(search(true, false, .3, false, true, searchDirection).contains(102L << 6));
        }
    }

    @Test public void provenTurnNeverOverridesAnExplicitTurnBan() throws Exception {
        for (int searchDirection : new int[]{1, -1, 0}) {
            Set<Long> route = search(true, false, .3, false, false, searchDirection, true);
            Assert.assertTrue("search direction " + searchDirection, route.contains(102L << 6));
            Assert.assertFalse(route.contains(103L << 6));
        }
    }

    @Test public void directionExpiryAndUnknownSourcesDoNotInventEvidence() {
        RouteDataObject entry = road(101, 0, -1, 0, 0);
        RouteDataObject proven = road(103, 0, 0, 0, 1);
        RouteDataObject other = road(102, 0, 0, 1, 1);
        RoadCrewCellPreferences preferences = parse(document(entry, proven), NOW);
        RoadCrewCellPreferences.Matcher matcher = preferences.newMatcher(NOW);
        Assert.assertEquals(1, matcher.turnCostFactor(entry, 0, 1, proven, 0, 1), 0);
        Assert.assertEquals(1.05, matcher.turnCostFactor(entry, 0, 1, other, 0, 1), 1e-9);
        Assert.assertEquals(1.05, matcher.turnCostFactor(entry, 0, 1, proven, 1, 0), 1e-9);
        Assert.assertEquals(1, matcher.turnCostFactor(entry, 1, 0, other, 0, 1), 0);
        Assert.assertEquals(1, matcher.turnCostFactor(other, 0, 1, proven, 0, 1), 0);
        Assert.assertEquals(1, matcher.turnCostFactor(entry, -1, 1, other, 0, 1), 0);
        Assert.assertEquals(1, matcher.turnCostFactor(entry, 0, 1, other, 0, 9), 0);
        Assert.assertEquals(1, preferences.newMatcher(NOW + DAY)
                .turnCostFactor(entry, 0, 1, other, 0, 1), 0);
        Assert.assertEquals(1, matcher.costFactor(other, 0, 1), 0);
    }

    // ROADMAP 318: one OSM way stored as two OBF objects. Going straight on it is
    // not a turn, so a proven exit elsewhere on the way must not make it dearer.
    @Test public void continuingAlongTheSameOsmWayIsNeverATurn() throws Exception {
        RouteDataObject entry = road(101, 0, -1, 0, 0);
        RouteDataObject proven = road(103, 0, 0, 0, 1);
        RouteDataObject continuation = road(101, 0, 0, -1, 1);
        continuation.id = (101L << 6) + 1;
        RoadCrewCellPreferences preferences = parse(document(entry, proven), NOW);
        RoadCrewCellPreferences.Matcher matcher = preferences.newMatcher(NOW);
        Assert.assertEquals(1, matcher.turnCostFactor(entry, 0, 1, continuation, 0, 1), 0);
        Assert.assertEquals(1, matcher.turnCostFactor(entry, 0, 1, continuation, 1, 0), 0);

        RoutingConfiguration cf = RoutingConfiguration.getDefault().build("truck",
                new RoutingConfiguration.RoutingMemoryLimits(64, 256), new HashMap<>());
        cf.roadCrewCellPreferences = preferences;
        RoutingContext ctx = new RoutingContext(cf, null, new BinaryMapIndexReader[0],
                RoutePlannerFrontEnd.RouteCalculationMode.NORMAL);
        Assert.assertEquals(0, new BinaryRoutePlanner().roadCrewTurnPenalty(ctx, false,
                new RouteSegment(entry, 0, 1), new RouteSegment(continuation, 0, 1)), 0);
    }

    @Test public void malformedAndInsufficientTurnsAreIgnored() {
        RouteDataObject entry = road(101, 0, -1, 0, 0);
        RouteDataObject proven = road(103, 0, 0, 0, 1);
        String[] properties = {"witnessCount", "expiresAt", "expiresAt", "toWayId", "toWayId", "toDirection"};
        String[] values = {"4", Long.toString(NOW),
                Long.toString(NOW + RoadCrewCellPreferences.MAX_TILE_AGE_MILLIS + 1), "bad", "101", "bad"};
        for (int i = 0; i < properties.length; i++) {
            JsonObject doc = document(entry, proven);
            JsonObject turn = doc.getAsJsonArray("roads").get(0).getAsJsonObject()
                    .getAsJsonArray("turns").get(0).getAsJsonObject();
            turn.addProperty(properties[i], values[i]);
            Assert.assertTrue(properties[i] + "=" + values[i], parse(doc, NOW).isEmpty());
        }
    }

    @Test public void cellRunsAndTurnsExpireIndependently() {
        JsonObject doc = document(road(101, 0, -1, 0, 0), road(103, 0, 0, 0, 1));
        JsonObject run = new JsonObject();
        run.addProperty("fromMeters", 0);
        run.addProperty("toMeters", 100);
        run.addProperty("expiresAt", NOW + DAY / 2);
        doc.getAsJsonArray("roads").get(0).getAsJsonObject().getAsJsonArray("runs").add(run);
        RoadCrewCellPreferences preferences = parse(doc, NOW);
        Assert.assertEquals(2, preferences.size());
        Assert.assertTrue(preferences.newMatcher(NOW).hasEvidence());
        RoadCrewCellPreferences fresh = preferences.fresh(NOW + DAY / 2);
        Assert.assertEquals(1, fresh.size());
        Assert.assertFalse(fresh.newMatcher(NOW + DAY / 2).hasEvidence());
        Assert.assertTrue(fresh.fresh(NOW + DAY).isEmpty());
    }

    @Test public void reverseSearchChargesTheSamePhysicalOutgoingEdge() throws Exception {
        RouteDataObject entry = road(101, 0, -1, 0, 0);
        RouteDataObject proven = road(103, 0, 0, 0, 1);
        RouteDataObject other = road(102, 0, 0, 1, 1);
        other.region.initRouteEncodingRule(2, "maxspeed:forward", "20");
        other.region.initRouteEncodingRule(3, "maxspeed:backward", "60");
        other.types = new int[]{1, 2, 3};
        RoutingConfiguration cf = RoutingConfiguration.getDefault().build("truck",
                new RoutingConfiguration.RoutingMemoryLimits(64, 256), new HashMap<>());
        cf.roadCrewCellPreferences = parse(document(entry, proven), NOW);
        RoutingContext ctx = new RoutingContext(cf, null, new BinaryMapIndexReader[0],
                RoutePlannerFrontEnd.RouteCalculationMode.NORMAL);
        BinaryRoutePlanner planner = new BinaryRoutePlanner();
        RouteSegment outgoing = new RouteSegment(other, 0, 1);
        float forward = planner.roadCrewTurnPenalty(ctx, false, new RouteSegment(entry, 0, 1), outgoing);
        float reverse = planner.roadCrewTurnPenalty(ctx, true, new RouteSegment(other, 1, 0),
                new RouteSegment(entry, 1, 0));
        Assert.assertTrue(forward > 0);
        Assert.assertEquals(forward, reverse, 1e-6);
        Assert.assertEquals(planner.calcRoutingSegmentTimeOnlyDist(ctx.getRouter(), outgoing) * .05,
                forward, 1e-6);
        Assert.assertEquals(0, planner.roadCrewTurnPenalty(ctx, false,
                new RouteSegment(entry, 0, 1), new RouteSegment(proven, 0, 1)), 0);
    }

    private Set<Long> search(boolean enabled, boolean forbidden, double detour,
                             boolean backwards, boolean expired, int searchDirection) throws Exception {
        return search(enabled, forbidden, detour, backwards, expired, searchDirection, false);
    }

    private Set<Long> search(boolean enabled, boolean forbidden, double detour,
                             boolean backwards, boolean expired, int searchDirection, boolean turnBan) throws Exception {
        RouteDataObject entry = road(101, 0, -1, 0, 0);
        RouteDataObject ordinary = road(102, 0, 0, 0, 5, 0, 10);
        RouteDataObject preferred = road(103, 0, 0, detour, 5, 0, 10);
        RouteDataObject exit = road(104, 0, 10, 0, 11);
        if (turnBan) { entry.setRestriction(0, preferred.id, MapRenderingTypes.RESTRICTION_NO_RIGHT_TURN, 0); }
        if (forbidden) {
            preferred.region.initRouteEncodingRule(2, "hgv", "no");
            preferred.types = new int[]{1, 2};
        }
        RoutingConfiguration cf = RoutingConfiguration.getDefault().build("truck",
                new RoutingConfiguration.RoutingMemoryLimits(64, 256), new HashMap<>());
        cf.planRoadDirection = searchDirection;
        RoadCrewCellPreferences preferences = parse(document(entry, preferred), NOW);
        cf.roadCrewCellPreferences = !enabled ? RoadCrewCellPreferences.EMPTY
                : expired ? preferences.fresh(NOW + DAY) : preferences;
        RoutingContext ctx = new RoutingContext(cf, null, new BinaryMapIndexReader[0],
                RoutePlannerFrontEnd.RouteCalculationMode.NORMAL) {
            final Map<String, RouteSegment> loaded = new HashMap<>();
            @Override public RouteSegment loadRouteSegment(int x, int y, long memory, boolean reverse) {
                String key = x + ":" + y + ":" + reverse;
                if (!loaded.containsKey(key)) {
                    RouteSegment head = null;
                    for (RouteDataObject r : new RouteDataObject[]{entry, ordinary, preferred, exit}) {
                        if (!config.router.acceptLine(r)) { continue; }
                        for (int i = 0; i < r.getPointsLength(); i++) {
                            if (r.pointsX[i] == x && r.pointsY[i] == y) {
                                RouteSegment next = new RouteSegment(r, i);
                                next.next = head;
                                head = next;
                            }
                        }
                    }
                    loaded.put(key, head);
                }
                return loaded.get(key);
            }
        };
        ctx.calculationProgress = new RouteCalculationProgress();
        FinalRouteSegment result = new BinaryRoutePlanner().searchRouteInternal(ctx,
                new RouteSegmentPoint(backwards ? exit : entry, 0, 0),
                new RouteSegmentPoint(backwards ? entry : exit, 0, 0), null);
        Assert.assertNotNull(result);
        Set<Long> ids = new HashSet<>();
        for (RouteSegment s = result; s != null; s = s.getParentRoute()) { ids.add(s.getRoad().id); }
        for (RouteSegment s = result.opposite; s != null; s = s.getParentRoute()) { ids.add(s.getRoad().id); }
        return ids;
    }

    private static JsonObject document(RouteDataObject from, RouteDataObject to) {
        JsonObject doc = new JsonObject();
        doc.addProperty("ok", true);
        doc.addProperty("schemaVersion", 2);
        doc.addProperty("generatedAt", NOW);
        doc.addProperty("evidenceModel", RoadCrewCellPreferences.EVIDENCE_MODEL);
        doc.addProperty("routingEffect", RoadCrewCellPreferences.ROUTING_EFFECT);
        doc.addProperty("routingPreferencePolicy", RoadCrewRoutePreferences.POLICY);
        JsonObject road = new JsonObject();
        road.addProperty("osmWayId", Long.toString(from.id >> 6));
        road.addProperty("direction", direction(from));
        road.add("runs", new JsonArray());
        JsonObject turn = new JsonObject();
        turn.addProperty("toWayId", Long.toString(to.id >> 6));
        turn.addProperty("toDirection", direction(to));
        turn.addProperty("witnessCount", 5);
        turn.addProperty("expiresAt", NOW + DAY);
        JsonArray turns = new JsonArray();
        turns.add(turn);
        road.add("turns", turns);
        JsonArray roads = new JsonArray();
        roads.add(road);
        doc.add("roads", roads);
        return doc;
    }

    private static String direction(RouteDataObject road) {
        return RoadCrewWayCanonical.canonicalise(road.pointsX, road.pointsY).reversed ? "R" : "F";
    }

    private static RoadCrewCellPreferences parse(JsonObject doc, long now) {
        return RoadCrewCellPreferences.parse(new StringReader(doc.toString()), now);
    }

    private static RouteDataObject road(long id, double... points) {
        RouteRegion region = new RouteRegion();
        region.setName("Bulgaria");
        region.initRouteEncodingRule(1, "highway", "primary");
        RouteDataObject road = new RouteDataObject(region);
        road.id = id << 6;
        road.types = new int[]{1};
        road.pointsX = new int[points.length / 2];
        road.pointsY = new int[points.length / 2];
        for (int i = 0; i < road.pointsX.length; i++) {
            road.pointsX[i] = MapUtils.get31TileNumberX(27 + points[2 * i + 1] * .001);
            road.pointsY[i] = MapUtils.get31TileNumberY(43 + points[2 * i] * .001);
        }
        return road;
    }
}
