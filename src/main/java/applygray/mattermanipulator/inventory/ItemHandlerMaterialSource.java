package applygray.mattermanipulator.inventory;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/** Exact-material source backed by a Forge item handler. */
public final class ItemHandlerMaterialSource implements MaterialSource {

    private final String id;
    private final IItemHandler handler;

    public ItemHandlerMaterialSource(String id, IItemHandler handler) {
        this.id = Objects.requireNonNull(id, "id");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) {
        ItemStack requested = specification.toStack();
        if (requested.isEmpty() || amount <= 0) return 0L;

        long remaining = amount;
        long extracted = 0L;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (!matches(existing, requested)) continue;

            int requestedCount = (int) Math.min(remaining, existing.getCount());
            ItemStack result = handler.extractItem(slot, requestedCount, simulate);
            if (result.isEmpty()) continue;
            if (!matches(result, requested) || result.getCount() > requestedCount) {
                throw new IllegalStateException("Item handler " + id + " returned an unexpected extraction");
            }
            extracted += result.getCount();
            remaining -= result.getCount();
        }
        return extracted;
    }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) {
        ItemStack template = specification.toStack();
        if (template.isEmpty() || amount <= 0) return 0L;

        long remaining = amount;
        long inserted = 0L;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack offered = template.copy();
            offered.setCount((int) Math.min(remaining, offered.getMaxStackSize()));
            ItemStack remainder = handler.insertItem(slot, offered, simulate);
            if (!remainder.isEmpty() && !matches(remainder, template)) {
                throw new IllegalStateException("Item handler " + id + " returned an unexpected insertion remainder");
            }
            int accepted = offered.getCount() - (remainder.isEmpty() ? 0 : remainder.getCount());
            inserted += accepted;
            remaining -= accepted;
        }
        return inserted;
    }

    private static boolean matches(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty() && ItemStack.areItemsEqual(first, second) &&
                ItemStack.areItemStackTagsEqual(first, second);
    }
}
