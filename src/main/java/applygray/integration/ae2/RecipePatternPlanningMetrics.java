package applygray.integration.ae2;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, lock-light operational counters for dynamic RecipeMap planning.
 *
 * <p>The sample window intentionally stores only planning durations, never recipe, grid, or world objects. It is
 * therefore safe to update from AE worker threads and to display from a Provider UI on the server thread.</p>
 */
final class RecipePatternPlanningMetrics {

    private static final int PLANNING_SAMPLE_LIMIT = 256;

    private final LongAdder targetCacheHits = new LongAdder();
    private final LongAdder targetCacheMisses = new LongAdder();
    private final LongAdder routeCandidateCacheHits = new LongAdder();
    private final LongAdder routeCandidateCacheMisses = new LongAdder();
    private final LongAdder generatedPatterns = new LongAdder();
    private final LongAdder reusedPatterns = new LongAdder();
    private final LongAdder budgetExhaustions = new LongAdder();
    private final LongAdder indexBuilds = new LongAdder();
    private final LongAdder indexedRecipes = new LongAdder();
    private final LongAdder indexNanos = new LongAdder();
    private final long[] planningSamples = new long[PLANNING_SAMPLE_LIMIT];
    private int nextPlanningSample;
    private int planningSampleCount;

    void recordTargetCacheHit() {
        targetCacheHits.increment();
    }

    void recordTargetCacheMiss() {
        targetCacheMisses.increment();
    }

    void recordRouteCandidateCacheHit() {
        routeCandidateCacheHits.increment();
    }

    void recordRouteCandidateCacheMiss() {
        routeCandidateCacheMisses.increment();
    }

    void recordGeneratedPattern() {
        generatedPatterns.increment();
    }

    void recordReusedPattern() {
        reusedPatterns.increment();
    }

    void recordBudgetExhaustion() {
        budgetExhaustions.increment();
    }

    void recordIndex(int recipes, long elapsedNanos) {
        indexBuilds.increment();
        indexedRecipes.add(Math.max(0, recipes));
        indexNanos.add(Math.max(0, elapsedNanos));
    }

    synchronized void recordPlanningDuration(long elapsedNanos) {
        planningSamples[nextPlanningSample] = Math.max(0, elapsedNanos);
        nextPlanningSample = (nextPlanningSample + 1) % PLANNING_SAMPLE_LIMIT;
        if (planningSampleCount < PLANNING_SAMPLE_LIMIT) planningSampleCount++;
    }

    Snapshot snapshot() {
        long[] samples;
        synchronized (this) {
            samples = Arrays.copyOf(planningSamples, planningSampleCount);
        }
        Arrays.sort(samples);
        long p95Nanos = samples.length == 0 ? 0 : samples[(samples.length * 95 + 99) / 100 - 1];
        return new Snapshot(targetCacheHits.sum(), targetCacheMisses.sum(), routeCandidateCacheHits.sum(),
                routeCandidateCacheMisses.sum(), generatedPatterns.sum(), reusedPatterns.sum(),
                budgetExhaustions.sum(), indexBuilds.sum(), indexedRecipes.sum(), indexNanos.sum(), p95Nanos);
    }

    static final class Snapshot {

        private final long targetCacheHits;
        private final long targetCacheMisses;
        private final long routeCandidateCacheHits;
        private final long routeCandidateCacheMisses;
        private final long generatedPatterns;
        private final long reusedPatterns;
        private final long budgetExhaustions;
        private final long indexBuilds;
        private final long indexedRecipes;
        private final long indexNanos;
        private final long p95PlanningNanos;

        private Snapshot(long targetCacheHits, long targetCacheMisses, long routeCandidateCacheHits,
                         long routeCandidateCacheMisses, long generatedPatterns, long reusedPatterns,
                         long budgetExhaustions, long indexBuilds, long indexedRecipes, long indexNanos,
                         long p95PlanningNanos) {
            this.targetCacheHits = targetCacheHits;
            this.targetCacheMisses = targetCacheMisses;
            this.routeCandidateCacheHits = routeCandidateCacheHits;
            this.routeCandidateCacheMisses = routeCandidateCacheMisses;
            this.generatedPatterns = generatedPatterns;
            this.reusedPatterns = reusedPatterns;
            this.budgetExhaustions = budgetExhaustions;
            this.indexBuilds = indexBuilds;
            this.indexedRecipes = indexedRecipes;
            this.indexNanos = indexNanos;
            this.p95PlanningNanos = p95PlanningNanos;
        }

        String summarize() {
            return "目标缓存 " + targetCacheHits + '/' + targetCacheMisses + " 路线缓存 " +
                    routeCandidateCacheHits + '/' + routeCandidateCacheMisses + " p95 " +
                    p95PlanningNanos / 1_000_000L + "ms\n索引 " + indexBuilds + '/' + indexedRecipes + " " +
                    indexNanos / 1_000_000L + "ms 样板 " + generatedPatterns + '/' + reusedPatterns + " 预算 " +
                    budgetExhaustions;
        }
    }
}
