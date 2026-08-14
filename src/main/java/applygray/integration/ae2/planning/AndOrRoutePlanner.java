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
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic, bounded best-first planner for recipe AND/OR hypergraphs.
 *
 * <p>An output key is an OR node whose alternatives are recipe edges. Every recipe is an AND node: one choice from
 * each input group must be satisfied. Inventory, co-products and input alternatives are part of the search state, so
 * choosing an input never commits a greedy mutation that can make the result depend on recipe input order.</p>
 */
public final class AndOrRoutePlanner<K> {

    public enum Status {
        COMPLETE,
        DEGRADED,
        REJECTED
    }

    public interface Graph<K> {

        long getAvailable(K key);

        boolean isLeaf(K key);

        boolean isFree(K key);

        long estimateMaterialCost(K key, long amount);

        List<Edge<K>> getEdges(K key, int depth);

        String stableKey(K key);

        boolean reserveExpansion();

        boolean shouldContinue();
    }

    public record Amount<K>(K key, long amount) {

        public Amount {
            Objects.requireNonNull(key, "key");
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        }
    }

    public static final class Input<K> {

        private final List<Amount<K>> alternatives;

        public Input(List<Amount<K>> alternatives) {
            this.alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        public List<Amount<K>> alternatives() {
            return alternatives;
        }
    }

    public static final class Edge<K> {

        private final String id;
        private final List<Input<K>> inputs;
        private final List<Amount<K>> outputs;
        private final long executionsCost;
        private final long cycleRisk;
        private final long materialFormConversions;

        public Edge(String id, List<Input<K>> inputs, List<Amount<K>> outputs, long executionsCost,
                    long cycleRisk, long materialFormConversions) {
            this.id = Objects.requireNonNull(id, "id");
            this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
            this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
            this.executionsCost = Math.max(0, executionsCost);
            this.cycleRisk = Math.max(0, cycleRisk);
            this.materialFormConversions = Math.max(0, materialFormConversions);
        }

        public String id() {
            return id;
        }

        public List<Input<K>> inputs() {
            return inputs;
        }

        public List<Amount<K>> outputs() {
            return outputs;
        }

        public long netOutput(K target) {
            long produced = 0;
            for (Amount<K> output : outputs) {
                if (target.equals(output.key())) produced = saturatedAdd(produced, output.amount());
            }
            long consumed = 0;
            for (Input<K> input : inputs) {
                long largestMatchingInput = 0;
                for (Amount<K> alternative : input.alternatives()) {
                    if (target.equals(alternative.key())) {
                        largestMatchingInput = Math.max(largestMatchingInput, alternative.amount());
                    }
                }
                consumed = saturatedAdd(consumed, largestMatchingInput);
            }
            return consumed >= produced ? 0 : produced - consumed;
        }
    }

    public record Limits(int maxExpansions, int maxDepth, int maxInputAlternatives) {

        public Limits {
            if (maxExpansions <= 0) throw new IllegalArgumentException("maxExpansions must be positive");
            if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be positive");
            if (maxInputAlternatives <= 0) {
                throw new IllegalArgumentException("maxInputAlternatives must be positive");
            }
        }
    }

    public record Cost(long missingMaterials, int maxDepth, long executions, long consumedStockMaterials,
                       int boundedFallbacks, int unresolvedIntermediates, long cycleRisk,
                       long materialFormConversions) implements Comparable<Cost> {

        public static final Cost ZERO = new Cost(0, 0, 0, 0, 0, 0, 0, 0);

        public Cost plus(Cost other) {
            return new Cost(saturatedAdd(missingMaterials, other.missingMaterials),
                    Math.max(maxDepth, other.maxDepth), saturatedAdd(executions, other.executions),
                    saturatedAdd(consumedStockMaterials, other.consumedStockMaterials),
                    saturatedIntAdd(boundedFallbacks, other.boundedFallbacks),
                    saturatedIntAdd(unresolvedIntermediates, other.unresolvedIntermediates),
                    saturatedAdd(cycleRisk, other.cycleRisk),
                    saturatedAdd(materialFormConversions, other.materialFormConversions));
        }

        @Override
        public int compareTo(Cost other) {
            int result = Integer.compare(boundedFallbacks, other.boundedFallbacks);
            if (result != 0) return result;
            result = Integer.compare(unresolvedIntermediates, other.unresolvedIntermediates);
            if (result != 0) return result;
            result = Long.compare(cycleRisk, other.cycleRisk);
            if (result != 0) return result;
            result = Long.compare(materialFormConversions, other.materialFormConversions);
            if (result != 0) return result;
            result = Long.compare(missingMaterials, other.missingMaterials);
            if (result != 0) return result;
            result = Integer.compare(maxDepth, other.maxDepth);
            if (result != 0) return result;
            result = Long.compare(executions, other.executions);
            if (result != 0) return result;
            return Long.compare(consumedStockMaterials, other.consumedStockMaterials);
        }
    }

