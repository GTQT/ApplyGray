package gregtech.common.metatileentities.multi.multiblockpart.appeng.slot;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandlerModifiable;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class ExportOnlyAEItemSlot extends ExportOnlyAESlot implements IItemHandlerModifiable {

    @Nullable
    protected Consumer<Integer> trigger;

    public ExportOnlyAEItemSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
        super(config, stock);
    }

    public ExportOnlyAEItemSlot() {
        super();
    }

    public void setTrigger(@Nullable Consumer<Integer> trigger) {
        this.trigger = trigger;
    }

    @Override
    public void deserializeNBT(NBTTagCompound nbt) {
        this.config = readStack(nbt, CONFIG_TAG);
        this.stock = readStack(nbt, STOCK_TAG);
    }

    @Nullable
    private static GenericStack readStack(NBTTagCompound owner, String key) {
        return owner.hasKey(key) ? GenericStack.readTag(owner.getCompoundTag(key)) : null;
    }

    @Override
    public @NotNull ExportOnlyAEItemSlot copy() {
        return new ExportOnlyAEItemSlot(
                this.config == null ? null : copy(this.config),
                this.stock == null ? null : copy(this.stock));
    }

    @Override
    public void decrementStock(long amount) {
        if (stock == null) {
            return;
        }
        setStack(copy(stock, Math.max(0, stock.amount() - amount)));
    }

    @Override
    public void setStackInSlot(int slot, @NotNull ItemStack stack) {
    }

    @Override
    public int getSlots() {
        return 1;
    }

    @NotNull
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot == 0 && this.stock != null && this.stock.what() instanceof AEItemKey itemKey) {
            return itemKey.toStack(saturatingInt(this.stock.amount()));
        }
        return ItemStack.EMPTY;
    }

    @NotNull
    @Override
    public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        return stack;
    }

    @NotNull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot != 0 || this.stock == null || !(this.stock.what() instanceof AEItemKey itemKey)) {
            return ItemStack.EMPTY;
        }

        int extracted = (int) Math.min(this.stock.amount(), amount);
        ItemStack result = itemKey.toStack(extracted);
        if (!simulate) {
            long remaining = this.stock.amount() - extracted;
            this.stock = remaining == 0 ? null : copy(this.stock, remaining);
            notifyChanged();
        }
        return result;
    }

    @Override
    public void addStack(GenericStack stack) {
        if (!(stack.what() instanceof AEItemKey)) {
            return;
        }
        if (this.stock == null || !this.stock.what().equals(stack.what())) {
            this.stock = copy(stack);
        } else {
            this.stock = new GenericStack(this.stock.what(), this.stock.amount() + stack.amount());
        }
        notifyChanged();
    }

    @Override
    public void setStack(@Nullable GenericStack stack) {
        if (stack != null && !(stack.what() instanceof AEItemKey)) {
            return;
        }
        if (this.stock == null && stack == null) {
            return;
        }
        this.stock = stack == null || stack.amount() <= 0 ? null : copy(stack);
        notifyChanged();
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    private void notifyChanged() {
        if (this.trigger != null) {
            this.trigger.accept(0);
        }
    }

    private static int saturatingInt(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, amount);
    }
}
