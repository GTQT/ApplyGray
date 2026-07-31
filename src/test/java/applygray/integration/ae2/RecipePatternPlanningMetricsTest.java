package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipePatternPlanningMetricsTest {

    @Test
    void snapshotSummarizesCountersAndPlanningP95() {
        RecipePatternPlanningMetrics metrics = new RecipePatternPlanningMetrics();

        metrics.recordTargetCacheHit();
        metrics.recordTargetCacheMiss();
        metrics.recordRouteCandidateCacheHit();
        metrics.recordRouteCandidateCacheMiss();
        metrics.recordGeneratedPattern();
        metrics.recordReusedPattern();
        metrics.recordBudgetExhaustion();
        metrics.recordIndex(42, 3_000_000L);
        metrics.recordPlanningDuration(1_000_000L);
        metrics.recordPlanningDuration(7_000_000L);

        assertEquals("目标缓存 1/1 路线缓存 1/1 p95 7ms\n索引 1/42 3ms 样板 1/1 预算 1",
                metrics.snapshot().summarize());
    }
}
