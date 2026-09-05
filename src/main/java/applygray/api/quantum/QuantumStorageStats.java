package applygray.api.quantum;

import java.math.BigInteger;

/**
 * Storage statistics provided by a single quantum storage unit block.
 * <p>
 * {@link #distinctSlots()} limits how many distinct content types may be stored
 * for this block's share, {@link #totalCapacity()} is the total amount (items or
 * mB) this block's share can hold, distributed evenly over the distinct slots.
 */
public interface QuantumStorageStats {

    int distinctSlots();

    BigInteger totalCapacity();
}
