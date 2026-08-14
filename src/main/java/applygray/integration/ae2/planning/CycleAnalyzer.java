package applygray.integration.ae2.planning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded SCC and external-seed analysis over an immutable recipe hypergraph. */
public final class CycleAnalyzer<K, E> {

    public interface SeedPolicy<K, E> {

        boolean isDirectSeed(K key);

        boolean isSeedEdge(K output, RecipeGraphIndex.HyperEdge<K, E> edge);

        default boolean canUseEdge(K output, RecipeGraphIndex.HyperEdge<K, E> edge) {
            return true;
        }

        default boolean canStartEdge(K output, RecipeGraphIndex.HyperEdge<K, E> edge) {
            return true;
        }
    }

    public record Limits(int maxNodes, int maxEdges, long maxNanos) {

        public Limits {
            if (maxNodes <= 0) throw new IllegalArgumentException("maxNodes must be positive");
            if (maxEdges <= 0) throw new IllegalArgumentException("maxEdges must be positive");
            if (maxNanos <= 0) throw new IllegalArgumentException("maxNanos must be positive");
        }
    }

    public static final class Result<K, E> {

        private final Analysis<K, E> analysis;

        private Result(Analysis<K, E> analysis) {
            this.analysis = analysis;
        }

        public boolean isComplete() {
            return analysis.complete;
        }

        public String reasonCode() {
            return analysis.reasonCode;
        }

        public int nodeCount() {
            return analysis.nodes.size();
        }

        public int edgeCount() {
            return analysis.edgeCount;
        }

        public boolean contains(K key) {
            return analysis.complete && analysis.nodes.containsKey(key);
        }

        public boolean isCyclic(K key) {
            Node<K, E> node = analysis.nodes.get(key);
            return analysis.complete && node != null && node.component != null && node.component.cyclic;
        }

        public boolean canReachSeed(K key) {
            Node<K, E> node = analysis.nodes.get(key);
            return analysis.complete && node != null && node.component != null &&
                    Boolean.TRUE.equals(node.component.reachesSeed);
        }

        public boolean sameComponent(K left, K right) {
            Node<K, E> leftNode = analysis.nodes.get(left);
            Node<K, E> rightNode = analysis.nodes.get(right);
            return analysis.complete && leftNode != null && leftNode.component != null && rightNode != null &&
                    leftNode.component == rightNode.component;
        }

        public boolean closesCycle(K output, RecipeGraphIndex.HyperEdge<K, E> edge) {
            if (!analysis.complete) return false;
            for (List<K> group : edge.inputGroups()) {
                for (K dependency : group) {
                    if (output.equals(dependency)) return true;
                    Node<K, E> dependencyNode = analysis.nodes.get(dependency);
                    if (dependencyNode != null && analysis.reaches(dependencyNode, output, new HashSet<>())) {
                        return true;
                    }
                }
            }
            return false;
        }

        public boolean edgeCanReachSeed(K output, RecipeGraphIndex.HyperEdge<K, E> edge) {
            if (!analysis.complete) return false;
            Node<K, E> outputNode = analysis.nodes.get(output);
            Component<K, E> component = outputNode == null ? null : outputNode.component;
            return analysis.edgeCanStart(output, edge, component, new HashSet<>());
        }
    }