    public record Result<K>(Status status, Cost cost, List<Amount<K>> rootInputChoices,
                            Map<K, String> selectedRoutes, List<String> selectedEdges,
                            int expansions, String reasonCode) {

        public Result {
            rootInputChoices = Collections.unmodifiableList(new ArrayList<>(rootInputChoices));
            selectedRoutes = Collections.unmodifiableMap(new LinkedHashMap<>(selectedRoutes));
            selectedEdges = List.copyOf(selectedEdges);
        }

        public boolean isComplete() {
            return status == Status.COMPLETE;
        }
    }

    private sealed interface Task<K> permits ChoiceTask, DemandTask {

        int depth();

        String stableId(Graph<K> graph);
    }

    private record DemandTask<K>(K key, long amount, int depth, Set<K> ancestors) implements Task<K> {

        @Override
        public String stableId(Graph<K> graph) {
            List<String> path = new ArrayList<>(ancestors.size());
            for (K ancestor : ancestors) path.add(graph.stableKey(ancestor));
            Collections.sort(path);
            return "D:" + graph.stableKey(key) + ':' + amount + ':' + depth + ':' + path;
        }
    }

    private record ChoiceTask<K>(String edgeId, int inputIndex, Input<K> input, long crafts, int depth,
                                 Set<K> ancestors, boolean rootInput) implements Task<K> {

        @Override
        public String stableId(Graph<K> graph) {
            List<String> path = new ArrayList<>(ancestors.size());
            for (K ancestor : ancestors) path.add(graph.stableKey(ancestor));
            Collections.sort(path);
            return "C:" + edgeId + ':' + inputIndex + ':' + crafts + ':' + depth + ':' + path;
        }
    }

    private static final class SearchState<K> {

        private final List<Task<K>> tasks;
        private final Map<K, Long> inventory;
        private final Cost cost;
        private final Map<Integer, Amount<K>> rootChoices;
        private final Map<K, String> selectedRoutes;
        private final List<String> selectedEdges;
        private final long sequence;

        private SearchState(List<Task<K>> tasks, Map<K, Long> inventory, Cost cost,
                            Map<Integer, Amount<K>> rootChoices,
                            Map<K, String> selectedRoutes, List<String> selectedEdges, long sequence) {
            this.tasks = tasks;
            this.inventory = inventory;
            this.cost = cost;
            this.rootChoices = rootChoices;
            this.selectedRoutes = selectedRoutes;
            this.selectedEdges = selectedEdges;
            this.sequence = sequence;
        }
    }

    public Result<K> plan(Edge<K> root, K target, Graph<K> graph, Limits limits) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(limits, "limits");

        if (root.netOutput(target) <= 0) {
            return new Result<>(Status.REJECTED, Cost.ZERO, List.of(), Map.of(), List.of(), 0,
                    "NON_POSITIVE_ROOT_OUTPUT");
        }

        long sequence = 0;
        SearchState<K> initial = applyRoot(root, target, graph, sequence++);
        Comparator<SearchState<K>> stateOrder = Comparator
                .comparing((SearchState<K> state) -> state.cost)
                .thenComparingInt(state -> state.tasks.size())
                .thenComparing(state -> stateSignature(state, graph))
                .thenComparingLong(state -> state.sequence);
        PriorityQueue<SearchState<K>> frontier = new PriorityQueue<>(stateOrder);
        frontier.add(initial);
        Map<String, Cost> visited = new HashMap<>();
        SearchState<K> bestPartial = initial;
        int expansions = 0;
        String exhaustionReason = "NO_COMPLETE_ROUTE";

