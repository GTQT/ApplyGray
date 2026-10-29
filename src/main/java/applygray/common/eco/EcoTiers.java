package applygray.common.eco;

/** PrismPlan's five hardware tiers. These are byte counts, never display-unit conversions. */
public final class EcoTiers {
    public static final int COUNT = 5;
    public static final int MAX_SEGMENTS = 16;
    public static final int PATTERN_SLOTS = 63;
    private static final long[] MEMORY = {4, 64, 1024, 16384, 262144};
    private static final int[] STORAGE_MIB = {4, 16, 64, 256, 1024};
    private static final int[] ACCELERATORS = {32, 256, 2048, 16384, 131072};
    private static final int[] THREADS = {1, 2, 4, 8, 16};
    private static final int[] HYPER_THREADS = {2, 4, 8, 16, 32};
    private static final long[] ENERGY = {1000000, 4000000, 16000000, 64000000, 256000000};
    public static final int[] DEFAULT_BATCHES = {32, 128, 512, 2048, 8192};

    private EcoTiers() {}

    private static int index(int tier) {
        if (tier < 1 || tier > COUNT) throw new IllegalArgumentException("ECO tier: " + tier);
        return tier - 1;
    }

    public static long memory(int tier) { return MEMORY[index(tier)] * 1000L * 1024L; }
    public static int storageBytes(int tier) { return STORAGE_MIB[index(tier)] * 1024 * 1024; }
    public static int bytesPerType(int tier) { return (1 << (2 * index(tier))) * 1024; }
    public static double idleDrain(int tier) { return STORAGE_MIB[index(tier)]; }
    public static int accelerators(int tier) { return ACCELERATORS[index(tier)]; }
    public static int threads(int tier, boolean hyper) { return (hyper ? HYPER_THREADS : THREADS)[index(tier)]; }
    public static int normalThreadsOnHyperCore(int tier) { return tier == 5 ? 1 : 0; }
    public static long energy(int tier) { return ENERGY[index(tier)]; }
}
