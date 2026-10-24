package net.byhobby.worldtourmod.tour;

public final class TourSpeed {
    public static final int TICKS_PER_SECOND = 20;
    public static final double DEFAULT_BLOCKS_PER_SECOND = 8.0;
    public static final double PORTAL_EASE_BLOCKS = 10.0;
    public static final double PORTAL_MIN_FACTOR = 0.12;

    /** Everything a speed rule might want to know about the current moment of the tour. */
    public record Progress(String pathId, int segmentIndex, int blockIndex, int segmentBlocks,
                           double distanceTravelled, long ticksRiding) {}

    @FunctionalInterface
    public interface Provider {
        double blocksPerSecond(Progress progress);
    }

    public static double portalFactor(double distance) {
        if (distance >= PORTAL_EASE_BLOCKS) return 1.0;
        double x = Math.max(0.0, distance) / PORTAL_EASE_BLOCKS;
        double smooth = x * x * (3 - 2 * x);                    // smoothstep
        return PORTAL_MIN_FACTOR + (1.0 - PORTAL_MIN_FACTOR) * smooth;
    }

    private static volatile Provider provider = p -> DEFAULT_BLOCKS_PER_SECOND;

    private TourSpeed() {}

    public static void set(Provider p) { provider = p; }

    public static double blocksPerTick(Progress p) {
        return Math.max(0.0, provider.blocksPerSecond(p)) / TICKS_PER_SECOND;
    }
}