        while (!frontier.isEmpty()) {
            SearchState<K> state = frontier.poll();
            if (isBetterPartial(state, bestPartial)) bestPartial = state;
            if (state.tasks.isEmpty()) {
                Status status = state.cost.boundedFallbacks() == 0 && state.cost.unresolvedIntermediates() == 0 ?
                        Status.COMPLETE : Status.DEGRADED;
                return result(status, state, expansions, status == Status.COMPLETE ? "OK" : "UNRESOLVED_ROUTE");
            }
            if (expansions >= limits.maxExpansions()) {
                exhaustionReason = "EXPANSION_LIMIT";
                break;
            }
            if (!graph.shouldContinue()) {
                exhaustionReason = "DEADLINE_OR_CANCELLED";
                break;
            }

            String signature = stateSignature(state, graph);
            Cost previous = visited.get(signature);
            if (previous != null && previous.compareTo(state.cost) <= 0) continue;
            visited.put(signature, state.cost);

            Task<K> task = firstTask(state.tasks, graph);
            List<Task<K>> remainingTasks = removeIdentity(state.tasks, task);
            if (task instanceof ChoiceTask<K> choice) {
                int alternativeLimit = Math.min(choice.input().alternatives().size(), limits.maxInputAlternatives());
                List<Amount<K>> alternatives = new ArrayList<>(choice.input().alternatives());
                alternatives.sort(Comparator.comparing(amount -> graph.stableKey(amount.key())));
                for (int index = 0; index < alternativeLimit; index++) {
                    Amount<K> alternative = alternatives.get(index);
                    long amount = saturatedMultiply(alternative.amount(), choice.crafts());
                    List<Task<K>> tasks = new ArrayList<>(remainingTasks);
                    tasks.add(new DemandTask<>(alternative.key(), amount, choice.depth(), choice.ancestors()));
                    Map<Integer, Amount<K>> rootChoices = new HashMap<>(state.rootChoices);
                    if (choice.rootInput()) rootChoices.put(choice.inputIndex(), alternative);
                    frontier.add(new SearchState<>(tasks, new HashMap<>(state.inventory), state.cost, rootChoices,
                            state.selectedRoutes, state.selectedEdges, sequence++));
                }
                expansions++;
                continue;
            }

            DemandTask<K> demand = (DemandTask<K>) task;
            Consumption<K> consumption = consumeInventory(state, remainingTasks, demand, graph, sequence++);
            SearchState<K> consumed = consumption.state();
            long remaining = demand.amount() - consumption.consumed();
            if (remaining <= 0 || graph.isFree(demand.key())) {
                frontier.add(consumed);
                expansions++;
                continue;
            }
            if (graph.isLeaf(demand.key())) {
                Cost missing = new Cost(graph.estimateMaterialCost(demand.key(), remaining), demand.depth(),
                        0, 0, 0, 0, 0, 0);
                frontier.add(withCost(consumed, consumed.cost.plus(missing), sequence++));
                expansions++;
                continue;
            }
            if (demand.depth() >= limits.maxDepth() || demand.ancestors().contains(demand.key())) {
                Cost unresolved = unresolvedCost(demand, remaining, graph,
                        demand.depth() >= limits.maxDepth());
                frontier.add(withCost(consumed, consumed.cost.plus(unresolved), sequence++));
                expansions++;
                continue;
            }
            if (!graph.reserveExpansion()) {
                exhaustionReason = "SHARED_BUDGET_EXHAUSTED";
                bestPartial = consumed;
                break;
            }

            List<Edge<K>> edges = new ArrayList<>(graph.getEdges(demand.key(), demand.depth()));
            edges.sort(Comparator.comparing(Edge::id));
            boolean expanded = false;
            for (Edge<K> edge : edges) {
                long netOutput = edge.netOutput(demand.key());
                if (netOutput <= 0) continue;
                long crafts = divideRoundUp(remaining, netOutput);
                frontier.add(applyEdge(consumed, demand, remaining, edge, crafts, graph, sequence++));
                expanded = true;
            }
            if (!expanded) {
                Cost unresolved = unresolvedCost(demand, remaining, graph, false);
                frontier.add(withCost(consumed, consumed.cost.plus(unresolved), sequence++));
            }
            expansions++;
        }

