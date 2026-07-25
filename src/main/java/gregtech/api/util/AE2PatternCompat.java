package gregtech.api.util;

import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Supergiant processing-pattern conversion helpers. */
public final class AE2PatternCompat {

    private AE2PatternCompat() {}

    public static boolean isFluidDrop(ItemStack stack) {
        GenericStack genericStack = GenericStack.unwrapItemStack(stack);
        return genericStack != null && genericStack.what() instanceof AEFluidKey;
    }

    @Nullable
    public static FluidStack getFluidStack(ItemStack stack) {
        GenericStack genericStack = GenericStack.unwrapItemStack(stack);
        if (genericStack == null || !(genericStack.what() instanceof AEFluidKey fluidKey)) {
            return null;
        }
        return fluidKey.toStack(clampAmount(genericStack.amount()));
    }

    public static ItemStack toFluidDrop(FluidStack fluidStack) {
        GenericStack genericStack = GenericStack.fromFluidStack(fluidStack);
        return GenericStack.wrapInItemStack(genericStack);
    }

    public static NBTBase createPatternIngredientTag(ItemStack stack) {
        GenericStack genericStack = toGenericStack(stack);
        return GenericStack.writeTag(genericStack);
    }

    public static boolean containsFluid(ItemStack[] stacks) {
        if (stacks == null) {
            return false;
        }
        for (ItemStack stack : stacks) {
            GenericStack genericStack = toGenericStack(stack);
            if (genericStack != null && genericStack.what() instanceof AEFluidKey) {
                return true;
            }
        }
        return false;
    }

    public static ItemStack createProcessingPattern(ItemStack[] inputs, ItemStack[] outputs, boolean substitute,
                                                    boolean fluidPattern) {
        List<GenericStack> genericInputs = toGenericStacks(inputs);
        List<GenericStack> genericOutputs = toGenericStacks(outputs);
        if (genericInputs.isEmpty() || genericOutputs.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return PatternDetailsHelper.encodeProcessingPattern(genericInputs, genericOutputs);
    }

    @Nullable
    public static GenericStack toGenericStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        GenericStack wrapped = GenericStack.unwrapItemStack(stack);
        if (wrapped != null) {
            return wrapped;
        }

        FluidStack containedFluid = FluidUtil.getFluidContained(stack);
        if (containedFluid != null && containedFluid.amount > 0) {
            FluidStack total = containedFluid.copy();
            total.amount = clampAmount((long) total.amount * stack.getCount());
            GenericStack genericFluid = GenericStack.fromFluidStack(total);
            if (genericFluid != null) {
                return genericFluid;
            }
        }
        return GenericStack.fromItemStack(stack);
    }

    private static List<GenericStack> toGenericStacks(ItemStack[] stacks) {
        List<GenericStack> result = new ArrayList<>();
        if (stacks == null) {
            return result;
        }
        for (ItemStack stack : stacks) {
            GenericStack genericStack = toGenericStack(stack);
            if (genericStack != null) {
                result.add(genericStack);
            }
        }
        return result;
    }

    private static int clampAmount(long amount) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, amount));
    }
}
