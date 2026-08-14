package applygray.integration.ae2.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Resolves a completed route-plan step into concrete, single-choice inputs without performing route search. */
public final class PlanMaterializer {

    public record Result<T>(boolean complete, List<T> inputs, int failedInputIndex, String reasonCode) {

        public Result {
            inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        }
    }

    public record TreeNode<K, T>(RoutePlan.Step<K> step, T value, List<TreeNode<K, T>> children) {

        public TreeNode {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(value, "value");
            children = Collections.unmodifiableList(new ArrayList<>(children));
        }
    }

    public record TreeResult<K, T>(boolean complete, TreeNode<K, T> root, long failedStepId,
                                    String reasonCode) {}

    private PlanMaterializer() {}

    public static <K, T> Result<T> resolveInputs(RoutePlan.Step<K> step, int inputCount,
                                                  BiFunction<Integer, RoutePlan.InputChoice<K>, T> resolver) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(resolver, "resolver");
        if (inputCount < 0) throw new IllegalArgumentException("inputCount must be non-negative");

        List<T> inputs = new ArrayList<>(inputCount);
        for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
            RoutePlan.InputChoice<K> choice = step.inputChoices().get(inputIndex);
            if (choice == null) {
                return new Result<>(false, inputs, inputIndex, "MISSING_PLAN_INPUT");
            }
            T resolved = resolver.apply(inputIndex, choice);
            if (resolved == null) {
                return new Result<>(false, inputs, inputIndex, "STALE_PLAN_INPUT");
            }
            inputs.add(resolved);
        }
        return new Result<>(true, inputs, -1, "OK");
    }

    /** Materializes every route occurrence without collapsing repeated target keys. */
    public static <K, T> TreeResult<K, T> materialize(RoutePlan<K> plan,
                                                       Function<RoutePlan.Step<K>, T> resolver) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(resolver, "resolver");
        TreeBuild<K, T> build = build(plan, plan.rootStep(), resolver, new java.util.HashSet<>());
        if (!build.complete) return new TreeResult<>(false, null, build.failedStepId, build.reasonCode);
        if (build.visited.size() != plan.steps().size()) {
            long missing = plan.selectionOrder().stream().filter(id -> !build.visited.contains(id))
                    .findFirst().orElse(-1L);
            return new TreeResult<>(false, null, missing, "UNREACHABLE_PLAN_STEP");
        }
        return new TreeResult<>(true, build.node, -1, "OK");
    }

    private static <K, T> TreeBuild<K, T> build(RoutePlan<K> plan, RoutePlan.Step<K> step,
                                                 Function<RoutePlan.Step<K>, T> resolver,
                                                 java.util.Set<Long> path) {
        if (!path.add(step.id())) return TreeBuild.failure(step.id(), "CYCLIC_PLAN_STEPS");
        for (RoutePlan.InputChoice<K> choice : step.inputChoices().values()) {
            if (choice.supplyKind() == RoutePlan.SupplyKind.UNKNOWN) {
                return TreeBuild.failure(step.id(), "INCOMPLETE_PLAN_SUPPLY");
            }
        }
        T value = resolver.apply(step);
        if (value == null) return TreeBuild.failure(step.id(), "STALE_PLAN_STEP");

        List<RoutePlan.Step<K>> children = plan.childrenOf(step.id());
        Map<Integer, RoutePlan.Step<K>> byInput = new java.util.HashMap<>();
        List<TreeNode<K, T>> materialized = new ArrayList<>(children.size());
        java.util.Set<Long> visited = new java.util.HashSet<>();
        visited.add(step.id());
        for (RoutePlan.Step<K> child : children) {
            if (byInput.putIfAbsent(child.parentInputIndex(), child) != null) {
                return TreeBuild.failure(child.id(), "DUPLICATE_PLAN_CHILD");
            }
            RoutePlan.InputChoice<K> choice = step.inputChoices().get(child.parentInputIndex());
            if (choice == null || !choice.key().equals(child.target()) ||
                    choice.supplierStepId() == null || choice.supplierStepId() != child.id() ||
                    choice.supplyKind() != RoutePlan.SupplyKind.RECIPE &&
                            choice.supplyKind() != RoutePlan.SupplyKind.MIXED) {
                return TreeBuild.failure(child.id(), "INVALID_PLAN_CHILD_LINK");
            }
            TreeBuild<K, T> childBuild = build(plan, child, resolver, path);
            if (!childBuild.complete) return childBuild;
            materialized.add(childBuild.node);
            visited.addAll(childBuild.visited);
        }
        for (Map.Entry<Integer, RoutePlan.InputChoice<K>> input : step.inputChoices().entrySet()) {
            if (input.getValue().supplierStepId() != null && !byInput.containsKey(input.getKey())) {
                return TreeBuild.failure(input.getValue().supplierStepId(), "MISSING_PLAN_CHILD");
            }
        }
        path.remove(step.id());
        return TreeBuild.success(new TreeNode<>(step, value, materialized), visited);
    }

    private static final class TreeBuild<K, T> {

        private final boolean complete;
        private final TreeNode<K, T> node;
        private final long failedStepId;
        private final String reasonCode;
        private final java.util.Set<Long> visited;

        private TreeBuild(boolean complete, TreeNode<K, T> node, long failedStepId, String reasonCode,
                          java.util.Set<Long> visited) {
            this.complete = complete;
            this.node = node;
            this.failedStepId = failedStepId;
            this.reasonCode = reasonCode;
            this.visited = visited;
        }

        private static <K, T> TreeBuild<K, T> success(TreeNode<K, T> node, java.util.Set<Long> visited) {
            return new TreeBuild<>(true, node, -1, "OK", visited);
        }

        private static <K, T> TreeBuild<K, T> failure(long stepId, String reasonCode) {
            return new TreeBuild<>(false, null, stepId, reasonCode, java.util.Set.of());
        }
    }
}