        return result(Status.DEGRADED, degrade(bestPartial, graph, sequence), expansions, exhaustionReason);
    }

    private SearchState<K> applyRoot(Edge<K> root, K target, Graph<K> graph, long sequence) {
        List<Task<K>> tasks = new ArrayList<>(root.inputs().size());
        Set<K> ancestors = Set.of(target);
        for (int index = 0; index < root.inputs().size(); index++) {
            tasks.add(new ChoiceTask<>(root.id(), index, root.inputs().get(index), 1, 1, ancestors, true));
        }
        Cost rootCost = edgeCost(root, 1, 1);
        return new SearchState<>(tasks, new HashMap<>(), rootCost, new HashMap<>(), Map.of(target, root.id()),
                List.of(root.id()), sequence);
    }

    private SearchState<K> applyEdge(SearchState<K> state, DemandTask<K> demand, long remaining, Edge<K> edge,
                                     long crafts, Graph<K> graph, long sequence) {
        Map<K, Long> inventory = new HashMap<>(state.inventory);
        Set<K> inputKeys = new HashSet<>();
        for (Input<K> input : edge.inputs()) {
            for (Amount<K> alternative : input.alternatives()) inputKeys.add(alternative.key());
        }
        for (Amount<K> output : edge.outputs()) {
            // Outputs that can satisfy this edge's own input are not available until the edge has executed. Publishing
            // them now would allow an unseeded recipe to bootstrap itself. Independent co-products remain reusable.
            if (!inputKeys.contains(output.key())) {
                addInventory(inventory, output.key(), saturatedMultiply(output.amount(), crafts), graph);
            }
        }
        consume(inventory, demand.key(), remaining, graph);

        Set<K> ancestors = new HashSet<>(demand.ancestors());
        ancestors.add(demand.key());
        Set<K> immutableAncestors = Collections.unmodifiableSet(ancestors);
        List<Task<K>> tasks = new ArrayList<>(state.tasks);
        for (int index = 0; index < edge.inputs().size(); index++) {
            tasks.add(new ChoiceTask<>(edge.id(), index, edge.inputs().get(index), crafts, demand.depth() + 1,
                    immutableAncestors, false));
        }
        List<String> selectedEdges = new ArrayList<>(state.selectedEdges);
        selectedEdges.add(edge.id());
        Map<K, String> selectedRoutes = new HashMap<>(state.selectedRoutes);
        selectedRoutes.putIfAbsent(demand.key(), edge.id());
        return new SearchState<>(tasks, inventory, state.cost.plus(edgeCost(edge, crafts, demand.depth() + 1)),
                state.rootChoices, Collections.unmodifiableMap(selectedRoutes),
                Collections.unmodifiableList(selectedEdges), sequence);
    }

    private record Consumption<K>(SearchState<K> state, long consumed) {}

    private Consumption<K> consumeInventory(SearchState<K> state, List<Task<K>> tasks, DemandTask<K> demand,
                                             Graph<K> graph, long sequence) {
        Map<K, Long> inventory = new HashMap<>(state.inventory);
        long consumed = consume(inventory, demand.key(), demand.amount(), graph);
        Cost stock = new Cost(0, 0, 0, graph.estimateMaterialCost(demand.key(), consumed), 0, 0, 0, 0);
        return new Consumption<>(new SearchState<>(tasks, inventory, state.cost.plus(stock), state.rootChoices,
                state.selectedRoutes, state.selectedEdges, sequence), consumed);
    }

    private SearchState<K> degrade(SearchState<K> state, Graph<K> graph, long sequence) {
        Cost cost = state.cost;
        for (Task<K> task : state.tasks) {
            if (task instanceof DemandTask<K> demand) {
                cost = cost.plus(unresolvedCost(demand, demand.amount(), graph, true));
            } else {
                cost = cost.plus(new Cost(0, task.depth(), 0, 0, 1, 1, 0, 0));
            }
        }
        return new SearchState<>(List.of(), state.inventory, cost, state.rootChoices, state.selectedRoutes,
                state.selectedEdges, sequence);
    }

