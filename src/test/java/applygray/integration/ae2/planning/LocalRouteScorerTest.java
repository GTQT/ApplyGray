package applygray.integration.ae2.planning;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRouteScorerTest {

    @Test
    void sharedSubproblemIsExpandedOnceAcrossAndInputsAndLaterRoots() {
        TestContext context = new TestContext(Map.of("ore", 1L));
        RouteModel.Edge<String> common = edge("common", input("ore"), "common");
        RouteModel.Edge<String> makeA = edge("make-a", input("common"), "a");
        RouteModel.Edge<String> makeB = edge("make-b", input("common"), "b");
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key)
                .addEdge("a", makeA.id(), makeA, List.of(List.of("common")))
                .addEdge("b", makeB.id(), makeB, List.of(List.of("common")))
                .addEdge("common", common.id(), common, List.of(List.of("ore")))
                .addNode("target")
                .build();
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));
        RouteModel.Edge<String> root = edge("root", List.of(input("a"), input("b")), "target");

        LocalRouteScorer.Result<String> first = scorer.score(root, "target", 1, 32);
        LocalRouteScorer.Result<String> second = scorer.score(root, "target", 1, 32);

        assertTrue(first.complete());
        assertEquals(3, first.expansions());
        assertEquals(0, second.expansions());
        assertEquals(4, first.selectedTargets());
    }

    @Test
    void selectedOccurrencePlanRepairsTheLocalInventoryLowerBound() {
        TestContext context = new TestContext(Map.of("ore", 5L), Map.of("ore", 1L));
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key).addNode("target").addNode("ore").build();
        RouteModel.Edge<String> root = edge("root", List.of(input("ore"), input("ore")), "target");
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));

        LocalRouteScorer.Result<String> local = scorer.score(root, "target", 1, 16);
        LocalRouteScorer.Result<String> materialized = scorer.scoreSelected(root, "target", 1, 32);

        assertEquals(0, local.cost().missingMaterials());
        assertTrue(materialized.complete());
        assertEquals(RoutePlan.SupplyKind.STOCK,
                materialized.routePlan().rootStep().inputChoices().get(0).supplyKind());
        assertEquals(RoutePlan.SupplyKind.LEAF,
                materialized.routePlan().rootStep().inputChoices().get(1).supplyKind());
    }

    @Test
    void occurrencePlanRepairsSharedStockWithoutReopeningGlobalSearch() {
        TestContext context = new TestContext(Map.of("ore", 1L), Map.of("part", 1L));
        RouteModel.Edge<String> makePart = edge("make-part", input("ore"), "part");
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key)
                .addEdge("part", makePart.id(), makePart, List.of(List.of("ore")))
                .addNode("target")
                .build();
        RouteModel.Edge<String> root = edge("root", List.of(input("part"), input("part")), "target");
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));

        LocalRouteScorer.Result<String> result = scorer.scoreSelected(root, "target", 1, 16);

        assertTrue(result.complete());
        assertEquals(2, result.routePlan().steps().size());
        RoutePlan.Step<String> plannedRoot = result.routePlan().rootStep();
        assertEquals(RoutePlan.SupplyKind.STOCK, plannedRoot.inputChoices().get(0).supplyKind());
        assertEquals(RoutePlan.SupplyKind.RECIPE, plannedRoot.inputChoices().get(1).supplyKind());
        assertEquals("make-part", result.routePlan().childrenOf(plannedRoot.id()).get(0).edgeId());
    }

    @Test
    void occurrencePlanPublishesCoProductsBeforeLaterSiblingDemand() {
        TestContext context = new TestContext(Map.of("ore", 1L), Map.of("b", 1L));
        RouteModel.Edge<String> makeA = new RouteModel.Edge<>("make-a", List.of(input("ore")),
                List.of(new RouteModel.Amount<>("a", 1), new RouteModel.Amount<>("b", 1)),
                1, 0, 0);
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key)
                .addEdge("a", makeA.id(), makeA, List.of(List.of("ore")))
                .addNode("target")
                .build();
        RouteModel.Edge<String> root = edge("root", List.of(input("b"), input("a")), "target");
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));

        LocalRouteScorer.Result<String> result = scorer.scoreSelected(root, "target", 1, 16);

        assertTrue(result.complete());
        assertEquals(RoutePlan.SupplyKind.CO_PRODUCT,
                result.routePlan().rootStep().inputChoices().get(0).supplyKind());
    }

    @Test
    void completedOccurrencePlanKeepsDeepProductionRouteInsteadOfRecyclingCycle() {
        TestContext context = new TestContext(Map.of("fiber", 1L));
        RouteModel.Edge<String> cutter = edge("cutter", input("block"), "plate");
        RouteModel.Edge<String> solidifier = edge("solidifier", input("resin"), "block");
        RouteModel.Edge<String> extractor = edge("extractor", input("plate"), "resin");
        RouteModel.Edge<String> chemicalBath = edge("chemical-bath", input("fiber"), "plate");
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key)
                .addEdge("plate", cutter.id(), cutter, List.of(List.of("block")))
                .addEdge("plate", chemicalBath.id(), chemicalBath, List.of(List.of("fiber")))
                .addEdge("block", solidifier.id(), solidifier, List.of(List.of("resin")))
                .addEdge("resin", extractor.id(), extractor, List.of(List.of("plate")))
                .addNode("target")
                .build();
        RouteModel.Edge<String> root = edge("root", input("plate"), "target");
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));

        LocalRouteScorer.Result<String> result = scorer.scoreSelected(root, "target", 1, 64);

        assertTrue(result.complete());
        assertEquals(List.of("chemical-bath"), result.routePlan().childrenOf(0).stream()
                .map(RoutePlan.Step::edgeId).toList());
        assertFalse(result.routePlan().steps().values().stream().anyMatch(step -> "cutter".equals(step.edgeId())));
    }

    @Test
    void externalLeafDoesNotPretendTheLocalScoreIsComplete() {
        TestContext context = new TestContext(Map.of());
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key).addNode("target").addNode("external-part").build();
        RouteModel.Edge<String> root = edge("root", input("external-part"), "target");
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));

        LocalRouteScorer.Result<String> result = scorer.score(root, "target", 1, 16);

        assertFalse(result.complete());
        assertEquals(1, result.selectedTargets());
    }

    @Test
    void quotaLimitedPrefixMustBeCompletedBeforeStrictMaterialization() {
        TestContext context = new TestContext(Map.of("ore", 1L));
        RouteModel.Edge<String> makeA = edge("make-a", input("b"), "a");
        RouteModel.Edge<String> makeB = edge("make-b", input("ore"), "b");
        RecipeGraphIndex<String, RouteModel.Edge<String>> graph = new RecipeGraphIndex.Builder<String,
                RouteModel.Edge<String>>(key -> key)
                .addEdge("a", makeA.id(), makeA, List.of(List.of("b")))
                .addEdge("b", makeB.id(), makeB, List.of(List.of("ore")))
                .addNode("target")
                .build();
        RouteModel.Edge<String> root = edge("root", input("a"), "target");
        LocalRouteScorer<String> scorer = new LocalRouteScorer<>(graph, context, RoutePolicy.deterministic(),
                new LocalRouteScorer.Limits(16, 16));

        LocalRouteScorer.Result<String> result = scorer.score(root, "target", 1, 1);

        assertTrue(result.quotaLimited());
        assertEquals(2, result.selectedTargets());

        LocalRouteScorer.Result<String> completed = scorer.scoreSelected(root, "target", 1, 16);

        assertTrue(completed.complete());
        assertEquals(3, completed.routePlan().steps().size());
        assertEquals(3, completed.selectedTargets());
    }

    private static RouteModel.Edge<String> edge(String id, RouteModel.Input<String> input,
                                                        String output) {
        return edge(id, List.of(input), output);
    }

    private static RouteModel.Edge<String> edge(String id, List<RouteModel.Input<String>> inputs,
                                                        String output) {
        return new RouteModel.Edge<>(id, inputs,
                List.of(new RouteModel.Amount<>(output, 1)), 1, 0, 0);
    }

    private static RouteModel.Input<String> input(String key) {
        return new RouteModel.Input<>(List.of(new RouteModel.Amount<>(key, 1)), false);
    }

    private static final class TestContext implements RouteModel.RuntimeContext<String> {

        private final Map<String, Long> leafCosts;
        private final Map<String, Long> inventory;

        private TestContext(Map<String, Long> leafCosts) {
            this(leafCosts, Map.of());
        }

        private TestContext(Map<String, Long> leafCosts, Map<String, Long> inventory) {
            this.leafCosts = new HashMap<>(leafCosts);
            this.inventory = new HashMap<>(inventory);
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
        public boolean reserveExpansion() {
            return true;
        }

        @Override
        public boolean shouldContinue() {
            return true;
        }
    }
}
