package applygray.integration.ae2.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Immutable deterministic index of recipe hyperedges. */
public final class RecipeGraphIndex<K, E> {

    @FunctionalInterface
    public interface EdgeSource<K, E> {

        List<HyperEdge<K, E>> getEdges(K output, int depth);
    }

    public record CaptureLimits(int maxNodes, int maxEdges, int maxDepth) {

        public CaptureLimits {
            if (maxNodes <= 0) throw new IllegalArgumentException("maxNodes must be positive");
            if (maxEdges <= 0) throw new IllegalArgumentException("maxEdges must be positive");
            if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be positive");
        }
    }

    public record CaptureResult<K, E>(RecipeGraphIndex<K, E> index, boolean complete, String reasonCode,
                                      int expandedNodes) {}

    public record HyperEdge<K, E>(String id, E value, List<List<K>> inputGroups) {

        public HyperEdge {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");
            List<List<K>> copied = new ArrayList<>();
            for (List<K> group : inputGroups) copied.add(List.copyOf(group));
            inputGroups = Collections.unmodifiableList(copied);
        }

        public Set<K> dependencies() {
            Set<K> dependencies = new LinkedHashSet<>();
            for (List<K> group : inputGroups) dependencies.addAll(group);
            return Collections.unmodifiableSet(dependencies);
        }
    }

    private final Map<K, List<HyperEdge<K, E>>> edgesByOutput;
    private final Map<K, String> stableKeys;

    private RecipeGraphIndex(Map<K, List<HyperEdge<K, E>>> edgesByOutput, Map<K, String> stableKeys) {
        this.edgesByOutput = edgesByOutput;
        this.stableKeys = stableKeys;
    }

    public Set<K> nodes() {
        return stableKeys.keySet();
    }

    public List<HyperEdge<K, E>> edgesFrom(K output) {
        return edgesByOutput.getOrDefault(output, List.of());
    }

    public String stableKey(K key) {
        String stable = stableKeys.get(key);
        if (stable == null) throw new IllegalArgumentException("key is not indexed: " + key);
        return stable;
    }

    public int edgeCount() {
        int count = 0;
        for (List<HyperEdge<K, E>> edges : edgesByOutput.values()) count += edges.size();
        return count;
    }

    public static <K, E> CaptureResult<K, E> capture(Set<K> roots, Function<K, String> stableKey,
                                                      Predicate<K> terminal, EdgeSource<K, E> source,
                                                      BooleanSupplier shouldContinue, CaptureLimits limits) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(stableKey, "stableKey");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(shouldContinue, "shouldContinue");
        Objects.requireNonNull(limits, "limits");

        Map<K, String> capturedStableKeys = new LinkedHashMap<>();
        Function<K, String> cachedStableKey = key ->
                capturedStableKeys.computeIfAbsent(key, stableKey);
        Builder<K, E> builder = new Builder<>(cachedStableKey);
        Map<K, Integer> depths = new LinkedHashMap<>();
        Comparator<K> pendingOrder = Comparator.comparingInt((K key) -> depths.getOrDefault(key, 0))
                .thenComparing(cachedStableKey);
        PriorityQueue<K> pending = new PriorityQueue<>(pendingOrder);
        List<K> orderedRoots = new ArrayList<>(roots);
        orderedRoots.sort(Comparator.comparing(cachedStableKey));
        boolean complete = true;
        String reasonCode = "OK";
        for (K root : orderedRoots) {
            if (depths.size() >= limits.maxNodes()) {
                complete = false;
                reasonCode = "GRAPH_NODE_LIMIT";
                break;
            }
            builder.addNode(root);
            depths.put(root, 0);
            pending.add(root);
        }
        Set<K> expanded = new LinkedHashSet<>();
        int edgeCount = 0;

