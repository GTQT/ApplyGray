package applygray.integration.ae2;

import ae2.api.inventories.InternalInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Exposes a Forge item handler to Supergiant's pattern-access terminal without
 * duplicating the owning meta tile entity's inventory.
 */
public final class ItemHandlerInternalInventory implements InternalInventory {

    private final IItemHandlerModifiable delegate;
    private final Runnable onChanged;

    public ItemHandlerInternalInventory(IItemHandlerModifiable delegate, Runnable onChanged) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    @Override
    public int size() {
        return delegate.getSlots();
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slotIndex) {
        return delegate.getStackInSlot(slotIndex);
    }

    @Override
    public void setItemDirect(int slotIndex, ItemStack stack) {
        delegate.setStackInSlot(slotIndex, stack);
        onChanged.run();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return delegate.isItemValid(slot, stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        ItemStack remainder = delegate.insertItem(slot, stack, simulate);
        if (!simulate && remainder.getCount() != stack.getCount()) {
            onChanged.run();
        }
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack extracted = delegate.extractItem(slot, amount, simulate);
        if (!simulate && !extracted.isEmpty()) {
            onChanged.run();
        }
        return extracted;
    }
}
