package net.osmand.router;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
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

public class RoadCrewRoutePreferencesTest {
    private static final long NOW = 1787918400000L;
    private static final long DAY = 86400000L;

    @Test
    public void exactDirectionOnlyAndUnknownRoadsRemainUsable() {
        RouteDataObject road = road(100, 0, 0, 0, 1, 0, 2);
        RoadCrewRoutePreferences p = parse(document(road));
        Assert.assertEquals(1, p.size());
        Assert.assertEquals(1, p.newMatcher().costFactor(road, 0, 1), 0);
        Assert.assertEquals(1.05, p.newMatcher().costFactor(road, 1, 0), 0);
        Assert.assertEquals(1.05, p.newMatcher().costFactor(road(101, 0, 0, 0, 1), 0, 1), 0);
        Assert.assertEquals(1, RoadCrewRoutePreferences.EMPTY.newMatcher().costFactor(road, 0, 1), 0);
        Assert.assertTrue(p.within(1, 2, 3, 4).isEmpty());
    }

    @Test
    public void changedGeometryDoesNotInheritPreference() {
        RouteDataObject original = road(100, 0, 0, 0, 1, 0, 2);
        RoadCrewRoutePreferences.Matcher matcher = parse(document(original)).newMatcher();
        Assert.assertEquals(1, matcher.costFactor(original, 0, 1), 0);
        RouteDataObject changed = road(100, 0, 0, .2, 1, 0, 2);
        Assert.assertEquals(1.05, matcher.costFactor(changed, 0, 1), 0);
        original.pointsY = changed.pointsY;
        Assert.assertEquals(1.05, matcher.costFactor(original, 0, 1), 0);
    }

    @Test
    public void thirtyDaysAndVoteExpiryAreCheckedOnEveryLoad() {
        JsonObject d = document(road(100, 0, 0, 0, 1));
        JsonObject s = d.getAsJsonArray("segments").get(0).getAsJsonObject();
        s.addProperty("lastObservedBucket", NOW - 30 * DAY);
        Assert.assertFalse(parse(d).isEmpty());
        s.addProperty("lastObservedBucket", NOW - 30 * DAY - 1);
        Assert.assertTrue(parse(d).isEmpty());
        s.addProperty("lastObservedBucket", NOW);
        s.getAsJsonObject("routingPreference").addProperty("validUntil", NOW - 1);
        Assert.assertTrue(parse(d).isEmpty());
    }

    @Test
    public void missingStaleTruncatedAndUnvalidatedSnapshotsAreNeutral() {
        for (String key : new String[]{"routingPreferencePolicy", "segments", "truncated"}) {
            JsonObject d = document(road(100, 0, 0, 0, 1));
            d.remove(key);
            Assert.assertTrue(key, parse(d).isEmpty());
        }
        JsonObject d = document(road(100, 0, 0, 0, 1));
        d.addProperty("generatedAt", NOW - 31 * DAY);
        Assert.assertTrue(parse(d).isEmpty());
        d.addProperty("generatedAt", NOW + 1);
        Assert.assertTrue(parse(d).isEmpty());
        d.addProperty("generatedAt", NOW);
        d.addProperty("truncated", true);
        Assert.assertTrue(parse(d).isEmpty());
        d.addProperty("truncated", false);
        JsonObject s = d.getAsJsonArray("segments").get(0).getAsJsonObject();
        s.getAsJsonObject("routingPreference").addProperty("suitableObserverCount", 2);
        Assert.assertTrue(parse(d).isEmpty());
        s.getAsJsonObject("routingPreference").addProperty("suitableObserverCount", 3);
        s.getAsJsonObject("routingPreference").addProperty("problemObserverCount", 1);
        Assert.assertTrue(parse(d).isEmpty());
    }

    @Test
    public void completePreferenceCacheCanContainMoreThanOneServerPage() {
        JsonObject d = document(road(100, 0, 0, 0, 1));
        JsonArray segments = new JsonArray();
        for (int index = 0; index < 501; index++) {
            JsonObject item = document(road(100 + index, 0, 0, 0, 1))
                    .getAsJsonArray("segments").get(0).getAsJsonObject();
            segments.add(item);
        }
        d.add("segments", segments);
        Assert.assertEquals(501, parse(d).size());
    }

    @Test
    public void realAStarPrefersCloseAlternativeButNotLargeDetourOrForbiddenRoad() throws Exception {
        Assert.assertTrue(search(false, false, .7, false).contains(102L << 6));
        Assert.assertTrue(search(true, false, .7, false).contains(103L << 6));
        Assert.assertTrue(search(true, false, 3, false).contains(102L << 6));
        Assert.assertTrue(search(true, true, .7, false).contains(102L << 6));
        // Reverse search must not reuse the forward evidence for reverse vehicle travel.
        Assert.assertTrue(search(true, false, .7, true).contains(102L << 6));
    }

