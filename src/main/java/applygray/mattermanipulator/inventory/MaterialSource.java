package applygray.mattermanipulator.inventory;

import applygray.mattermanipulator.building.BlockSpec;
import net.minecraftforge.fluids.FluidStack;

/**
 * A source or destination of exact block materials.
 *
 * <p>The source must not mutate its backing storage when {@code simulate} is true. All calls happen on the server
 * thread; implementations must still return the actual transferred count because an external inventory can change
 * between reservation and commit.</p>
 */
public interface MaterialSource {

    String id();

    long extract(BlockSpec specification, long amount, boolean simulate);

    long insert(BlockSpec specification, long amount, boolean simulate);

    /** Fluid half of the same exact-material contract; legacy item-only sources safely return zero. */
    default long extract(FluidStack specification, long amount, boolean simulate) {
        return 0L;
    }

    /** Fluid half of the same exact-material contract; legacy item-only sources safely return zero. */
    default long insert(FluidStack specification, long amount, boolean simulate) {
        return 0L;
    }
}
