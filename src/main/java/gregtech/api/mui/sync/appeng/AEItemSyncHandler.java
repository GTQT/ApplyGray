package gregtech.api.mui.sync.appeng;

import applygray.api.mui.ApplyGrayByteBufAdapters;
import gregtech.api.capability.impl.GhostCircuitItemStackHandler;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.api.util.GTUtility;
import gregtech.api.util.JEIUtil;
import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEItemList;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEItemSlot;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.IConfigurableSlot;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.utils.serialization.IByteBufAdapter;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntConsumer;

public class AEItemSyncHandler extends AESyncHandler {

    protected final ExportOnlyAEItemList itemList;

    public AEItemSyncHandler(ExportOnlyAEItemList itemList, @Nullable Runnable dirtyNotifier,
                             @NotNull IntConsumer circuitChangeConsumer) {
        super(itemList.getInventory(), itemList.isStocking(), dirtyNotifier, circuitChangeConsumer);
        this.itemList = itemList;
    }

    @Override
    protected @NotNull IConfigurableSlot @NotNull [] initializeCache() {
        IConfigurableSlot[] cache = new IConfigurableSlot[slots.length];
        for (int index = 0; index < slots.length; index++) {
            cache[index] = new ExportOnlyAEItemSlot();
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
        if (!(stack.what() instanceof AEItemKey itemKey)) {
            return false;
        }
        return !isStocking || !itemList.hasStackInConfig(itemKey.getReadOnlyStack(), true);
    }

    @Override
    public IRecipeTransferError receiveRecipe(@NotNull IRecipeLayout recipeLayout, boolean maxTransfer,
                                              boolean simulate) {
        if (simulate) {
            return null;
        }

        List<ItemStack> itemInputs = new ArrayList<>(JEIUtil
                .getDisplayedInputItemStacks(recipeLayout.getItemStacks(), false, true)
                .values());
        GTUtility.collapseItemList(itemInputs);

        int circuitValue = GhostCircuitItemStackHandler.NO_CONFIG;
        Iterator<ItemStack> inputsIterator = itemInputs.iterator();
        while (inputsIterator.hasNext()) {
            ItemStack stack = inputsIterator.next();
            if (stack == null) {
                continue;
            }
            if (IntCircuitIngredient.isIntegratedCircuit(stack)) {
                if (hasToolkitInInventory() && MetaItems.PROGRAMMABLE_CIRCUIT != null) {
                    int config = IntCircuitIngredient.getCircuitConfiguration(stack);
                    ItemStack circuitStack = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
                    ProgrammableCircuit.wrap(IntCircuitIngredient.getIntegratedCircuit(config), circuitStack);
                    inputsIterator.remove();
                    itemInputs.add(circuitStack);
                } else {
                    circuitValue = IntCircuitIngredient.getCircuitConfiguration(stack);
                    inputsIterator.remove();
                }
                break;
            }
        }

        ghostCircuitConfig.accept(circuitValue);

        int lastSlotIndex;
        for (lastSlotIndex = 0; lastSlotIndex < itemInputs.size() && lastSlotIndex < slots.length; lastSlotIndex++) {
            setConfig(lastSlotIndex, GenericStack.fromItemStack(itemInputs.get(lastSlotIndex)));
        }
        clearConfigFrom(lastSlotIndex);
        return null;
    }

    @SideOnly(Side.CLIENT)
    public void setConfig(int index, @Nullable ItemStack stack) {
        setConfig(index, stack == null || stack.isEmpty() ? null : GenericStack.fromItemStack(stack));
    }

    private boolean hasToolkitInInventory() {
        EntityPlayer player = getSyncManager().getPlayer();
        if (player == null || MetaItems.PROGRAMMING_TOOLKIT == null) {
            return false;
        }

        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack invStack = player.inventory.getStackInSlot(i);
            if (!invStack.isEmpty() && MetaItems.PROGRAMMING_TOOLKIT.isItemEqual(invStack)) {
                return true;
            }
        }
        return false;
    }
}
