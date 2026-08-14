package applygray.integration.ae2.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, replayable result of selected-route occurrence materialization.
 *
 * <p>Every recipe execution is represented by a distinct step. Keeping occurrences instead of only a
 * {@code target -> recipe} map allows two demands for the same key to select different routes without losing either
 * decision during materialization.</p>
 */
public final class RoutePlan<K> {

    public enum SupplyKind {
        UNKNOWN,
        STOCK,
        CO_PRODUCT,
        LEAF,
        FREE,
        RECIPE,
        MIXED,
        UNRESOLVED
    }

    public record InputChoice<K>(K key, long amount, SupplyKind supplyKind, Long supplierStepId) {

        public InputChoice {
            Objects.requireNonNull(key, "key");
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
            Objects.requireNonNull(supplyKind, "supplyKind");
            if (supplierStepId != null && supplierStepId < 0) {
                throw new IllegalArgumentException("supplierStepId must be non-negative");
            }
            if (supplyKind == SupplyKind.RECIPE && supplierStepId == null) {
                throw new IllegalArgumentException("recipe supply must identify its step");
            }
        }

        InputChoice<K> withSupply(SupplyKind kind, Long supplierId) {
            return new InputChoice<>(key, amount, kind, supplierId);
        }
    }

    public record Step<K>(long id, Long parentStepId, int parentInputIndex, K target, long requestedAmount,
                          String edgeId, long executions, int depth, Map<Integer, InputChoice<K>> inputChoices) {

        public Step {
            if (id < 0) throw new IllegalArgumentException("id must be non-negative");
            if (parentStepId != null && parentStepId < 0) {
                throw new IllegalArgumentException("parentStepId must be non-negative");
            }
            if (parentStepId == null && parentInputIndex != -1) {
                throw new IllegalArgumentException("root step must use parentInputIndex=-1");
            }
            if (parentStepId != null && parentInputIndex < 0) {
                throw new IllegalArgumentException("child step must identify its parent input");
            }
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(edgeId, "edgeId");
            if (requestedAmount <= 0) throw new IllegalArgumentException("requestedAmount must be positive");
            if (executions <= 0) throw new IllegalArgumentException("executions must be positive");
            if (depth < 0) throw new IllegalArgumentException("depth must be non-negative");
            inputChoices = Collections.unmodifiableMap(new LinkedHashMap<>(inputChoices));
        }

        Step<K> withInputChoice(int inputIndex, InputChoice<K> choice) {
            Map<Integer, InputChoice<K>> updated = new LinkedHashMap<>(inputChoices);
            updated.put(inputIndex, Objects.requireNonNull(choice, "choice"));
            return new Step<>(id, parentStepId, parentInputIndex, target, requestedAmount, edgeId, executions, depth,
                    updated);
        }

        Step<K> withInputSupply(int inputIndex, SupplyKind kind, Long supplierStepId) {
            InputChoice<K> choice = inputChoices.get(inputIndex);
            if (choice == null) return this;
            return withInputChoice(inputIndex, choice.withSupply(kind, supplierStepId));
        }
    }

    private final long rootStepId;
    private final Map<Long, Step<K>> steps;
    private final List<Long> selectionOrder;

    public RoutePlan(long rootStepId, Map<Long, Step<K>> steps, List<Long> selectionOrder) {
        if (!steps.containsKey(rootStepId)) throw new IllegalArgumentException("root step is missing");
        this.rootStepId = rootStepId;
        this.steps = Collections.unmodifiableMap(new LinkedHashMap<>(steps));
        this.selectionOrder = Collections.unmodifiableList(new ArrayList<>(selectionOrder));
    }

    public long rootStepId() {
        return rootStepId;
    }

    public Step<K> rootStep() {
        return steps.get(rootStepId);
    }

    public Map<Long, Step<K>> steps() {
        return steps;
    }

    public List<Long> selectionOrder() {
        return selectionOrder;
    }

    public List<Step<K>> childrenOf(long stepId) {
        List<Step<K>> children = new ArrayList<>();
        for (Step<K> step : steps.values()) {
            if (step.parentStepId() != null && step.parentStepId() == stepId) children.add(step);
        }
        children.sort(java.util.Comparator.<Step<K>>comparingInt(Step::parentInputIndex)
                .thenComparingLong(Step::id));
        return Collections.unmodifiableList(children);
    }
}
