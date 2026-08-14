package applygray.integration.ae2.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGraphIndexTest {

    @Test
    void buildFreezesAndDeterministicallyOrdersTheHypergraph() {
        List<String> mutableOptions = new ArrayList<>(List.of("ore-b", "ore-a"));
        RecipeGraphIndex<String, String> index = new RecipeGraphIndex.Builder<String, String>(key -> key)
                .addEdge("part", "route-z", "z", List.of(mutableOptions))
                .addEdge("part", "route-a", "a", List.of(List.of("ore-c")))
                .build();
        mutableOptions.add("late-mutation");

        assertEquals(List.of("ore-a", "ore-b", "ore-c", "part"), new ArrayList<>(index.nodes()));
        assertEquals(List.of("route-a", "route-z"), index.edgesFrom("part").stream()
                .map(RecipeGraphIndex.HyperEdge::id).toList());
        assertEquals(List.of("ore-a", "ore-b"), index.edgesFrom("part").get(1).inputGroups().get(0));
        assertThrows(UnsupportedOperationException.class, () -> index.edgesFrom("part").clear());
    }

    @Test
    void captureBuildsTheReachableGraphBeforePlanning() {
        Map<String, List<RecipeGraphIndex.HyperEdge<String, String>>> source = Map.of(
                "a", List.of(new RecipeGraphIndex.HyperEdge<>("a-from-b", "a", List.of(List.of("b")))),
                "b", List.of(new RecipeGraphIndex.HyperEdge<>("b-from-ore", "b", List.of(List.of("ore")))));

        var result = RecipeGraphIndex.capture(Set.of("a"), key -> key, key -> key.equals("ore"),
                (key, depth) -> source.getOrDefault(key, List.of()), () -> true,
                new RecipeGraphIndex.CaptureLimits(8, 8, 8));

        assertTrue(result.complete());
        assertEquals(Set.of("a", "b", "ore"), result.index().nodes());
        assertEquals("a-from-b", result.index().edgesFrom("a").get(0).id());
        assertEquals("b-from-ore", result.index().edgesFrom("b").get(0).id());
    }

    @Test
    void captureReturnsAnExplicitPartialIndexAtItsNodeBudget() {
        Map<String, List<RecipeGraphIndex.HyperEdge<String, String>>> source = Map.of(
                "a", List.of(new RecipeGraphIndex.HyperEdge<>("a-from-b", "a", List.of(List.of("b")))),
                "b", List.of(new RecipeGraphIndex.HyperEdge<>("b-from-ore", "b", List.of(List.of("ore")))));

        var result = RecipeGraphIndex.capture(Set.of("a"), key -> key, key -> false,
                (key, depth) -> source.getOrDefault(key, List.of()), () -> true,
                new RecipeGraphIndex.CaptureLimits(2, 8, 8));

        assertFalse(result.complete());
        assertEquals("GRAPH_NODE_LIMIT", result.reasonCode());
        assertEquals(Set.of("a", "b"), result.index().nodes());
        assertTrue(result.index().edgesFrom("b").isEmpty());
    }

    @Test
    void captureReturnsAnExplicitPartialIndexAtItsEdgeBudget() {
        List<RecipeGraphIndex.HyperEdge<String, String>> edges = List.of(
                new RecipeGraphIndex.HyperEdge<>("a-route", "a", List.of(List.of("ore-a"))),
                new RecipeGraphIndex.HyperEdge<>("b-route", "b", List.of(List.of("ore-b"))));

        var result = RecipeGraphIndex.capture(Set.of("part"), key -> key, key -> false,
                (key, depth) -> key.equals("part") ? edges : List.of(), () -> true,
                new RecipeGraphIndex.CaptureLimits(8, 1, 8));

        assertFalse(result.complete());
        assertEquals("GRAPH_EDGE_LIMIT", result.reasonCode());
        assertEquals(List.of("a-route"), result.index().edgesFrom("part").stream()
                .map(RecipeGraphIndex.HyperEdge::id).toList());
    }

    @Test
    void captureReturnsAnExplicitPartialIndexWhenCancelled() {
        var result = RecipeGraphIndex.capture(Set.of("part"), key -> key, key -> false,
                (key, depth) -> List.of(), () -> false,
                new RecipeGraphIndex.CaptureLimits(8, 8, 8));

        assertFalse(result.complete());
        assertEquals("GRAPH_DEADLINE_OR_CANCELLED", result.reasonCode());
        assertEquals(Set.of("part"), result.index().nodes());
    }

    @Test
    void captureCachesStableKeysAcrossQueueOrderingAndIndexBuild() {
        int width = 1024;
        List<String> dependencies = new ArrayList<>(width);
        for (int index = width - 1; index >= 0; index--) dependencies.add("part-" + index);
        Map<String, Integer> stableKeyCalls = new HashMap<>();

        var result = RecipeGraphIndex.capture(Set.of("root"), key -> {
                    stableKeyCalls.merge(key, 1, Integer::sum);
                    return key;
                }, key -> !key.equals("root"),
                (key, depth) -> key.equals("root") ? List.of(
                        new RecipeGraphIndex.HyperEdge<>("wide", "wide", List.of(dependencies))) : List.of(),
                () -> true, new RecipeGraphIndex.CaptureLimits(width + 1, 1, 2));

        assertTrue(result.complete());
        assertEquals(width + 1, result.index().nodes().size());
        assertTrue(stableKeyCalls.values().stream().allMatch(calls -> calls == 1));
    }
}
