package net.osmand.plus.roadcrew;

public class CacheTest {
    private static void check(boolean value, String reason) {
        if (!value) { throw new AssertionError(reason); }
    }

    public static void main(String[] args) {
        RoadCrewCameraCache<String> cache = new RoadCrewCameraCache<>();
        long first = cache.begin(100);
        check(first >= 0 && cache.begin(100) == -1, "single flight");
        cache.complete(first, "old maps", 100);
        check("old maps".equals(cache.get()), "successful snapshot");
        long oldRead = cache.begin(200);
        cache.invalidate();
        check(cache.get() == null, "map changes invalidate even nonempty data");
        cache.complete(oldRead, "stale in flight", 200);
        check(cache.get() == null, "old generation cannot repopulate cache");
        long newRead = cache.begin(201);
        cache.complete(newRead, "new maps", 201);
        check("new maps".equals(cache.get()), "new generation accepted");
        long failed = cache.begin(300);
        cache.complete(failed, null, 300);
        check("new maps".equals(cache.get()), "failure preserves last successful data");
        check(cache.begin(301) == -1, "failure retry bounded, not every frame");
        check(cache.begin(5300) >= 0, "failure retried without moving");
        System.out.println("8 camera cache checks passed");
    }
}
