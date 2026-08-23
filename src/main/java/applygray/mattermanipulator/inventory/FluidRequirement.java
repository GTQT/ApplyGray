package applygray.mattermanipulator.inventory;

import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Immutable exact fluid value used by material reservations. Amounts are millibuckets. */
public record FluidRequirement(String fluidName, NBTTagCompound tag, long amount) {

    public FluidRequirement {
        Objects.requireNonNull(fluidName, "fluidName");
        if (fluidName.isEmpty() || FluidRegistry.getFluid(fluidName) == null) {
            throw new IllegalArgumentException("Unknown fluid: " + fluidName);
        }
        tag = tag == null ? null : tag.copy();
        if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
    }

    public FluidRequirement(FluidStack stack, long amount) {
        this(Objects.requireNonNull(stack, "stack").getFluid().getName(), stack.tag, amount);
    }

    public Fluid fluid() {
        return Objects.requireNonNull(FluidRegistry.getFluid(fluidName), "fluid registry changed");
    }

    public FluidStack stack(int requestedAmount) {
        FluidStack stack = new FluidStack(fluid(), requestedAmount);
        if (tag != null) stack.tag = tag.copy();
        return stack;
    }
}
