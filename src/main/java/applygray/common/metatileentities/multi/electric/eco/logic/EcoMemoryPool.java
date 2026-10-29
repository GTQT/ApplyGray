package applygray.common.metatileentities.multi.electric.eco.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread reservation ledger shared by all CPUs of one physical computer. */
public final class EcoMemoryPool {
    private final Map<UUID, Long> reservations = new HashMap<>();
    private long capacity;
    private long used;

    public void setCapacity(long capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Negative memory capacity");
        this.capacity = capacity;
    }

    public long capacity() { return capacity; }
    public long used() { return used; }
    public long available() { return Math.max(0, capacity - used); }
    public long reserved(UUID id) { return reservations.getOrDefault(id, 0L); }

    public static long charge(long bytes, boolean hyper) {
        if (bytes < 0) throw new IllegalArgumentException("Negative job size");
        return hyper ? Math.addExact(bytes, bytes / 10 + (bytes % 10 == 0 ? 0 : 1)) : bytes;
    }

    public boolean reserve(UUID id, long bytes, boolean hyper) {
        if (reservations.containsKey(id)) return false;
        final long charged;
        try { charged = charge(bytes, hyper); }
        catch (ArithmeticException overflow) { return false; }
        if (charged > available()) return false;
        reservations.put(id, charged);
        used += charged;
        return true;
    }

    /** Restore accepted jobs even if hardware was removed: they continue to own their reservation. */
    public void restore(UUID id, long charged) {
        if (charged < 0 || reservations.containsKey(id)) throw new IllegalArgumentException("Invalid reservation");
        long total = Math.addExact(used, charged);
        reservations.put(id, charged);
        used = total;
    }

    public void release(UUID id) {
        Long charged = reservations.remove(id);
        if (charged != null) used -= charged;
    }
}
