package applygray.mattermanipulator.inventory;

import java.util.Objects;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import applygray.mattermanipulator.building.BlockSpec;

/** Exact fluid source backed by a Forge fluid handler. */
public final class FluidMaterialSource implements MaterialSource {

    private final String id;
    private final IFluidHandler handler;

    public FluidMaterialSource(String id, IFluidHandler handler) {
        this.id = Objects.requireNonNull(id, "id");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) { return 0L; }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) { return 0L; }

    @Override
    public long extract(FluidStack requested, long amount, boolean simulate) {
        if (requested == null || requested.amount <= 0 || amount <= 0) return 0L;
        long remaining = amount;
        long extracted = 0L;
        while (remaining > 0L) {
            FluidStack probe = requested.copy();
            probe.amount = (int) Math.min(remaining, Integer.MAX_VALUE);
            FluidStack result = handler.drain(probe, !simulate);
            if (result == null || result.amount <= 0) break;
            if (!result.isFluidEqual(requested) || result.amount > probe.amount) {
                throw new IllegalStateException("Fluid handler " + id + " returned an unexpected extraction");
            }
            extracted += result.amount;
            remaining -= result.amount;
            if (result.amount < probe.amount) break;
        }
        return extracted;
    }

    @Override
    public long insert(FluidStack offered, long amount, boolean simulate) {
        if (offered == null || offered.amount <= 0 || amount <= 0) return 0L;
        long remaining = amount;
        long inserted = 0L;
        while (remaining > 0L) {
            FluidStack part = offered.copy();
            part.amount = (int) Math.min(remaining, Integer.MAX_VALUE);
            int accepted = handler.fill(part, !simulate);
            if (accepted < 0 || accepted > part.amount) {
                throw new IllegalStateException("Fluid handler " + id + " returned an invalid insertion amount");
            }
            inserted += accepted;
            remaining -= accepted;
            if (accepted < part.amount) break;
        }
        return inserted;
    }
}