    public Result<K, E> analyze(K root, RecipeGraphIndex<K, E> index, SeedPolicy<K, E> seedPolicy,
                                Limits limits) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(seedPolicy, "seedPolicy");
        Objects.requireNonNull(limits, "limits");
        Analysis<K, E> analysis = new Analysis<>(index, seedPolicy, limits);
        analysis.collect(root);
        if (analysis.complete) analysis.buildComponents();
        return new Result<>(analysis);
    }

    private static final class Analysis<K, E> {

        private final RecipeGraphIndex<K, E> index;
        private final SeedPolicy<K, E> seedPolicy;
        private final Limits limits;
        private final long deadlineNanos;
        private final Map<K, Node<K, E>> nodes = new HashMap<>();
        private final Deque<Node<K, E>> tarjanStack = new ArrayDeque<>();
        private boolean complete = true;
        private String reasonCode = "OK";
        private int edgeCount;
        private int nextTarjanIndex;

        private Analysis(RecipeGraphIndex<K, E> index, SeedPolicy<K, E> seedPolicy, Limits limits) {
            this.index = index;
            this.seedPolicy = seedPolicy;
            this.limits = limits;
            long now = System.nanoTime();
            deadlineNanos = Long.MAX_VALUE - now < limits.maxNanos() ? Long.MAX_VALUE : now + limits.maxNanos();
        }

        private void collect(K key) {
            if (!complete || nodes.containsKey(key)) return;
            if (nodes.size() >= limits.maxNodes()) {
                exhaust("SCC_NODE_LIMIT");
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                exhaust("SCC_TIME_LIMIT");
                return;
            }
            Node<K, E> node = new Node<>(key);
            nodes.put(key, node);
            node.directSeed = seedPolicy.isDirectSeed(key);
            if (node.directSeed) return;

            for (RecipeGraphIndex.HyperEdge<K, E> edge : index.edgesFrom(key)) {
                if (++edgeCount > limits.maxEdges()) {
                    exhaust("SCC_EDGE_LIMIT");
                    return;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    exhaust("SCC_TIME_LIMIT");
                    return;
                }
                if (!seedPolicy.canUseEdge(key, edge)) continue;
                node.edges.add(edge);
                if (seedPolicy.isSeedEdge(key, edge)) {
                    node.directSeed = true;
                    continue;
                }
                for (K dependency : edge.dependencies()) {
                    node.dependencies.add(dependency);
                    collect(dependency);
                    if (!complete) return;
                }
            }
        }

        private void exhaust(String reason) {
            complete = false;
            reasonCode = reason;
        }

        private void buildComponents() {
            List<Node<K, E>> ordered = new ArrayList<>(nodes.values());
            ordered.sort(java.util.Comparator.comparing(node -> index.stableKey(node.key)));
            for (Node<K, E> node : ordered) {
                if (node.tarjanIndex < 0) strongConnect(node);
            }
            for (Node<K, E> node : ordered) {
                if (node.directSeed) node.component.directSeed = true;
                for (K dependencyKey : node.dependencies) {
                    Node<K, E> dependency = nodes.get(dependencyKey);
                    if (dependency == null || dependency.component == null) continue;
                    if (dependency.component == node.component) {
                        node.component.cyclic |= dependency == node;
                    }
                }
            }
            for (Node<K, E> node : ordered) canReachSeed(node.component, new HashSet<>());
        }

        private void strongConnect(Node<K, E> node) {
            node.tarjanIndex = nextTarjanIndex;
            node.lowLink = nextTarjanIndex++;
            tarjanStack.push(node);
            node.onTarjanStack = true;
            List<K> dependencies = new ArrayList<>(node.dependencies);
            dependencies.sort(java.util.Comparator.comparing(index::stableKey));
            for (K dependencyKey : dependencies) {
                Node<K, E> dependency = nodes.get(dependencyKey);
                if (dependency == null) continue;
                if (dependency.tarjanIndex < 0) {
                    strongConnect(dependency);
                    node.lowLink = Math.min(node.lowLink, dependency.lowLink);
                } else if (dependency.onTarjanStack) {
                    node.lowLink = Math.min(node.lowLink, dependency.tarjanIndex);
                }
            }
            if (node.lowLink != node.tarjanIndex) return;
            Component<K, E> component = new Component<>();
            Node<K, E> member;
            do {
                member = tarjanStack.pop();
                member.onTarjanStack = false;
                member.component = component;
                component.members.add(member);
            } while (member != node);
            component.cyclic = component.members.size() > 1;
        }

        private boolean canReachSeed(Component<K, E> component, Set<Component<K, E>> visiting) {
            if (component.reachesSeed != null) return component.reachesSeed;
            if (component.directSeed) return component.reachesSeed = true;
            if (!visiting.add(component)) return false;
            try {
                for (Node<K, E> member : component.members) {
                    for (RecipeGraphIndex.HyperEdge<K, E> edge : member.edges) {
                        if (edgeCanStart(member.key, edge, component, visiting)) return component.reachesSeed = true;
                    }
                }
                return component.reachesSeed = false;
            } finally {
                visiting.remove(component);
            }
        }

        private boolean edgeCanStart(K output, RecipeGraphIndex.HyperEdge<K, E> edge, Component<K, E> component,
                                     Set<Component<K, E>> visiting) {
            if (!seedPolicy.canUseEdge(output, edge)) return false;
            if (!seedPolicy.canStartEdge(output, edge)) return false;
            if (seedPolicy.isSeedEdge(output, edge)) return true;
            for (List<K> group : edge.inputGroups()) {
                boolean satisfiable = false;
                for (K option : group) {
                    if (seedPolicy.isDirectSeed(option)) {
                        satisfiable = true;
                        break;
                    }
                    Node<K, E> dependency = nodes.get(option);
                    if (dependency != null && dependency.component != null && dependency.component != component &&
                            canReachSeed(dependency.component, visiting)) {
                        satisfiable = true;
                        break;
                    }
                }
                if (!satisfiable) return false;
            }
            return true;
        }

        private boolean reaches(Node<K, E> current, K target, Set<Node<K, E>> visiting) {
            if (current.key.equals(target)) return true;
            if (!visiting.add(current)) return false;
            try {
                for (K dependencyKey : current.dependencies) {
                    Node<K, E> dependency = nodes.get(dependencyKey);
                    if (dependency != null && reaches(dependency, target, visiting)) return true;
                }
                return false;
            } finally {
                visiting.remove(current);
            }
        }
    }

    private static final class Node<K, E> {
        private final K key;
        private final Set<K> dependencies = new HashSet<>();
        private final List<RecipeGraphIndex.HyperEdge<K, E>> edges = new ArrayList<>();
        private int tarjanIndex = -1;
        private int lowLink;
        private boolean onTarjanStack;
        private boolean directSeed;
        private Component<K, E> component;

        private Node(K key) {
            this.key = key;
        }
    }

    private static final class Component<K, E> {
        private final List<Node<K, E>> members = new ArrayList<>();
        private boolean cyclic;
        private boolean directSeed;
        private Boolean reachesSeed;
    }
}