    private Cost unresolvedCost(DemandTask<K> demand, long amount, Graph<K> graph, boolean bounded) {
        return new Cost(graph.estimateMaterialCost(demand.key(), amount), demand.depth(), 0, 0,
                bounded ? 1 : 0, 1, 0, 0);
    }

    private Cost edgeCost(Edge<K> edge, long crafts, int depth) {
        return new Cost(0, depth, saturatedMultiply(edge.executionsCost, crafts), 0, 0, 0,
                saturatedMultiply(edge.cycleRisk, crafts),
                saturatedMultiply(edge.materialFormConversions, crafts));
    }

    private Result<K> result(Status status, SearchState<K> state, int expansions, String reasonCode) {
        int choiceCount = state.rootChoices.isEmpty() ? 0 : Collections.max(state.rootChoices.keySet()) + 1;
        List<Amount<K>> choices = new ArrayList<>(Collections.nCopies(choiceCount, null));
        state.rootChoices.forEach(choices::set);
        return new Result<>(status, state.cost, choices, state.selectedRoutes, state.selectedEdges, expansions,
                reasonCode);
    }

    private boolean isBetterPartial(SearchState<K> candidate, SearchState<K> current) {
        if (candidate.tasks.size() != current.tasks.size()) return candidate.tasks.size() < current.tasks.size();
        return candidate.cost.compareTo(current.cost) < 0;
    }

    private Task<K> firstTask(List<Task<K>> tasks, Graph<K> graph) {
        return tasks.stream().min(Comparator
                .comparingInt((Task<K> task) -> task instanceof ChoiceTask<?> ? 0 : 1)
                .thenComparing(task -> task.stableId(graph))).orElseThrow();
    }

    private List<Task<K>> removeIdentity(List<Task<K>> tasks, Task<K> selected) {
        List<Task<K>> result = new ArrayList<>(tasks.size() - 1);
        boolean removed = false;
        for (Task<K> task : tasks) {
            if (!removed && task == selected) {
                removed = true;
            } else {
                result.add(task);
            }
        }
        return result;
    }

    private SearchState<K> withCost(SearchState<K> state, Cost cost, long sequence) {
        return new SearchState<>(state.tasks, state.inventory, cost, state.rootChoices, state.selectedRoutes,
                state.selectedEdges, sequence);
    }

    private String stateSignature(SearchState<K> state, Graph<K> graph) {
        List<String> tasks = new ArrayList<>(state.tasks.size());
        for (Task<K> task : state.tasks) tasks.add(task.stableId(graph));
        Collections.sort(tasks);
        List<String> inventory = new ArrayList<>(state.inventory.size());
        state.inventory.forEach((key, amount) -> inventory.add(graph.stableKey(key) + '=' + amount));
        Collections.sort(inventory);
        Map<Integer, String> choices = new LinkedHashMap<>();
        state.rootChoices.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> choices.put(entry.getKey(), graph.stableKey(entry.getValue().key()) + ':' +
                        entry.getValue().amount()));
        List<String> routes = new ArrayList<>(state.selectedRoutes.size());
        state.selectedRoutes.forEach((key, edge) -> routes.add(graph.stableKey(key) + '=' + edge));
        Collections.sort(routes);
        return tasks + "|i=" + inventory + "|r=" + choices + "|p=" + routes;
    }

    private long consume(Map<K, Long> inventory, K key, long amount, Graph<K> graph) {
        if (amount <= 0) return 0;
        long available = inventory.getOrDefault(key, Math.max(0, graph.getAvailable(key)));
        long consumed = Math.min(available, amount);
        inventory.put(key, available - consumed);
        return consumed;
    }

    private void addInventory(Map<K, Long> inventory, K key, long amount, Graph<K> graph) {
        if (amount <= 0) return;
        long available = inventory.getOrDefault(key, Math.max(0, graph.getAvailable(key)));
        inventory.put(key, saturatedAdd(available, amount));
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

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static int saturatedIntAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        return left + right;
    }
}
