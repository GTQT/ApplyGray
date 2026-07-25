package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import gregtech.api.capability.impl.FluidTankList;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.storage.MEStorage;
import ae2.api.stacks.GenericStack;

import static gregtech.api.util.GTQTUtility.isFluidTankListEmpty;
import static gregtech.api.util.GTQTUtility.isInventoryEmpty;

public class Utils {
    public static void returnItems(MEStorage monitor, IItemHandlerModifiable itemHandler,
                                   IActionSource source) {
        if (isInventoryEmpty(itemHandler)) return;

        if (monitor == null) return;

        for (int x = 0; x < itemHandler.getSlots(); x++) {
            ItemStack itemStack = itemHandler.getStackInSlot(x);
            if (itemStack.isEmpty()) continue;

            GenericStack aeStack = GenericStack.fromItemStack(itemStack);
            if (aeStack == null) continue;

            long inserted = monitor.insert(aeStack.what(), aeStack.amount(), Actionable.MODULATE, source);
            if (inserted < aeStack.amount()) {
                itemStack.setCount((int) Math.min(Integer.MAX_VALUE, aeStack.amount() - inserted));
            } else {
                itemHandler.setStackInSlot(x, ItemStack.EMPTY);
            }
        }
    }

    public static void returnFluids(MEStorage monitor, FluidTankList fluidTankList,
                                    IActionSource source) {
        if (isFluidTankListEmpty(fluidTankList)) return;

        if (monitor == null) return;

        for (int x = 0; x < fluidTankList.getTanks(); x++){
            FluidStack exportFluid = fluidTankList.getTankAt(x).getFluid();
            if (exportFluid != null) {
                GenericStack aeFluid = GenericStack.fromFluidStack(exportFluid);
                if (aeFluid != null) {
                    long inserted = monitor.insert(aeFluid.what(), aeFluid.amount(), Actionable.MODULATE, source);
                    fluidTankList.getTankAt(x).drain((int) Math.min(inserted, Integer.MAX_VALUE), true);
                }
            }
        }
    }
}
