package applygray.integration.ae2.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Memoized local lower-bound scorer used to rank root routes before occurrence-level materialization.
 *
 * <p>The scorer deliberately does not mutate the shared occurrence inventory or publish co-products. Those
 * interactions belong to the single materialization pass after a root has been selected. Keeping them out of route
 * ranking turns independent AND inputs back into memoizable subproblems instead of a cartesian product of complete
 * inventory states.</p>
 */
public final class LocalRouteScorer<K> {

    public record Limits(int maxDepth, int maxInputAlternatives) {

        public Limits {
            if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be positive");
            if (maxInputAlternatives <= 0) {
                throw new IllegalArgumentException("maxInputAlternatives must be positive");
            }
        }
    }

    public record Result<K>(RouteModel.Cost cost, RoutePlan<K> routePlan, int expansions, int scoreExpansions,
                            int repairExpansions, int boundPrunedEdges, int selectedTargets, boolean complete,
                            boolean quotaLimited, String reasonCode) {}

    private record MemoKey<K>(K key, long amount, int depth) {}

    private record ProgressKey<K>(K key, long amount, int depth, Set<K> ancestors) {

        private ProgressKey {
            ancestors = Set.copyOf(ancestors);
        }
    }

    private static final class Node<K> {

        private final K target;
        private final RouteModel.Edge<K> edge;
        private final List<RouteModel.Amount<K>> choices;
        private final List<Node<K>> children;
        private final RouteModel.Cost cost;
        private final boolean complete;
        private final boolean contextIndependent;

        private Node(K target, RouteModel.Edge<K> edge,
                     List<RouteModel.Amount<K>> choices, List<Node<K>> children,
                     RouteModel.Cost cost, boolean complete, boolean contextIndependent) {
            this.target = target;
            this.edge = edge;
            this.choices = choices;
            this.children = children;
            this.cost = cost;
            this.complete = complete;
            this.contextIndependent = contextIndependent;
        }
    }

    private static final class CallBudget {

        private final int limit;
        private int expansions;
        private int boundPrunedEdges;
        private boolean quotaLimited;
        private boolean sharedBudgetLimited;

        private CallBudget(int limit) {
            this.limit = Math.max(1, limit);
        }

        private boolean retainsFrontier() {
            return limit != Integer.MAX_VALUE;
        }
    }

    /** Resumable work for one demand; completed children still graduate into the context-independent memo. */
    private static final class DemandProgress<K> {

        private List<RouteModel.Edge<K>> edges;
        private int edgeIndex;
        private Node<K> best;
        private EdgeProgress<K> activeEdge;
    }

    /** Cursor within one edge, including the current input alternative. */
    private static final class EdgeProgress<K> {

        private final K target;
        private final RouteModel.Edge<K> edge;
        private final long crafts;
        private final int depth;
        private final Set<K> ancestors;
        private final List<RouteModel.Amount<K>> choices = new ArrayList<>();
        private final List<Node<K>> children = new ArrayList<>();
        private RouteModel.Cost cost;
        private int inputIndex;
        private List<RouteModel.Amount<K>> alternatives;
        private int alternativeIndex;
        private Node<K> bestAlternative;
        private RouteModel.Amount<K> bestChoice;
        private boolean dominated;
        private boolean complete = true;
        private boolean contextIndependent = true;

        private EdgeProgress(K target, RouteModel.Edge<K> edge, long crafts, int depth, Set<K> ancestors,
                             RouteModel.Cost cost, boolean retainFrontier) {
            this.target = target;
            this.edge = edge;
            this.crafts = crafts;
            this.depth = depth;
            this.ancestors = retainFrontier ? Set.copyOf(ancestors) : ancestors;
            this.cost = cost;
        }
    }

    private final class PlanBuild {

        private final PlanningInventory<K> inventory = new PlanningInventory<>();
        private final Map<Long, RoutePlan.Step<K>> steps = new LinkedHashMap<>();
        private final List<Long> order = new ArrayList<>();
        private long nextStepId;
        private boolean complete = true;
        private String reasonCode = "OK";

        private void fail(String reason) {
            if (!complete) return;
            complete = false;
            reasonCode = reason;
        }
    }

