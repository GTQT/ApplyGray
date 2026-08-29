package applygray.mattermanipulator.inventory;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

import net.minecraftforge.fluids.FluidStack;

/** Infinite, side-effect-free material source used only for creative-mode builds. */
public final class CreativeMaterialSource implements MaterialSource {

    public static final CreativeMaterialSource INSTANCE = new CreativeMaterialSource();

    private CreativeMaterialSource() {}

    @Override
    public String id() {
        return "creative";
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) {
        Objects.requireNonNull(specification, "specification");
        return available(amount);
    }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) {
        Objects.requireNonNull(specification, "specification");
        return available(amount);
    }

    @Override
    public long extract(FluidStack specification, long amount, boolean simulate) {
        Objects.requireNonNull(specification, "specification");
        return available(amount);
    }

    @Override
    public long insert(FluidStack specification, long amount, boolean simulate) {
        Objects.requireNonNull(specification, "specification");
        return available(amount);
    }

    private static long available(long amount) {
        if (amount < 0L) throw new IllegalArgumentException("amount must not be negative");
        return amount;
    }
}
