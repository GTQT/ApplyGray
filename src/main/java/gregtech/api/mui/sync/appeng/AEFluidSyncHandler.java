package gregtech.api.mui.sync.appeng;

import applygray.api.mui.ApplyGrayByteBufAdapters;
import gregtech.api.capability.impl.GhostCircuitItemStackHandler;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.api.util.GTUtility;
import gregtech.api.util.JEIUtil;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEFluidList;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEFluidSlot;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.IConfigurableSlot;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.utils.serialization.IByteBufAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class AEFluidSyncHandler extends AESyncHandler {

    protected final ExportOnlyAEFluidList fluidList;

    public AEFluidSyncHandler(ExportOnlyAEFluidList fluidList, @Nullable Runnable dirtyNotifier,
                              @NotNull IntConsumer circuitChangeConsumer) {
        super(fluidList.getInventory(), fluidList.isStocking(), dirtyNotifier, circuitChangeConsumer);
        this.fluidList = fluidList;
    }

    @Override
    protected @NotNull IConfigurableSlot @NotNull [] initializeCache() {
        IConfigurableSlot[] cache = new IConfigurableSlot[slots.length];
        for (int index = 0; index < slots.length; index++) {
            cache[index] = new ExportOnlyAEFluidSlot();
        }
        return cache;
    }

    @Override
    protected @NotNull IByteBufAdapter<GenericStack> initializeByteBufAdapter() {
        return ApplyGrayByteBufAdapters.GENERIC_STACK;
    }

    @Override
    public boolean isStackValidForSlot(int index, @Nullable GenericStack stack) {
        if (stack == null) {
            return true;
        }
        if (!(stack.what() instanceof AEFluidKey fluidKey)) {
            return false;
        }
        return !isStocking || !fluidList.hasStackInConfig(fluidKey.getReadOnlyStack(), true);
    }

    @Override
    public IRecipeTransferError receiveRecipe(@NotNull IRecipeLayout recipeLayout, boolean maxTransfer,
                                              boolean simulate) {
        if (simulate) {
            return null;
        }

        Int2ObjectMap<FluidStack> originalFluidInputs = JEIUtil
                .getDisplayedInputFluidStacks(recipeLayout.getFluidStacks(), false, true);
        List<FluidStack> fluidInputs = new ArrayList<>(originalFluidInputs.values());
        GTUtility.collapseFluidList(fluidInputs);

        int lastSlotIndex;
        for (lastSlotIndex = 0; lastSlotIndex < fluidInputs.size() && lastSlotIndex < slots.length; lastSlotIndex++) {
            setConfig(lastSlotIndex, GenericStack.fromFluidStack(fluidInputs.get(lastSlotIndex)));
        }
        clearConfigFrom(lastSlotIndex);

        Int2ObjectMap<ItemStack> itemInputs = JEIUtil.getDisplayedInputItemStacks(recipeLayout.getItemStacks(), false,
                false);
        int circuitValue = GhostCircuitItemStackHandler.NO_CONFIG;
        for (ItemStack inputStack : itemInputs.values()) {
            if (IntCircuitIngredient.isIntegratedCircuit(inputStack)) {
                circuitValue = IntCircuitIngredient.getCircuitConfiguration(inputStack);
                break;
            }
        }
        ghostCircuitConfig.accept(circuitValue);
        return null;
    }

    @SideOnly(Side.CLIENT)
    public void setConfig(int index, @Nullable FluidStack stack) {
        setConfig(index, stack == null ? null : GenericStack.fromFluidStack(stack));
    }
}
