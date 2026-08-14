package applygray.integration.ae2.planning;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Sparse branch-local inventory ledger that distinguishes external stock from outputs produced by the route. */
final class PlanningInventory<K> {

    record Consumption(long total, long external, long produced) {}

    private final Map<K, Long> available;
    private final Map<K, Long> produced;

    PlanningInventory() {
        this(new HashMap<>(), new HashMap<>());
    }

    private PlanningInventory(Map<K, Long> available, Map<K, Long> produced) {
        this.available = available;
        this.produced = produced;
    }

    PlanningInventory<K> copy() {
        return new PlanningInventory<>(new HashMap<>(available), new HashMap<>(produced));
    }

    Consumption consume(K key, long amount, LongSupplier externalAvailable) {
        if (amount <= 0) return new Consumption(0, 0, 0);
        long totalAvailable = initialize(key, externalAvailable);
        long producedAvailable = produced.getOrDefault(key, 0L);
        long consumed = Math.min(totalAvailable, amount);
        long consumedProduced = Math.min(producedAvailable, consumed);
        long consumedExternal = consumed - consumedProduced;
        available.put(key, totalAvailable - consumed);
        if (consumedProduced > 0) produced.put(key, producedAvailable - consumedProduced);
        return new Consumption(consumed, consumedExternal, consumedProduced);
    }

    void addProduced(K key, long amount, LongSupplier externalAvailable) {
        if (amount <= 0) return;
        long totalAvailable = initialize(key, externalAvailable);
        available.put(key, saturatedAdd(totalAvailable, amount));
        produced.put(key, saturatedAdd(produced.getOrDefault(key, 0L), amount));
    }

    Map<K, Long> availableView() {
        return Collections.unmodifiableMap(available);
    }

    Map<K, Long> producedView() {
        return Collections.unmodifiableMap(produced);
    }

    private long initialize(K key, LongSupplier externalAvailable) {
        return available.computeIfAbsent(key, ignored -> Math.max(0, externalAvailable.getAsLong()));
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
