package applygray.integration.ae2.planning;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanMaterializerTest {

    @Test
    void resolvesOnlyTheChoicesRecordedByThePlan() {
        RoutePlan.Step<String> step = new RoutePlan.Step<>(1, 0L, 0, "part", 2,
                "make-part", 2, 1, Map.of(
                0, new RoutePlan.InputChoice<>("ore-b", 3, RoutePlan.SupplyKind.LEAF, null),
                1, new RoutePlan.InputChoice<>("water", 4, RoutePlan.SupplyKind.STOCK, null)));

        var result = PlanMaterializer.resolveInputs(step, 2,
                (index, choice) -> index + ":" + choice.key() + "x" + choice.amount());

        assertTrue(result.complete());
        assertEquals(java.util.List.of("0:ore-bx3", "1:waterx4"), result.inputs());
    }

    @Test
    void reportsAStalePlannedInputWithoutSelectingAFallback() {
        RoutePlan.Step<String> step = new RoutePlan.Step<>(1, 0L, 0, "part", 1,
                "make-part", 1, 1, Map.of(0, new RoutePlan.InputChoice<>("removed", 1,
                RoutePlan.SupplyKind.LEAF, null)));

        var result = PlanMaterializer.resolveInputs(step, 1, (index, choice) -> null);

        assertFalse(result.complete());
        assertEquals(0, result.failedInputIndex());
        assertEquals("STALE_PLAN_INPUT", result.reasonCode());
    }

    @Test
    void materializesRepeatedTargetsAsDistinctRouteOccurrences() {
        RoutePlan.Step<String> root = new RoutePlan.Step<>(0, null, -1, "target", 1,
                "root", 1, 0, Map.of(
                0, new RoutePlan.InputChoice<>("part", 1, RoutePlan.SupplyKind.RECIPE, 1L),
                1, new RoutePlan.InputChoice<>("part", 1, RoutePlan.SupplyKind.RECIPE, 2L)));
        RoutePlan.Step<String> first = new RoutePlan.Step<>(1, 0L, 0, "part", 1,
                "route-a", 1, 1, Map.of());
        RoutePlan.Step<String> second = new RoutePlan.Step<>(2, 0L, 1, "part", 1,
                "route-b", 1, 1, Map.of());
        Map<Long, RoutePlan.Step<String>> steps = new LinkedHashMap<>();
        steps.put(0L, root);
        steps.put(1L, first);
        steps.put(2L, second);

        var result = PlanMaterializer.materialize(new RoutePlan<>(0, steps, List.of(0L, 1L, 2L)),
                RoutePlan.Step::edgeId);

        assertTrue(result.complete());
        assertEquals(List.of("route-a", "route-b"), result.root().children().stream()
                .map(PlanMaterializer.TreeNode::value).toList());
    }
}