    private final RecipeGraphIndex<K, RouteModel.Edge<K>> graph;
    private final RouteModel.RuntimeContext<K> context;
    private final RoutePolicy routePolicy;
    private final Limits limits;
    private final Map<MemoKey<K>, Node<K>> memo = new HashMap<>();
    private final Map<ProgressKey<K>, DemandProgress<K>> demandProgress = new HashMap<>();
    private final Map<K, List<RouteModel.Edge<K>>> producingEdges = new HashMap<>();
    private final Map<RouteModel.Input<K>, List<RouteModel.Amount<K>>> orderedAlternatives =
            new java.util.IdentityHashMap<>();

    public LocalRouteScorer(RecipeGraphIndex<K, RouteModel.Edge<K>> graph,
                            RouteModel.RuntimeContext<K> context, RoutePolicy routePolicy, Limits limits) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.context = Objects.requireNonNull(context, "context");
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Result<K> score(RouteModel.Edge<K> root, K target, long requestedAmount,
                           int expansionQuota) {
        return score(root, target, requestedAmount, expansionQuota, false);
    }

    /** Scores and then occurrence-materializes only the route that already won candidate ranking. */
    public Result<K> scoreSelected(RouteModel.Edge<K> root, K target, long requestedAmount,
                                   int expansionQuota) {
        return score(root, target, requestedAmount, expansionQuota, true);
    }

    private Result<K> score(RouteModel.Edge<K> root, K target, long requestedAmount,
                            int expansionQuota, boolean materializeSelected) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(target, "target");
        if (requestedAmount <= 0) throw new IllegalArgumentException("requestedAmount must be positive");

