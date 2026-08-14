package applygray.integration.ae2.planning;

import java.util.List;
import java.util.Objects;

/** Immutable recipe-graph values shared by local route scoring and occurrence-plan materialization. */
public final class RouteModel {

    private RouteModel() {}

    public interface RuntimeContext<K> {

        long getAvailable(K key);

        boolean isLeaf(K key);

        boolean isFree(K key);

        long estimateMaterialCost(K key, long amount);

        boolean reserveExpansion();

        boolean shouldContinue();
    }

    public record Amount<K>(K key, long amount) {

        public Amount {
            Objects.requireNonNull(key, "key");
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        }
    }

    public record Input<K>(List<Amount<K>> alternatives, boolean stockOnly) {

        public Input {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    public record Edge<K>(String id, List<Input<K>> inputs, List<Amount<K>> outputs, long executionsCost,
                          long cycleRisk, long materialFormConversions) {

        public Edge {
            Objects.requireNonNull(id, "id");
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            executionsCost = Math.max(0, executionsCost);
            cycleRisk = Math.max(0, cycleRisk);
            materialFormConversions = Math.max(0, materialFormConversions);
        }

        public long netOutput(K target) {
            long produced = outputAmount(target);
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

        public long outputAmount(K target) {
            long produced = 0;
            for (Amount<K> output : outputs) {
                if (target.equals(output.key())) produced = saturatedAdd(produced, output.amount());
            }
            return produced;
        }
    }

    public record Cost(long missingMaterials, int maxDepth, long executions, long consumedStockMaterials,
                       int boundedFallbacks, int unresolvedIntermediates, long cycleRisk,
                       long materialFormConversions) {

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
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static int saturatedIntAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        if (right < 0 && left < Integer.MIN_VALUE - right) return Integer.MIN_VALUE;
        return left + right;
    }
}
