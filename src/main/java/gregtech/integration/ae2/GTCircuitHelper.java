package gregtech.integration.ae2;

import gregtech.api.recipes.ingredients.IntCircuitIngredient;

import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;

import org.jetbrains.annotations.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import ae2.api.stacks.GenericStack;

/**
 * Shared programmable-circuit support for Supergiant pattern import and editing.
 */
public final class GTCircuitHelper {

    private static final ThreadLocal<Boolean> CURRENT_JEI_INGREDIENT_NOT_CONSUMABLE =
            ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Boolean> PATTERN_TRANSFER_ENABLED = ThreadLocal.withInitial(() -> false);

    private GTCircuitHelper() {}

    public static void setCurrentJeiIngredientNotConsumable(boolean notConsumable) {
        CURRENT_JEI_INGREDIENT_NOT_CONSUMABLE.set(notConsumable);
    }

    public static void clearCurrentJeiIngredientNotConsumable() {
        CURRENT_JEI_INGREDIENT_NOT_CONSUMABLE.remove();
    }

    public static void beginPatternTransfer(@Nullable EntityPlayer player, boolean doTransfer) {
        PATTERN_TRANSFER_ENABLED.set(doTransfer && hasToolkitInInventory(player) && isProgrammableCircuitAvailable());
    }

    public static void endPatternTransfer() {
        PATTERN_TRANSFER_ENABLED.remove();
    }

    public static boolean isPatternTransferEnabled() {
        return Boolean.TRUE.equals(PATTERN_TRANSFER_ENABLED.get());
    }

    public static boolean isProgrammableCircuit(ItemStack stack) {
        return MetaItems.PROGRAMMABLE_CIRCUIT != null
                && stack != null
                && !stack.isEmpty()
                && MetaItems.PROGRAMMABLE_CIRCUIT.isItemEqual(stack);
    }

    public static boolean isIntegratedCircuit(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return IntCircuitIngredient.isIntegratedCircuit(stack)
                || Boolean.TRUE.equals(CURRENT_JEI_INGREDIENT_NOT_CONSUMABLE.get())
                        && !stack.isEmpty()
                        && !isProgrammableCircuit(stack);
    }

    @Nullable
    public static GenericStack wrapItemAsProgrammable(ItemStack sourceItem) {
        ItemStack wrapped = wrapItemAsProgrammableStack(sourceItem);
        return wrapped == null ? null : GenericStack.fromItemStack(wrapped);
    }

    @Nullable
    public static ItemStack wrapItemAsProgrammableStack(ItemStack sourceItem) {
        if (sourceItem.isEmpty()) {
            return null;
        }

        if (MetaItems.PROGRAMMABLE_CIRCUIT == null) {
            return sourceItem.copy();
        }

        final ItemStack wrappedItem;
        if (IntCircuitIngredient.isIntegratedCircuit(sourceItem)) {
            final int config = IntCircuitIngredient.getCircuitConfiguration(sourceItem);
            wrappedItem = IntCircuitIngredient.getIntegratedCircuit(config);
        } else {
            wrappedItem = sourceItem.copy();
            wrappedItem.setCount(1);
        }

        final ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
        ProgrammableCircuit.wrap(wrappedItem, programmable);
        return programmable;
    }

    public static boolean hasToolkitInInventory(@Nullable EntityPlayer player) {
        if (player == null || MetaItems.PROGRAMMING_TOOLKIT == null) {
            return false;
        }

        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            final ItemStack invStack = player.inventory.getStackInSlot(i);
            if (!invStack.isEmpty() && MetaItems.PROGRAMMING_TOOLKIT.isItemEqual(invStack)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static ItemStack getProgrammableCircuitStack() {
        if (MetaItems.PROGRAMMABLE_CIRCUIT == null) {
            return null;
        }
        return MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
    }

    public static boolean isProgrammableCircuitAvailable() {
        return MetaItems.PROGRAMMABLE_CIRCUIT != null;
    }
}