    @Test
    public void preferenceCannotOverrideTruckDimensionsOrWeight() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("height", "4");
        parameters.put("width", "2.55");
        parameters.put("weight", "40");
        parameters.put("length", "16.5");
        GeneralRouter router = RoutingConfiguration.getDefault().build("truck",
                new RoutingConfiguration.RoutingMemoryLimits(64, 256), parameters).router;
        String[][] restrictions = {{"maxheight", "3.5"}, {"maxwidth", "2"},
                {"maxweight", "12"}, {"maxlength", "12"}, {"hgv", "no"}};
        for (String[] restriction : restrictions) {
            RouteDataObject r = road(100, 0, 0, 0, 1);
            r.region.initRouteEncodingRule(2, restriction[0], restriction[1]);
            r.types = new int[]{1, 2};
            Assert.assertEquals(1, parse(document(r)).newMatcher().costFactor(r, 0, 1), 0);
            Assert.assertFalse(restriction[0], router.acceptLine(r));
        }
    }

    private Set<Long> search(boolean enabled, boolean forbidden, double detour, boolean backwards) throws Exception {
        RouteDataObject entry = road(101, 0, -1, 0, 0);
        RouteDataObject ordinary = road(102, 0, 0, 0, 5, 0, 10);
        RouteDataObject preferred = road(103, 0, 0, detour, 5, 0, 10);
        RouteDataObject exit = road(104, 0, 10, 0, 11);
        if (forbidden) {
            preferred.region.initRouteEncodingRule(2, "hgv", "no");
            preferred.types = new int[]{1, 2};
        }
        RoutingConfiguration cf = RoutingConfiguration.getDefault().build("truck",
                new RoutingConfiguration.RoutingMemoryLimits(64, 256), new HashMap<>());
        cf.roadCrewPreferences = enabled ? parse(document(preferred)) : RoadCrewRoutePreferences.EMPTY;
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
        RouteSegmentPoint start = new RouteSegmentPoint(backwards ? exit : entry, 0, 0);
        RouteSegmentPoint end = new RouteSegmentPoint(backwards ? entry : exit, 0, 0);
        FinalRouteSegment result = new BinaryRoutePlanner().searchRouteInternal(ctx, start, end, null);
        Assert.assertNotNull(result);
        Set<Long> ids = new HashSet<>();
        for (RouteSegment s = result; s != null; s = s.getParentRoute()) { ids.add(s.getRoad().id); }
        for (RouteSegment s = result.opposite; s != null; s = s.getParentRoute()) { ids.add(s.getRoad().id); }
        return ids;
    }

    static JsonObject document(RouteDataObject road) {
        RoadCrewSegmentIdentity.SegmentKey k = RoadCrewSegmentIdentity.create(road, 0, road.getPointsLength() - 1);
        JsonObject d = new JsonObject();
        d.addProperty("ok", true);
        d.addProperty("schemaVersion", 1);
        d.addProperty("truncated", false);
        d.addProperty("generatedAt", NOW);
        d.addProperty("routingPreferencePolicy", RoadCrewRoutePreferences.POLICY);
        d.addProperty("routingPreferenceValidUntil", NOW + 30 * DAY);
        JsonObject s = new JsonObject();
        s.addProperty("segmentId", RoadCrewShadowIndex.segmentId(k));
        s.addProperty("canonicalId", k.getCanonicalId());
        s.addProperty("osmWayId", k.getOsmWayId());
        s.addProperty("region", k.getRegion());
        s.addProperty("fromLatitude", k.getFromLatitude());
        s.addProperty("fromLongitude", k.getFromLongitude());
        s.addProperty("toLatitude", k.getToLatitude());
        s.addProperty("toLongitude", k.getToLongitude());
        s.addProperty("geometryFingerprint", k.getGeometryFingerprint());
        s.addProperty("lengthMeters", k.getLengthMeters());
        s.addProperty("shadowLevel", "MATURE_SHADOW");
        s.addProperty("confidence", .9);
        s.addProperty("distinctObserverCount", 5);
        s.addProperty("passageCount", 8);
        s.addProperty("activeDayCount", 3);
        s.addProperty("lastObservedBucket", NOW);
        JsonObject p = new JsonObject();
        p.addProperty("eligible", true);
        p.addProperty("suitableObserverCount", 3);
        p.addProperty("problemObserverCount", 0);
        p.addProperty("validUntil", NOW + 30 * DAY);
        s.add("routingPreference", p);
        JsonArray segments = new JsonArray();
        segments.add(s);
        d.add("segments", segments);
        return d;
    }

    private static RoadCrewRoutePreferences parse(JsonObject d) {
        return RoadCrewRoutePreferences.parse(new StringReader(d.toString()), NOW);
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