        CallBudget budget = new CallBudget(expansionQuota);
        long netOutput = root.netOutput(target);
        if (netOutput <= 0) {
            return new Result<>(RouteModel.Cost.ZERO, null, 0, 0, 0, 0, 0,
                    false, false, "NON_POSITIVE_ROOT_OUTPUT");
        }
        long crafts = divideRoundUp(requestedAmount, netOutput);
        Node<K> node = scoreEdge(target, root, crafts, 1, new HashSet<>(Set.of(target)), budget);
        int scoreExpansions = budget.expansions;
        int selectedTargets = selectedTargetCount(node);
        RoutePlan<K> routePlan = null;
        if (materializeSelected && node.complete && !budget.quotaLimited && !budget.sharedBudgetLimited) {
            routePlan = materializeRoute(node, target, requestedAmount, budget);
        }
        boolean complete = node.complete && (!materializeSelected || routePlan != null);
        String reason = complete ? "OK" : budget.quotaLimited ? "LOCAL_SCORE_QUOTA" :
                budget.sharedBudgetLimited ? "SHARED_BUDGET_EXHAUSTED" : "UNRESOLVED_ROUTE";
        return new Result<>(node.cost, routePlan, budget.expansions, scoreExpansions,
                budget.expansions - scoreExpansions, budget.boundPrunedEdges, selectedTargets, complete,
                budget.quotaLimited, reason);
    }

    /**
     * Replays the selected local tree once against a shared inventory/co-product ledger. A demand that local scoring
     * considered stock-satisfied is repaired lazily only when an earlier occurrence has consumed that stock.
     */
    private RoutePlan<K> materializeRoute(Node<K> root, K target, long requestedAmount, CallBudget budget) {
        PlanBuild build = new PlanBuild();
        appendPlannedStep(root, target, requestedAmount, null, -1, 0, new HashSet<>(Set.of(target)), build, budget);
        if (!build.complete) return null;
        return new RoutePlan<>(0, build.steps, build.order);
    }

    private long appendPlannedStep(Node<K> selected, K target, long requestedAmount, Long parentStepId,
                                   int parentInputIndex, int depth, Set<K> ancestors, PlanBuild build,
                                   CallBudget budget) {
        if (!build.complete || selected == null || selected.edge == null) {
            build.fail("MISSING_SELECTED_EDGE");
            return -1;
        }
        RouteModel.Edge<K> edge = selected.edge;
        long netOutput = edge.netOutput(target);
        if (netOutput <= 0) {
            build.fail("NON_POSITIVE_SELECTED_OUTPUT");
            return -1;
        }

        long stepId = build.nextStepId++;
        long crafts = divideRoundUp(requestedAmount, netOutput);
        build.order.add(stepId);
        build.steps.put(stepId, new RoutePlan.Step<>(stepId, parentStepId, parentInputIndex, target,
                requestedAmount, edge.id(), crafts, depth, Map.of()));

        Map<Integer, RoutePlan.InputChoice<K>> inputChoices = new LinkedHashMap<>();
        Set<K> childAncestors = new HashSet<>(ancestors);
        childAncestors.add(target);
        for (int inputIndex : plannedInputOrder(selected)) {
            if (inputIndex >= selected.choices.size() || inputIndex >= selected.children.size()) {
                build.fail("MISSING_SELECTED_INPUT");
                return stepId;
            }
            RouteModel.Input<K> input = edge.inputs().get(inputIndex);
            RouteModel.Amount<K> choice = selected.choices.get(inputIndex);
            long totalAmount = saturatedMultiply(choice.amount(), crafts);
            RoutePlan.SupplyKind supply;
            Long supplierStepId = null;

            if (context.isFree(choice.key())) {
                supply = RoutePlan.SupplyKind.FREE;
            } else {
                PlanningInventory.Consumption consumed = build.inventory.consume(choice.key(), totalAmount,
                        () -> context.getAvailable(choice.key()));
                long remaining = totalAmount - consumed.total();
                if (remaining <= 0) {
                    supply = inventorySupply(consumed);
                } else if (input.stockOnly()) {
                    build.fail("STOCK_ONLY_INPUT_UNAVAILABLE");
                    return stepId;
                } else if (context.isLeaf(choice.key())) {
                    supply = RoutePlan.SupplyKind.LEAF;
                } else if (childAncestors.contains(choice.key())) {
                    build.fail("PATH_CYCLE");
                    return stepId;
                } else {
                    Node<K> child = selected.children.get(inputIndex);
                    if (child.edge == null) {
                        child = scoreDemand(choice.key(), remaining, depth + 1, childAncestors, false, budget, true);
                    }
                    if (child.edge == null || !child.complete) {
                        build.fail("UNRESOLVED_REPAIR_ROUTE");
                        return stepId;
                    }
                    supplierStepId = appendPlannedStep(child, choice.key(), remaining, stepId, inputIndex,
                            depth + 1, childAncestors, build, budget);
                    if (!build.complete) return stepId;
                    PlanningInventory.Consumption produced = build.inventory.consume(choice.key(), remaining,
                            () -> context.getAvailable(choice.key()));
                    if (produced.total() < remaining) {
                        build.fail("SELECTED_ROUTE_UNDERPRODUCED");
                        return stepId;
                    }
                    supply = consumed.total() > 0 ? RoutePlan.SupplyKind.MIXED : RoutePlan.SupplyKind.RECIPE;
                }
            }
            inputChoices.put(inputIndex, new RoutePlan.InputChoice<>(choice.key(), choice.amount(), supply,
                    supplierStepId));
        }
        build.steps.put(stepId, new RoutePlan.Step<>(stepId, parentStepId, parentInputIndex, target,
                requestedAmount, edge.id(), crafts, depth, inputChoices));

        if (parentStepId != null) {
            for (RouteModel.Amount<K> output : edge.outputs()) {
                build.inventory.addProduced(output.key(), saturatedMultiply(output.amount(), crafts),
                        () -> context.getAvailable(output.key()));
            }
        }
        return stepId;
    }

    /** Processes sibling producers before demands that can consume their co-products. */
    private List<Integer> plannedInputOrder(Node<K> selected) {
        List<Integer> order = new ArrayList<>(selected.edge.inputs().size());
        for (int inputIndex = 0; inputIndex < selected.edge.inputs().size(); inputIndex++) order.add(inputIndex);
        order.sort(Comparator.<Integer>comparingInt(index -> -siblingCoProductScore(selected, index))
                .thenComparingInt(Integer::intValue));
        return order;
    }

    private int siblingCoProductScore(Node<K> selected, int inputIndex) {
        if (inputIndex >= selected.children.size()) return 0;
        Node<K> child = selected.children.get(inputIndex);
        if (child == null || child.edge == null) return 0;
        int score = 0;
        for (RouteModel.Amount<K> output : child.edge.outputs()) {
            for (int siblingIndex = 0; siblingIndex < selected.choices.size(); siblingIndex++) {
                if (siblingIndex != inputIndex && output.key().equals(selected.choices.get(siblingIndex).key())) {
                    score++;
                }
            }
        }
        return score;
    }

    private RoutePlan.SupplyKind inventorySupply(PlanningInventory.Consumption consumed) {
        if (consumed.external() > 0 && consumed.produced() > 0) return RoutePlan.SupplyKind.MIXED;
        return consumed.produced() > 0 ? RoutePlan.SupplyKind.CO_PRODUCT : RoutePlan.SupplyKind.STOCK;
    }

    private Node<K> scoreDemand(K key, long amount, int depth, Set<K> ancestors, boolean stockOnly,
                                CallBudget budget) {
        return scoreDemand(key, amount, depth, ancestors, stockOnly, budget, false);
    }

    private Node<K> scoreDemand(K key, long amount, int depth, Set<K> ancestors, boolean stockOnly,
                                CallBudget budget, boolean ignoreStock) {
        long available = ignoreStock ? 0 : Math.max(0, context.getAvailable(key));
        long fromStock = Math.min(available, amount);
        long remaining = amount - fromStock;
        RouteModel.Cost stockCost = new RouteModel.Cost(0, 0, 0,
                context.estimateMaterialCost(key, fromStock), 0, 0, 0, 0);
        if (remaining <= 0 || context.isFree(key)) return terminal(key, stockCost, true, true);
        if (stockOnly) return terminal(key, stockCost.plus(unresolved(key, remaining, depth, false)), false, true);
        if (context.isLeaf(key)) {
            return terminal(key, stockCost.plus(new RouteModel.Cost(
                    context.estimateMaterialCost(key, remaining), depth, 0, 0, 0, 0, 0, 0)), true, true);
        }
        if (depth >= limits.maxDepth() || ancestors.contains(key)) {
            return terminal(key, stockCost.plus(unresolved(key, remaining, depth,
                    depth >= limits.maxDepth())), false, false);
        }

        MemoKey<K> memoKey = new MemoKey<>(key, remaining, depth);
        Node<K> cached = memo.get(memoKey);
        if (cached != null) return withAddedCost(cached, stockCost);

        ProgressKey<K> progressKey = budget.retainsFrontier() ?
                new ProgressKey<>(key, remaining, depth, ancestors) : null;
        DemandProgress<K> progress = progressKey == null ? null : demandProgress.get(progressKey);
        if (!context.shouldContinue()) {
            budget.sharedBudgetLimited = true;
            return terminal(key, stockCost.plus(unresolved(key, remaining, depth, true)), false, false);
        }
        if (progress == null) {
            if (budget.expansions >= budget.limit) {
                budget.quotaLimited = true;
                return terminal(key, stockCost.plus(unresolved(key, remaining, depth, true)), false, false);
            }
            if (!context.reserveExpansion()) {
                budget.sharedBudgetLimited = true;
                return terminal(key, stockCost.plus(unresolved(key, remaining, depth, true)), false, false);
            }
            budget.expansions++;
            progress = new DemandProgress<>();
            if (budget.retainsFrontier()) {
                List<RouteModel.Edge<K>> edges = new ArrayList<>(producingEdges(key));
                edges.sort((left, right) -> {
                    long leftCrafts = divideRoundUp(remaining, left.netOutput(key));
                    long rightCrafts = divideRoundUp(remaining, right.netOutput(key));
                    int cost = routePolicy.compare(edgeCost(left, leftCrafts, depth + 1),
                            edgeCost(right, rightCrafts, depth + 1));
                    return cost != 0 ? cost : left.id().compareTo(right.id());
                });
                progress.edges = List.copyOf(edges);
            } else {
                progress.edges = producingEdges(key);
            }
            if (progressKey != null) demandProgress.put(progressKey, progress);
        }

        boolean mutablePath = !budget.retainsFrontier();
        Set<K> childAncestors = mutablePath ? ancestors : new HashSet<>(ancestors);
        boolean addedToPath = childAncestors.add(key);
        try {
            while (progress.edgeIndex < progress.edges.size()) {
                RouteModel.Edge<K> edge = progress.edges.get(progress.edgeIndex);
                if (progress.activeEdge == null) {
                    long crafts = divideRoundUp(remaining, edge.netOutput(key));
                    progress.activeEdge = new EdgeProgress<>(key, edge, crafts, depth + 1, childAncestors,
                            edgeCost(edge, crafts, depth + 1), budget.retainsFrontier());
                }
                Node<K> candidate = resumeEdge(progress.activeEdge, budget, progress.best);
                if (candidate == null) {
                    if (progress.activeEdge.dominated) {
                        progress.activeEdge = null;
                        progress.edgeIndex++;
                        budget.boundPrunedEdges++;
                        continue;
                    }
                    return terminal(key, stockCost.plus(unresolved(key, remaining, depth, true)), false, false);
                }
                if (progress.best == null || routePolicy.compare(candidate.cost, progress.best.cost) < 0 ||
                        routePolicy.compare(candidate.cost, progress.best.cost) == 0 &&
                                candidate.edge.id().compareTo(progress.best.edge.id()) < 0) {
                    progress.best = candidate;
                }
                progress.activeEdge = null;
                progress.edgeIndex++;
            }
            Node<K> selected = progress.best;
            if (selected == null) {
                selected = terminal(key, unresolved(key, remaining, depth, false), false, true);
            }
            if (progressKey != null) demandProgress.remove(progressKey);
            Node<K> result = withAddedCost(selected, stockCost);
            if (result.contextIndependent) {
                memo.put(memoKey, withoutStockCost(result, stockCost.consumedStockMaterials()));
            }
            return result;
        } finally {
            if (mutablePath && addedToPath) childAncestors.remove(key);
        }
    }

    private Node<K> scoreEdge(K target, RouteModel.Edge<K> edge, long crafts, int depth,
                              Set<K> ancestors, CallBudget budget) {
        EdgeProgress<K> progress = new EdgeProgress<>(target, edge, crafts, depth, ancestors,
                edgeCost(edge, crafts, depth), budget.retainsFrontier());
        Node<K> result = resumeEdge(progress, budget, null);
        if (result != null) return result;
        RouteModel.Cost pausedCost = progress.cost.plus(new RouteModel.Cost(
                0, depth, 0, 0, 1, 1, 0, 0));
        return new Node<>(target, edge, List.copyOf(progress.choices), List.copyOf(progress.children), pausedCost,
                false, false);
    }

    /** Continues exactly at the input alternative that exhausted the previous grant. */
    private Node<K> resumeEdge(EdgeProgress<K> progress, CallBudget budget, Node<K> incumbent) {
        if (isDominatedBy(incumbent, progress.cost)) {
            progress.dominated = true;
            return null;
        }
        while (progress.inputIndex < progress.edge.inputs().size()) {
            RouteModel.Input<K> input = progress.edge.inputs().get(progress.inputIndex);
            if (progress.alternatives == null) {
                progress.alternatives = orderedAlternatives(input);
            }
            while (progress.alternativeIndex < progress.alternatives.size()) {
                RouteModel.Amount<K> option = progress.alternatives.get(progress.alternativeIndex);
                Node<K> candidate = scoreDemand(option.key(),
                        saturatedMultiply(option.amount(), progress.crafts), progress.depth,
                        progress.ancestors, input.stockOnly(), budget);
                if (budget.quotaLimited || budget.sharedBudgetLimited) return null;
                if (progress.bestAlternative == null ||
                        routePolicy.compare(candidate.cost, progress.bestAlternative.cost) < 0 ||
                        routePolicy.compare(candidate.cost, progress.bestAlternative.cost) == 0 &&
                                graph.stableKey(option.key()).compareTo(
                                        graph.stableKey(progress.bestChoice.key())) < 0) {
                    progress.bestAlternative = candidate;
                    progress.bestChoice = option;
                }
                progress.alternativeIndex++;
            }
            if (progress.bestAlternative == null) {
                progress.cost = progress.cost.plus(new RouteModel.Cost(
                        0, progress.depth, 0, 0, 1, 1, 0, 0));
                progress.complete = false;
                progress.contextIndependent = false;
            } else {
                progress.choices.add(progress.bestChoice);
                progress.children.add(progress.bestAlternative);
                progress.cost = progress.cost.plus(progress.bestAlternative.cost);
                progress.complete &= progress.bestAlternative.complete;
                progress.contextIndependent &= progress.bestAlternative.contextIndependent;
            }
            progress.inputIndex++;
            progress.alternatives = null;
            progress.alternativeIndex = 0;
            progress.bestAlternative = null;
            progress.bestChoice = null;
            if (isDominatedBy(incumbent, progress.cost)) {
                progress.dominated = true;
                return null;
            }
        }
        return new Node<>(progress.target, progress.edge, List.copyOf(progress.choices),
                List.copyOf(progress.children), progress.cost, progress.complete, progress.contextIndependent);
    }

    private boolean isDominatedBy(Node<K> incumbent, RouteModel.Cost lowerBound) {
        return incumbent != null && incumbent.complete &&
                routePolicy.incumbentStrictlyDominates(incumbent.cost, lowerBound);
    }

    /** Immutable per-output edge order reused by the selected winner's non-resumable scoring pass. */
    private List<RouteModel.Edge<K>> producingEdges(K key) {
        return producingEdges.computeIfAbsent(key, ignored -> {
            List<RouteModel.Edge<K>> edges = new ArrayList<>();
            for (RecipeGraphIndex.HyperEdge<K, RouteModel.Edge<K>> hyperEdge : graph.edgesFrom(key)) {
                RouteModel.Edge<K> edge = hyperEdge.value();
                if (edge.netOutput(key) > 0) edges.add(edge);
            }
            edges.sort((left, right) -> {
                int cost = routePolicy.compare(edgeCost(left, 1, 1), edgeCost(right, 1, 1));
                return cost != 0 ? cost : left.id().compareTo(right.id());
            });
            return List.copyOf(edges);
        });
    }

    private List<RouteModel.Amount<K>> orderedAlternatives(RouteModel.Input<K> input) {
        return orderedAlternatives.computeIfAbsent(input, ignored -> {
            List<RouteModel.Amount<K>> alternatives = new ArrayList<>(input.alternatives());
            alternatives.sort(Comparator.comparing(option -> graph.stableKey(option.key())));
            int alternativeLimit = Math.min(alternatives.size(), limits.maxInputAlternatives());
            return List.copyOf(alternatives.subList(0, alternativeLimit));
        });
    }

    private Node<K> terminal(K key, RouteModel.Cost cost, boolean complete, boolean contextIndependent) {
        return new Node<>(key, null, List.of(), List.of(), cost, complete, contextIndependent);
    }

    private Node<K> withAddedCost(Node<K> node, RouteModel.Cost added) {
        if (added.equals(RouteModel.Cost.ZERO)) return node;
        return new Node<>(node.target, node.edge, node.choices, node.children, node.cost.plus(added), node.complete,
                node.contextIndependent);
    }

    private RouteModel.Cost unresolved(K key, long amount, int depth, boolean bounded) {
        return new RouteModel.Cost(context.estimateMaterialCost(key, amount), depth, 0, 0,
                bounded ? 1 : 0, 1, 0, 0);
    }

    private static <K> RouteModel.Cost edgeCost(RouteModel.Edge<K> edge, long crafts, int depth) {
        return new RouteModel.Cost(0, depth, saturatedMultiply(edge.executionsCost(), crafts), 0, 0, 0,
                saturatedMultiply(edge.cycleRisk(), crafts),
                saturatedMultiply(edge.materialFormConversions(), crafts));
    }

    private Node<K> withoutStockCost(Node<K> node, long stockCost) {
        if (stockCost == 0) return node;
        RouteModel.Cost cost = node.cost;
        RouteModel.Cost stripped = new RouteModel.Cost(cost.missingMaterials(), cost.maxDepth(),
                cost.executions(), Math.max(0, cost.consumedStockMaterials() - stockCost),
                cost.boundedFallbacks(), cost.unresolvedIntermediates(), cost.cycleRisk(),
                cost.materialFormConversions());
        return new Node<>(node.target, node.edge, node.choices, node.children, stripped, node.complete,
                node.contextIndependent);
    }

    private int selectedTargetCount(Node<K> root) {
        Set<K> targets = new HashSet<>();
        collectSelectedTargets(root, targets,
                Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return targets.size();
    }

    private void collectSelectedTargets(Node<K> node, Set<K> targets, Set<Node<K>> visited) {
        if (node == null || node.edge == null || !visited.add(node)) return;
        targets.add(node.target);
        for (Node<K> child : node.children) collectSelectedTargets(child, targets, visited);
    }

    private static long divideRoundUp(long value, long divisor) {
        if (value <= 0) return 0;
        return 1 + (value - 1) / divisor;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }
}
