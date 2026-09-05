package applygray.common.quantum;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A big-number quantum store living on a storage multiblock controller.
 * <p>
 * Amounts are tracked as {@link BigInteger} because total capacities reach far
 * beyond the 64-bit range. The whole store is a single map of distinct types to
 * amounts; each distinct type may grow up to {@code totalCapacity / maxDistinct}.
 * <p>
 * The type parameter {@code T} is an opaque content descriptor ({@code ItemStack}
 * or {@code FluidStack} in practice); equality and (de)serialization are supplied
 * by the caller.
 */
public class QuantumStorageHandler<T> {

    private int maxDistinct;
    private BigInteger totalCapacity;
    private BigInteger slotCapacity;
    private final BiPredicate<T, T> isSameType;
    private final BiConsumer<NBTTagCompound, T> writeType;
    private final Function<NBTTagCompound, T> readType;

    private final LinkedHashMap<T, BigInteger> contents = new LinkedHashMap<>();

    public int maxDistinct() {
        return maxDistinct;
    }

    public BigInteger totalCapacity() {
        return totalCapacity;
    }

    public QuantumStorageHandler(int maxDistinct,
                                 BigInteger totalCapacity,
                                 BiPredicate<T, T> isSameType,
                                 BiConsumer<NBTTagCompound, T> writeType,
                                 Function<NBTTagCompound, T> readType) {
        this.maxDistinct = Math.max(maxDistinct, 0);
        this.totalCapacity = totalCapacity.max(BigInteger.ZERO);
        this.slotCapacity = computeSlotCapacity(this.maxDistinct, this.totalCapacity);
        this.isSameType = isSameType;
        this.writeType = writeType;
        this.readType = readType;
    }

    /**
     * Recomputes slot capacity from the given totals and drops any content that no
     * longer fits (too many distinct types, or an amount above the new per-slot cap).
     *
     * @return {@code true} if any content was removed
     */
    public boolean rebuild(int newMaxDistinct, BigInteger newTotalCapacity) {
        maxDistinct = Math.max(newMaxDistinct, 0);
        totalCapacity = newTotalCapacity.max(BigInteger.ZERO);
        slotCapacity = computeSlotCapacity(maxDistinct, totalCapacity);

        boolean removed = false;
        Iterator<Map.Entry<T, BigInteger>> iterator = contents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<T, BigInteger> content = iterator.next();
            if (contents.size() > maxDistinct || content.getValue().compareTo(slotCapacity) > 0) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    /** @return the accepted amount; may be less than requested when the slot cap is hit */
    public BigInteger insert(T type, BigInteger amount) {
        if (amount.signum() <= 0 || slotCapacity.signum() <= 0) {
            return BigInteger.ZERO;
        }
        T existingKey = findKey(type);
        if (existingKey != null) {
            BigInteger currentAmount = contents.get(existingKey);
            BigInteger remaining = slotCapacity.subtract(currentAmount);
            if (remaining.signum() <= 0) {
                return BigInteger.ZERO;
            }
            BigInteger accepted = amount.min(remaining);
            if (accepted.signum() > 0) {
                contents.put(existingKey, currentAmount.add(accepted));
            }
            return accepted;
        }
        if (contents.size() >= maxDistinct) {
            return BigInteger.ZERO;
        }
        BigInteger accepted = amount.min(slotCapacity);
        if (accepted.signum() > 0) {
            contents.put(type, accepted);
        }
        return accepted;
    }

    /** @return the removed amount */
    public BigInteger extract(T type, BigInteger amount) {
        T key = findKey(type);
        if (key == null) {
            return BigInteger.ZERO;
        }
        BigInteger currentAmount = contents.get(key);
        BigInteger removed = amount.min(currentAmount);
        BigInteger remaining = currentAmount.subtract(removed);
        if (remaining.signum() <= 0) {
            contents.remove(key);
        } else {
            contents.put(key, remaining);
        }
        return removed;
    }

    public boolean canInsert(T type) {
        return slotCapacity.signum() > 0 && (findKey(type) != null || contents.size() < maxDistinct);
    }

    /** @return how much more of {@code type} may be inserted before the slot cap is hit */
    public BigInteger maxInsertable(T type) {
        if (slotCapacity.signum() <= 0) {
            return BigInteger.ZERO;
        }
        T existingKey = findKey(type);
        if (existingKey != null) {
            BigInteger remaining = slotCapacity.subtract(contents.get(existingKey));
            return remaining.signum() < 0 ? BigInteger.ZERO : remaining;
        }
        return contents.size() < maxDistinct ? slotCapacity : BigInteger.ZERO;
    }

    public BigInteger currentAmount(T type) {
        T key = findKey(type);
        return key == null ? BigInteger.ZERO : contents.get(key);
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

    public Set<Map.Entry<T, BigInteger>> entries() {
        return contents.entrySet();
    }

    public BigInteger totalStored() {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger amount : contents.values()) {
            total = total.add(amount);
        }
        return total;
    }

    private T findKey(T type) {
        for (T key : contents.keySet()) {
            if (isSameType.test(key, type)) {
                return key;
            }
        }
        return null;
    }

    /** Serializes the whole store; amounts are stored as decimal strings to survive 64-bit overflow. */
    public NBTTagCompound serialize() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (Map.Entry<T, BigInteger> entry : contents.entrySet()) {
            NBTTagCompound slot = new NBTTagCompound();
            writeType.accept(slot, entry.getKey());
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
            if (amount.signum() > 0) {
                contents.put(type, amount);
            }
        }
    }

    private static BigInteger computeSlotCapacity(int slots, BigInteger capacity) {
        if (slots <= 0 || capacity.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return capacity.divide(BigInteger.valueOf(slots));
    }
}
