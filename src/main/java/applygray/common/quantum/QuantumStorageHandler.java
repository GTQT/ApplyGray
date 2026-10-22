package applygray.common.quantum;

import it.unimi.dsi.fastutil.Hash;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A big-number quantum store living on a storage multiblock controller.
 * <p>
 * Amounts are tracked as {@link BigInteger} because total capacities reach far
 * beyond the 64-bit range. The whole store is a single map of distinct types to
 * amounts; each distinct type may grow up to {@code totalCapacity / maxDistinct}.
 * <p>
 * The type parameter {@code T} is an opaque content descriptor ({@code ItemStack}
 * or {@code FluidStack} in practice); hashing, equality, copying and
 * (de)serialization are supplied by the caller.
 * <p>
 * <b>Cost model.</b> Distinct types are keyed by their hash under a frozen
 * {@link Hash.Strategy}, so every lookup is O(1) instead of a linear scan, and
 * the stored total is maintained incrementally instead of being folded on every
 * read. That matters because a fully built array can hold millions of distinct
 * slots, and both the GUI and the ME network ask for the totals every tick.
 * <p>
 * <b>Mutation contract.</b> The hashes of stored keys are frozen, so a stored
 * instance must never be mutated. Callers only ever receive copies through
 * {@link #entries()} and through the change listeners, and {@link #insert}
 * stores its own copy of a newly added type. The instance passed to
 * {@link #insert} and {@link #extract} is only used for the lookup and may be
 * mutated afterwards.
 */
public class QuantumStorageHandler<T> {

    private int maxDistinct;
    private BigInteger totalCapacity;
    private BigInteger slotCapacity;
    private final Hash.Strategy<T> strategy;
    private final UnaryOperator<T> copyType;
    private final BiConsumer<NBTTagCompound, T> writeType;
    private final Function<NBTTagCompound, T> readType;

    private final LinkedHashMap<HashKey<T>, BigInteger> contents = new LinkedHashMap<>();
    private BigInteger storedTotal = BigInteger.ZERO;

    /**
     * Change listeners; registration is rare and iteration happens on every
     * accepted insert/extraction, so a copy-on-write list keeps the hot path
     * allocation free while staying safe against re-entrant registration.
     */
    private final List<BiConsumer<T, BigInteger>> listeners = new CopyOnWriteArrayList<>();

    public int maxDistinct() {
        return maxDistinct;
    }

    public BigInteger totalCapacity() {
        return totalCapacity;
    }

    /**
     * @param strategy  hash strategy for content equality; it must ignore stack size
     * @param copyType  copies a content type, so callers never own a stored instance
     * @param writeType serializes one content type into a slot tag
     * @param readType  reads one content type back from a slot tag
     */
    public QuantumStorageHandler(int maxDistinct,
                                 BigInteger totalCapacity,
                                 Hash.Strategy<T> strategy,
                                 UnaryOperator<T> copyType,
                                 BiConsumer<NBTTagCompound, T> writeType,
                                 Function<NBTTagCompound, T> readType) {
        this.maxDistinct = Math.max(maxDistinct, 0);
        this.totalCapacity = totalCapacity.max(BigInteger.ZERO);
        this.slotCapacity = computeSlotCapacity(this.maxDistinct, this.totalCapacity);
        this.strategy = strategy;
        this.copyType = copyType;
        this.writeType = writeType;
        this.readType = readType;
    }

    /**
     * Recomputes slot capacity from the given totals and drops any content that no
     * longer fits (too many distinct types, or an amount above the new per-slot cap).
     * Every dropped type is reported to the listeners as a negative delta, so a
     * mounted ME view removes it instead of continuing to serve it.
     *
     * @return {@code true} if any content was removed
     */
    public boolean rebuild(int newMaxDistinct, BigInteger newTotalCapacity) {
        maxDistinct = Math.max(newMaxDistinct, 0);
        totalCapacity = newTotalCapacity.max(BigInteger.ZERO);
        slotCapacity = computeSlotCapacity(maxDistinct, totalCapacity);

        List<Map.Entry<T, BigInteger>> dropped = null;
        Iterator<Map.Entry<HashKey<T>, BigInteger>> iterator = contents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<HashKey<T>, BigInteger> content = iterator.next();
            if (contents.size() > maxDistinct || content.getValue().compareTo(slotCapacity) > 0) {
                iterator.remove();
                if (dropped == null) {
                    dropped = new ArrayList<>();
                }
                dropped.add(new AbstractMap.SimpleImmutableEntry<>(
                        copyType.apply(content.getKey().type), content.getValue()));
            }
        }
        if (dropped == null) {
            return false;
        }
        recomputeTotal();
        // Dropped content must also leave the grid, otherwise a rebuilt store keeps serving phantom entries.
        for (Map.Entry<T, BigInteger> entry : dropped) {
            notifyChanged(entry.getKey(), entry.getValue().negate());
        }
        return true;
    }

    /** @return the accepted amount; may be less than requested when the slot cap is hit */
    public BigInteger insert(T type, BigInteger amount) {
        if (type == null || amount.signum() <= 0 || slotCapacity.signum() <= 0) {
            return BigInteger.ZERO;
        }
        HashKey<T> key = new HashKey<>(type, strategy);
        BigInteger currentAmount = contents.get(key);
        if (currentAmount != null) {
            BigInteger remaining = slotCapacity.subtract(currentAmount);
            if (remaining.signum() <= 0) {
                return BigInteger.ZERO;
            }
            BigInteger accepted = amount.min(remaining);
            if (accepted.signum() > 0) {
                // replace() keeps the existing key instance, whose hash must stay frozen.
                contents.replace(key, currentAmount.add(accepted));
                storedTotal = storedTotal.add(accepted);
                notifyChanged(type, accepted);
            }
            return accepted;
        }
        if (contents.size() >= maxDistinct) {
            return BigInteger.ZERO;
        }
        BigInteger accepted = amount.min(slotCapacity);
        if (accepted.signum() > 0) {
            contents.put(new HashKey<>(copyType.apply(type), strategy), accepted);
            storedTotal = storedTotal.add(accepted);
            notifyChanged(type, accepted);
        }
        return accepted;
    }

    /** @return the removed amount */
    public BigInteger extract(T type, BigInteger amount) {
        if (type == null || amount.signum() <= 0) {
            return BigInteger.ZERO;
        }
        HashKey<T> key = new HashKey<>(type, strategy);
        BigInteger currentAmount = contents.get(key);
        if (currentAmount == null) {
            return BigInteger.ZERO;
        }
        BigInteger removed = amount.min(currentAmount);
        BigInteger remaining = currentAmount.subtract(removed);
        if (remaining.signum() <= 0) {
            contents.remove(key);
        } else {
            contents.replace(key, remaining);
        }
        storedTotal = storedTotal.subtract(removed);
        notifyChanged(type, removed.negate());
        return removed;
    }

    public boolean canInsert(T type) {
        return type != null && slotCapacity.signum() > 0
                && (contents.containsKey(new HashKey<>(type, strategy)) || contents.size() < maxDistinct);
    }

    /** @return how much more of {@code type} may be inserted before the slot cap is hit */
    public BigInteger maxInsertable(T type) {
        if (type == null || slotCapacity.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger currentAmount = contents.get(new HashKey<>(type, strategy));
        if (currentAmount != null) {
            BigInteger remaining = slotCapacity.subtract(currentAmount);
            return remaining.signum() < 0 ? BigInteger.ZERO : remaining;
        }
        return contents.size() < maxDistinct ? slotCapacity : BigInteger.ZERO;
    }

    public BigInteger currentAmount(T type) {
        if (type == null) {
            return BigInteger.ZERO;
        }
        BigInteger amount = contents.get(new HashKey<>(type, strategy));
        return amount == null ? BigInteger.ZERO : amount;
    }

    public int distinctSlots() {
        return contents.size();
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    public boolean isFull() {
        return contents.size() >= maxDistinct;
    }

    /**
     * Snapshots the whole store; every content type in the returned list is a
     * caller-owned copy, so iterating and even mutating it cannot corrupt the store.
     */
    public List<Map.Entry<T, BigInteger>> entries() {
        List<Map.Entry<T, BigInteger>> snapshot = new ArrayList<>(contents.size());
        for (Map.Entry<HashKey<T>, BigInteger> entry : contents.entrySet()) {
            snapshot.add(new AbstractMap.SimpleImmutableEntry<>(
                    copyType.apply(entry.getKey().type), entry.getValue()));
        }
        return snapshot;
    }

    /** Incremental total, so reading it never costs more than the last change. */
    public BigInteger totalStored() {
        return storedTotal;
    }

    /**
     * Registers a listener that is told about every accepted insert (positive
     * delta) and extraction (negative delta). The reported type is a copy, and a
     * listener may run re-entrantly from inside {@link #insert}/{@link #extract},
     * so it must not mutate the type it receives.
     */
    public void addListener(BiConsumer<T, BigInteger> listener) {
        listeners.add(listener);
    }

    public void removeListener(BiConsumer<T, BigInteger> listener) {
        listeners.remove(listener);
    }

    private void notifyChanged(T type, BigInteger delta) {
        if (listeners.isEmpty() || delta.signum() == 0) {
            return;
        }
        T changedType = copyType.apply(type);
        for (BiConsumer<T, BigInteger> listener : listeners) {
            listener.accept(changedType, delta);
        }
    }

    /** Serializes the whole store; amounts are stored as decimal strings to survive 64-bit overflow. */
    public NBTTagCompound serialize() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (Map.Entry<HashKey<T>, BigInteger> entry : contents.entrySet()) {
            NBTTagCompound slot = new NBTTagCompound();
            writeType.accept(slot, entry.getKey().type);
            slot.setString("amount", entry.getValue().toString());
            list.appendTag(slot);
        }
        tag.setTag("contents", list);
        return tag;
    }

    public void deserialize(NBTTagCompound tag) {
        contents.clear();
        NBTTagList list = tag.getTagList("contents", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound slot = list.getCompoundTagAt(i);
            T type = readType.apply(slot);
            BigInteger amount = new BigInteger(slot.getString("amount"));
            if (type != null && amount.signum() > 0) {
                contents.put(new HashKey<>(type, strategy), amount);
            }
        }
        recomputeTotal();
    }

    private void recomputeTotal() {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger amount : contents.values()) {
            total = total.add(amount);
        }
        storedTotal = total;
    }

    private static BigInteger computeSlotCapacity(int slots, BigInteger capacity) {
        if (slots <= 0 || capacity.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return capacity.divide(BigInteger.valueOf(slots));
    }

    /**
     * A content type with a frozen hash, so the underlying map can be indexed in
     * constant time without letting a later mutation of the type invalidate it.
     */
    private static final class HashKey<T> {

        private final T type;
        private final Hash.Strategy<T> strategy;
        private final int hash;

        private HashKey(T type, Hash.Strategy<T> strategy) {
            this.type = type;
            this.strategy = strategy;
            this.hash = strategy.hashCode(type);
        }

        private T type() {
            return type;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof HashKey<?> key) || strategy != key.strategy) {
                return false;
            }
            @SuppressWarnings("unchecked")
            T otherType = (T) key.type;
            return strategy.equals(type, otherType);
        }
    }
}