        while (complete && !pending.isEmpty()) {
            K output = pending.poll();
            int depth = depths.getOrDefault(output, 0);
            if (!expanded.add(output) || terminal.test(output) || depth >= limits.maxDepth()) continue;
            if (!shouldContinue.getAsBoolean()) {
                complete = false;
                reasonCode = "GRAPH_DEADLINE_OR_CANCELLED";
                break;
            }

            List<HyperEdge<K, E>> edges = new ArrayList<>(source.getEdges(output, depth));
            edges.sort(Comparator.comparing(HyperEdge::id));
            for (HyperEdge<K, E> edge : edges) {
                if (edgeCount >= limits.maxEdges()) {
                    complete = false;
                    reasonCode = "GRAPH_EDGE_LIMIT";
                    break;
                }
                Set<K> dependencies = edge.dependencies();
                Set<K> newDependencies = new LinkedHashSet<>(dependencies);
                newDependencies.removeAll(depths.keySet());
                if (depths.size() + newDependencies.size() > limits.maxNodes()) {
                    complete = false;
                    reasonCode = "GRAPH_NODE_LIMIT";
                    break;
                }
                builder.addEdge(output, edge.id(), edge.value(), edge.inputGroups());
                edgeCount++;
                List<K> orderedDependencies = new ArrayList<>(dependencies);
                orderedDependencies.sort(Comparator.comparing(cachedStableKey));
                for (K dependency : orderedDependencies) {
                    if (depths.putIfAbsent(dependency, depth + 1) == null) {
                        builder.addNode(dependency);
                        pending.add(dependency);
                    }
                }
            }
            if (!complete) break;
        }
        return new CaptureResult<>(builder.build(), complete, reasonCode, expanded.size());
    }

    public static final class Builder<K, E> {

        private final Function<K, String> stableKey;
        private final Map<K, List<HyperEdge<K, E>>> edgesByOutput = new LinkedHashMap<>();
        private final Set<K> nodes = new LinkedHashSet<>();

        public Builder(Function<K, String> stableKey) {
            this.stableKey = Objects.requireNonNull(stableKey, "stableKey");
        }

        public Builder<K, E> addNode(K key) {
            nodes.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder<K, E> addEdge(K output, String id, E value, List<List<K>> inputGroups) {
            Objects.requireNonNull(output, "output");
            HyperEdge<K, E> edge = new HyperEdge<>(id, value, inputGroups == null ? List.of() : inputGroups);
            nodes.add(output);
            nodes.addAll(edge.dependencies());
            edgesByOutput.computeIfAbsent(output, ignored -> new ArrayList<>()).add(edge);
            return this;
        }

        public RecipeGraphIndex<K, E> build() {
            List<K> orderedNodes = new ArrayList<>(nodes);
            orderedNodes.sort(Comparator.comparing(stableKey));
            Map<K, String> keys = new LinkedHashMap<>();
            Map<K, List<HyperEdge<K, E>>> edges = new LinkedHashMap<>();
            for (K node : orderedNodes) {
                keys.put(node, stableKey.apply(node));
                List<HyperEdge<K, E>> orderedEdges = new ArrayList<>();
                for (HyperEdge<K, E> edge : edgesByOutput.getOrDefault(node, List.of())) {
                    List<List<K>> orderedGroups = new ArrayList<>(edge.inputGroups().size());
                    for (List<K> group : edge.inputGroups()) {
                        List<K> alternatives = new ArrayList<>(new LinkedHashSet<>(group));
                        alternatives.sort(Comparator.comparing(stableKey));
                        orderedGroups.add(alternatives);
                    }
                    orderedEdges.add(new HyperEdge<>(edge.id(), edge.value(), orderedGroups));
                }
                orderedEdges.sort(Comparator.comparing(HyperEdge::id));
                edges.put(node, Collections.unmodifiableList(orderedEdges));
            }
            return new RecipeGraphIndex<>(Collections.unmodifiableMap(edges), Collections.unmodifiableMap(keys));
        }
    }
}
