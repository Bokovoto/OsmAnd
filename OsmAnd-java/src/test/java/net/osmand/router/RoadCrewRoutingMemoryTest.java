package net.osmand.router;

import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.router.HHRouteDataStructure.HHRoutingConfig;
import net.osmand.router.HHRouteDataStructure.HHRoutingContext;
import net.osmand.router.HHRouteDataStructure.NetworkDBPoint;
import net.osmand.router.HHRouteDataStructure.NetworkDBSegment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

public class RoadCrewRoutingMemoryTest {
    @Test public void directionalReloadRestoresCostsWithoutLoadingTheOtherSide() {
        HHRoutingContext<NetworkDBPoint> ctx = context(1);
        NetworkDBPoint a = point(1), b = point(2), dual = point(3);
        a.clusterId = 10;
        dual.clusterId = 20;
        a.dualPoint = dual;
        ctx.clusterInPoints = new TIntObjectHashMap<>();
        ctx.clusterOutPoints = new TIntObjectHashMap<>();
        ctx.clusterInPoints.put(10, Arrays.asList(b));
        ctx.clusterOutPoints.put(20, Arrays.asList(b));
        HHRouteDataStructure.setSegments(ctx, a, new byte[]{50}, new byte[]{70}, false);
        assertNull(a.connected(true));
        assertEquals(7, a.connected(false).get(0).dist, 0);
        ctx.loadedEdges = 1;
        ctx.unloadExpandedSegments(a);
        ctx.loadedEdges = 2;
        ctx.unloadExpandedSegments(b);
        assertNull(a.connected(false));
        HHRouteDataStructure.setSegments(ctx, a, new byte[]{50}, new byte[]{70}, true);
        assertNull(a.connected(false));
        assertEquals(5, a.connected(true).get(0).dist, 0);
        HHRouteDataStructure.setSegments(ctx, a, new byte[]{50}, new byte[]{70}, false);
        assertEquals(7, a.connected(false).get(0).dist, 0);
        assertEquals(5, a.connected(true).get(0).dist, 0);
    }

    @Test public void appendedRulesAreReusedAndReplacementInvalidatesOldValue() {
        RouteRegion region = new RouteRegion();
        region.initRouteEncodingRule(1, "highway", "primary");
        assertEquals(1, region.searchRouteEncodingRule("highway", "primary"));
        for (int i = 2; i < 1002; i++) {
            region.initRouteEncodingRule(i, "maxweight", Integer.toString(i));
            assertEquals(i, region.searchRouteEncodingRule("maxweight", Integer.toString(i)));
        }
        region.initRouteEncodingRule(2, "maxweight", "replacement");
        assertEquals(-1, region.searchRouteEncodingRule("maxweight", "2"));
        assertEquals(2, region.searchRouteEncodingRule("maxweight", "replacement"));
        region.initRouteEncodingRule(1002, "surface", null);
        assertEquals(1002, region.searchRouteEncodingRule("surface", null));
        assertEquals(1003, region.quickGetEncodingRulesSize());
    }

    @Test public void budgetUnloadsOldEdgesButKeepsCurrentExpansion() {
        HHRoutingContext<NetworkDBPoint> ctx = context(1);
        NetworkDBPoint old = point(1), current = point(2);
        connect(old, current, 7);
        ctx.loadedEdges = 2;
        ctx.unloadExpandedSegments(old);
        assertNotNull(old.connected(false));
        ctx.unloadExpandedSegments(current);
        assertNull(old.connected(false));
        assertNull(old.connected(true));
        assertNotNull(current.connected(true));
        assertEquals(1, ctx.loadedEdges);
    }

    @Test public void editedCostPinsBothEndpointsAndSurvivesEviction() {
        HHRoutingContext<NetworkDBPoint> ctx = context(1);
        NetworkDBPoint start = point(1), end = point(2), other = point(3);
        NetworkDBSegment segment = connect(start, end, 7);
        segment.editDist(-1);
        ctx.loadedEdges = 2;
        ctx.unloadExpandedSegments(start);
        ctx.unloadExpandedSegments(end);
        ctx.unloadExpandedSegments(other);
        assertTrue(start.edgesEdited);
        assertTrue(end.edgesEdited);
        assertSame(segment, start.connected(false).get(0));
        assertEquals(-1, segment.dist, 0);
        assertNotNull(end.connected(true));
    }

    @Test public void zeroBudgetKeepsEdgesAndExplicitUnloadResetsAccounting() {
        HHRoutingContext<NetworkDBPoint> ctx = context(0);
        NetworkDBPoint a = point(1), b = point(2);
        connect(a, b, 7);
        ctx.pointsById.put(a.index, a);
        ctx.pointsById.put(b.index, b);
        ctx.loadedEdges = 2;
        ctx.unloadExpandedSegments(a);
        ctx.unloadExpandedSegments(b);
        assertNotNull(a.connected(false));
        assertNotNull(b.connected(true));
        ctx.unloadAllConnections();
        assertNull(a.connected(false));
        assertNull(b.connected(true));
        assertEquals(0, ctx.loadedEdges);
        assertTrue(ctx.expandedPoints.isEmpty());
    }

    private static HHRoutingContext<NetworkDBPoint> context(int budget) {
        HHRoutingContext<NetworkDBPoint> ctx = new HHRoutingContext<>();
        ctx.config = new HHRoutingConfig();
        ctx.config.MAX_LOADED_EDGES = budget;
        ctx.pointsById = new TLongObjectHashMap<>();
        return ctx;
    }

    private static NetworkDBPoint point(int id) {
        NetworkDBPoint p = new NetworkDBPoint();
        p.index = id;
        p.markSegmentsNotLoaded();
        return p;
    }

    private static NetworkDBSegment connect(NetworkDBPoint a, NetworkDBPoint b, double distance) {
        NetworkDBSegment edge = new NetworkDBSegment(a, b, distance, true, false);
        a.connectedSet(false, new ArrayList<>(Arrays.asList(edge)));
        a.connectedSet(true, new ArrayList<>());
        b.connectedSet(true, new ArrayList<>(Arrays.asList(
                new NetworkDBSegment(a, b, distance, false, false))));
        b.connectedSet(false, new ArrayList<>());
        return edge;
    }
}
