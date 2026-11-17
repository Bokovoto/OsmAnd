package net.osmand.binary;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class RoadCrewRouteEncodingCacheTest {
    @Test public void addingRulesKeepsOneLookupMapInsteadOfRebuildingItForEveryTag() {
        RouteRegion region = new RouteRegion();
        region.initRouteEncodingRule(1, "highway", "primary");
        assertEquals(1, region.searchRouteEncodingRule("highway", "primary"));
        Map<String, Integer> cache = region.decodingRules;
        for (int id = 2; id <= 2000; id++) {
            String value = Integer.toString(id);
            region.initRouteEncodingRule(id, "maxweight", value);
            assertSame("append must not discard the whole lookup table", cache, region.decodingRules);
            assertEquals(id, region.searchRouteEncodingRule("maxweight", value));
        }
        assertEquals(2000, cache.size());
    }

    @Test public void replacingARuleInvalidatesTheOldLookup() {
        RouteRegion region = new RouteRegion();
        region.initRouteEncodingRule(1, "highway", "primary");
        region.searchRouteEncodingRule("highway", "primary");
        Map<String, Integer> cache = region.decodingRules;
        region.initRouteEncodingRule(1, "highway", "secondary");
        assertNull(region.decodingRules);
        assertEquals(-1, region.searchRouteEncodingRule("highway", "primary"));
        assertEquals(1, region.searchRouteEncodingRule("highway", "secondary"));
        assertNotSame(cache, region.decodingRules);
    }
}
