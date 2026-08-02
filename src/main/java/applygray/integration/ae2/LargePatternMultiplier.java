package applygray.integration.ae2;

/** Chooses bounded RecipeMap batch sizes for AE2 planning. */
public final class LargePatternMultiplier {

    /** A fully scalable temporary pattern should collapse one recipe choice to one AE2 execution. */
    public static final int TARGET_MAX_PLANNED_PATTERN_RUNS = 1;
    /** One ordinary run is already the smallest possible planning unit. */
    public static final int MIN_ORDINARY_PATTERN_RUNS_FOR_BATCH = 2;

    private LargePatternMultiplier() {
    }

    /**
     * Returns a bounded multiplier that leaves the selected pattern with one AE2 execution when possible,
     * or {@code 1} when batching would not materially reduce the tree.
     *
     * <p>The last large-pattern execution may intentionally round the recipe count up. Its scaled inputs are then
     * planned as part of the same crafting tree and its additional outputs become normal crafting surplus.</p>
     */
    public static int chooseMultiplier(long ordinaryRuns, int maximumMultiplier) {
        int safeMaximum = Math.max(1, maximumMultiplier);
        if (ordinaryRuns < MIN_ORDINARY_PATTERN_RUNS_FOR_BATCH || safeMaximum <= 1) return 1;

        long desiredMultiplier = divideCeil(ordinaryRuns, TARGET_MAX_PLANNED_PATTERN_RUNS);
        long multiplier = Math.min(desiredMultiplier, safeMaximum);
        if (multiplier <= 1) return 1;

        return (int) multiplier;
    }

    /** Returns the number of executions AE2 needs when one detail represents {@code multiplier} ordinary runs. */
    public static long getPlannedRuns(long ordinaryRuns, int multiplier) {
        if (ordinaryRuns <= 0 || multiplier <= 0) return 0;
        return divideCeil(ordinaryRuns, multiplier);
    }

    private static long divideCeil(long numerator, long denominator) {
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : quotient + 1;
    }
}
