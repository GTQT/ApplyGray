package applygray.integration.ae2.planning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleAnalyzerTest {

    @Test
    void unseededStronglyConnectedComponentIsNotStartable() {
        RecipeGraphIndex<String, String> index = cycleGraph(List.of("b"));

        var result = analyzer().analyze("a", index, seedPolicy(Set.of()), limits());

        assertTrue(result.isComplete());
        assertTrue(result.isCyclic("a"));
        assertTrue(result.sameComponent("a", "b"));
        assertFalse(result.canReachSeed("a"));
        assertTrue(result.closesCycle("a", index.edgesFrom("a").get(0)));
    }

    @Test
    void oneOrAlternativeOutsideTheComponentCanSeedTheWholeComponent() {
        RecipeGraphIndex<String, String> index = cycleGraph(List.of("b", "ore"));

        var result = analyzer().analyze("a", index, seedPolicy(Set.of("ore")), limits());

        assertTrue(result.isCyclic("a"));
        assertTrue(result.canReachSeed("a"));
        assertTrue(result.edgeCanReachSeed("a", index.edgesFrom("a").get(0)));
    }

    @Test
    void everyAndInputGroupMustBeSeedReachable() {
        RecipeGraphIndex<String, String> index = new RecipeGraphIndex.Builder<String, String>(key -> key)
                .addEdge("a", "a-from-b-and-ore", "a", List.of(List.of("b"), List.of("ore")))
                .addEdge("b", "b-from-a", "b", List.of(List.of("a")))
                .build();

        var result = analyzer().analyze("a", index, seedPolicy(Set.of("ore")), limits());

        assertTrue(result.isCyclic("a"));
        assertFalse(result.canReachSeed("a"));
    }

    @Test
    void analysisReportsItsExactBudgetReason() {
        RecipeGraphIndex<String, String> index = cycleGraph(List.of("b"));

        var result = analyzer().analyze("a", index, seedPolicy(Set.of()),
                new CycleAnalyzer.Limits(1, 16, TimeUnit.SECONDS.toNanos(1)));

        assertFalse(result.isComplete());
        assertEquals("SCC_NODE_LIMIT", result.reasonCode());
    }

    private static RecipeGraphIndex<String, String> cycleGraph(List<String> aAlternatives) {
        return new RecipeGraphIndex.Builder<String, String>(key -> key)
                .addEdge("a", "a-route", "a", List.of(aAlternatives))
                .addEdge("b", "b-route", "b", List.of(List.of("a")))
                .build();
    }

    private static CycleAnalyzer<String, String> analyzer() {
        return new CycleAnalyzer<>();
    }

    private static CycleAnalyzer.SeedPolicy<String, String> seedPolicy(Set<String> seeds) {
        return new CycleAnalyzer.SeedPolicy<>() {
            @Override
            public boolean isDirectSeed(String key) {
                return seeds.contains(key);
            }

            @Override
            public boolean isSeedEdge(String output, RecipeGraphIndex.HyperEdge<String, String> edge) {
                return false;
            }
        };
    }

    private static CycleAnalyzer.Limits limits() {
        return new CycleAnalyzer.Limits(32, 64, TimeUnit.SECONDS.toNanos(1));
    }
}
