package applygray.integration.ae2.planning;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndOrRoutePlannerTest {

    private final AndOrRoutePlanner<String> planner = new AndOrRoutePlanner<>();

    @Test
    void sharedInventoryChoiceIsIndependentOfRootInputOrder() {
        TestGraph graph = new TestGraph(Map.of("stock", 1L), Map.of("raw-cheap", 1L, "raw-expensive", 100L));
        var first = edge("root", List.of(input("stock", "raw-cheap"), input("stock", "raw-expensive")),
                amount("target", 1));
        var reordered = edge("root", List.of(input("stock", "raw-expensive"), input("stock", "raw-cheap")),
                amount("target", 1));

        var firstResult = planner.plan(first, "target", graph, limits());
        var reorderedResult = planner.plan(reordered, "target", graph, limits());

        assertEquals(AndOrRoutePlanner.Status.COMPLETE, firstResult.status());
        assertEquals(firstResult.cost(), reorderedResult.cost());
        assertEquals(1, firstResult.cost().missingMaterials());
        assertTrue(firstResult.rootInputChoices().stream().anyMatch(choice -> choice.key().equals("stock")));
        assertTrue(firstResult.rootInputChoices().stream().anyMatch(choice -> choice.key().equals("raw-cheap")));
    }

    @Test
    void bestFirstSearchSelectsTheCheapestCompleteDependencyRoute() {
        TestGraph graph = new TestGraph(Map.of(), Map.of("ore-a", 10L, "ore-b", 2L));
        graph.edges.put("intermediate", List.of(
                edge("expensive", List.of(input("ore-a")), amount("intermediate", 1)),
                edge("cheap", List.of(input("ore-b")), amount("intermediate", 1))));
        var root = edge("root", List.of(input("intermediate")), amount("target", 1));

        var result = planner.plan(root, "target", graph, limits());

        assertEquals(AndOrRoutePlanner.Status.COMPLETE, result.status());
        assertEquals(2, result.cost().missingMaterials());
        assertEquals(List.of("root", "cheap"), result.selectedEdges());
        assertEquals("root", result.selectedRoutes().get("target"));
        assertEquals("cheap", result.selectedRoutes().get("intermediate"));
    }

    @Test
    void coProductCanSatisfyAnotherAndInput() {
        TestGraph graph = new TestGraph(Map.of(), Map.of("ore", 1L));
        graph.edges.put("a", List.of(new AndOrRoutePlanner.Edge<>("split",
                List.of(input("ore")), List.of(amount("a", 1), amount("b", 1)), 1, 0, 0)));
        var root = edge("root", List.of(input("a"), input("b")), amount("target", 1));

        var result = planner.plan(root, "target", graph, limits());

        assertEquals(AndOrRoutePlanner.Status.COMPLETE, result.status());
        assertEquals(1, result.cost().missingMaterials());
        assertEquals(List.of("root", "split"), result.selectedEdges());
    }

    @Test
    void unseededCycleIsDegradedInsteadOfReportedComplete() {
        TestGraph graph = new TestGraph(Map.of(), Map.of());
        graph.edges.put("a", List.of(edge("a-from-b", List.of(input("b")), amount("a", 1))));
        graph.edges.put("b", List.of(edge("b-from-a", List.of(input("a")), amount("b", 1))));
        var root = edge("root", List.of(input("a")), amount("target", 1));

        var result = planner.plan(root, "target", graph, limits());

        assertEquals(AndOrRoutePlanner.Status.DEGRADED, result.status());
        assertEquals("UNRESOLVED_ROUTE", result.reasonCode());
        assertTrue(result.cost().unresolvedIntermediates() > 0);
    }

    @Test
    void netPositiveSelfRecipeStillRequiresAnExternalSeed() {
        TestGraph graph = new TestGraph(Map.of(), Map.of());
        graph.edges.put("a", List.of(edge("duplicate-a", List.of(input("a")), amount("a", 2))));
        var root = edge("root", List.of(input("a")), amount("target", 1));

        var result = planner.plan(root, "target", graph, limits());

        assertEquals(AndOrRoutePlanner.Status.DEGRADED, result.status());
        assertTrue(result.cost().unresolvedIntermediates() > 0);
    }

    @Test
    void expansionBudgetReturnsAnExplicitDegradedResult() {
        TestGraph graph = new TestGraph(Map.of(), Map.of("ore", 1L));
        graph.edges.put("a", List.of(edge("a", List.of(input("b")), amount("a", 1))));
        graph.edges.put("b", List.of(edge("b", List.of(input("ore")), amount("b", 1))));
        var root = edge("root", List.of(input("a")), amount("target", 1));

        var result = planner.plan(root, "target", graph, new AndOrRoutePlanner.Limits(2, 16, 16));

        assertEquals(AndOrRoutePlanner.Status.DEGRADED, result.status());
        assertEquals("EXPANSION_LIMIT", result.reasonCode());
        assertTrue(result.cost().boundedFallbacks() > 0);
    }

    @Test
    void emptyAndInputCannotBecomeAFreeRecipe() {
        TestGraph graph = new TestGraph(Map.of(), Map.of());
        var root = edge("root", List.of(new AndOrRoutePlanner.Input<>(List.of())), amount("target", 1));

        var result = planner.plan(root, "target", graph, limits());

        assertEquals(AndOrRoutePlanner.Status.DEGRADED, result.status());
        assertEquals("NO_COMPLETE_ROUTE", result.reasonCode());
    }

    @Test
    void deepDependencyChainUsesTheExplicitFrontier() {
        TestGraph graph = new TestGraph(Map.of(), Map.of("ore", 1L));
        int depth = 512;
        for (int index = 0; index < depth; index++) {
            String output = "part-" + index;
            String input = index + 1 == depth ? "ore" : "part-" + (index + 1);
            graph.edges.put(output, List.of(edge("make-" + output, List.of(input(input)), amount(output, 1))));
        }
        var root = edge("root", List.of(input("part-0")), amount("target", 1));

        var result = planner.plan(root, "target", graph, new AndOrRoutePlanner.Limits(4096, 1024, 16));

        assertEquals(AndOrRoutePlanner.Status.COMPLETE, result.status());
        assertEquals(1, result.cost().missingMaterials());
        assertEquals(depth + 1, result.selectedEdges().size());
    }

    private static AndOrRoutePlanner.Limits limits() {
        return new AndOrRoutePlanner.Limits(256, 16, 16);
    }

    @SafeVarargs
    private static AndOrRoutePlanner.Edge<String> edge(String id,
                                                       List<AndOrRoutePlanner.Input<String>> inputs,
                                                       AndOrRoutePlanner.Amount<String>... outputs) {
        return new AndOrRoutePlanner.Edge<>(id, inputs, List.of(outputs), 1, 0, 0);
    }

    private static AndOrRoutePlanner.Input<String> input(String... alternatives) {
        return new AndOrRoutePlanner.Input<>(java.util.Arrays.stream(alternatives)
                .map(key -> amount(key, 1)).toList());
    }

    private static AndOrRoutePlanner.Amount<String> amount(String key, long amount) {
        return new AndOrRoutePlanner.Amount<>(key, amount);
    }

    private static final class TestGraph implements AndOrRoutePlanner.Graph<String> {

        private final Map<String, Long> inventory;
        private final Map<String, Long> leafCosts;
        private final Map<String, List<AndOrRoutePlanner.Edge<String>>> edges = new HashMap<>();

        private TestGraph(Map<String, Long> inventory, Map<String, Long> leafCosts) {
            this.inventory = inventory;
            this.leafCosts = leafCosts;
        }

        @Override
        public long getAvailable(String key) {
            return inventory.getOrDefault(key, 0L);
        }

        @Override
        public boolean isLeaf(String key) {
            return leafCosts.containsKey(key);
        }

        @Override
        public boolean isFree(String key) {
            return false;
        }

        @Override
        public long estimateMaterialCost(String key, long amount) {
            return leafCosts.getOrDefault(key, 1L) * amount;
        }

        @Override
        public List<AndOrRoutePlanner.Edge<String>> getEdges(String key, int depth) {
            return edges.getOrDefault(key, List.of());
        }

        @Override
        public String stableKey(String key) {
            return key;
        }

        @Override
        public boolean reserveExpansion() {
            return true;
        }

        @Override
        public boolean shouldContinue() {
            return true;
        }
    }
}
