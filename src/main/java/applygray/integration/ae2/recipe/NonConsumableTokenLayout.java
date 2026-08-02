package applygray.integration.ae2.recipe;

import gregtech.common.items.behaviors.ProgrammableCircuit;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import net.minecraftforge.common.util.Constants;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Positional mapping from AE programmable-circuit token inputs to the original non-consumable GT item requirements.
 */
public final class NonConsumableTokenLayout {

    public static final NonConsumableTokenLayout EMPTY = new NonConsumableTokenLayout(Collections.emptyList());
    private static final int SERIALIZATION_VERSION = 1;

    private final List<Slot> slots;

    public NonConsumableTokenLayout(List<Slot> slots) {
        List<Slot> copied = new ArrayList<>(slots == null ? 0 : slots.size());
        if (slots != null) {
            for (Slot slot : slots) {
                if (slot != null) copied.add(slot);
            }
        }
        this.slots = Collections.unmodifiableList(copied);
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public int getRequiredVirtualSlots() {
        return slots.size();
    }

    public List<Slot> getSlots() {
        return slots;
    }

    /**
     * Decodes actual AE inputs without mutating the buffer. A null result is unsafe and must reject the push.
     */
    @Nullable
    public List<ItemStack> decode(KeyCounter[] inputHolders) {
        return decode(inputHolders, 1);
    }

    /**
     * Decodes inputs for a merged AE2 push. Each logical token slot must contain exactly one token per execution,
     * while the execution buffer needs only one copy of the decoded non-consumable catalyst.
     */
    @Nullable
    public List<ItemStack> decode(KeyCounter[] inputHolders, int batchMultiplier) {
        if (batchMultiplier <= 0) return null;
        if (slots.isEmpty()) return Collections.emptyList();
        if (inputHolders == null) return null;

        List<ItemStack> decoded = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.inputIndex < 0 || slot.inputIndex >= inputHolders.length) return null;
            KeyCounter holder = inputHolders[slot.inputIndex];
            if (holder == null || holder.size() != 1) return null;

            AEKey key = null;
            long amount = 0;
            for (var entry : holder) {
                key = entry.getKey();
                amount = entry.getLongValue();
                break;
            }
            if (!(key instanceof AEItemKey itemKey) || amount != batchMultiplier) return null;

            ItemStack token = itemKey.toStack(1);
            if (ProgrammableCircuit.getInstanceFor(token) == null || !ProgrammableCircuit.hasWrappedItem(token)) {
                return null;
            }
            ItemStack wrapped = ProgrammableCircuit.getWrappedItem(token).orElse(ItemStack.EMPTY);
            if (wrapped.isEmpty() || !slot.accepts(wrapped)) return null;

            ItemStack copy = wrapped.copy();
            copy.setCount(1);
            decoded.add(copy);
        }
        return decoded;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("Version", SERIALIZATION_VERSION);
        NBTTagList serializedSlots = new NBTTagList();
        for (Slot slot : slots) {
            serializedSlots.appendTag(slot.writeToNBT());
        }
        data.setTag("Slots", serializedSlots);
        return data;
    }

    @Nullable
    public static NonConsumableTokenLayout readFromNBT(NBTTagCompound data) {
        if (data == null || data.getInteger("Version") != SERIALIZATION_VERSION ||
                !data.hasKey("Slots", Constants.NBT.TAG_LIST)) {
            return null;
        }
        NBTTagList serializedSlots = data.getTagList("Slots", Constants.NBT.TAG_COMPOUND);
        List<Slot> slots = new ArrayList<>(serializedSlots.tagCount());
        for (int i = 0; i < serializedSlots.tagCount(); i++) {
            Slot slot = Slot.readFromNBT(serializedSlots.getCompoundTagAt(i));
            if (slot == null) return null;
            slots.add(slot);
        }
        return new NonConsumableTokenLayout(slots);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof NonConsumableTokenLayout layout && slots.equals(layout.slots);
    }

    @Override
    public int hashCode() {
        return slots.hashCode();
    }

    public static final class Slot {

        private final int inputIndex;
        private final List<ItemStack> candidates;

        public Slot(int inputIndex, List<ItemStack> candidates) {
            if (inputIndex < 0) throw new IllegalArgumentException("inputIndex must not be negative");
            List<ItemStack> copied = new ArrayList<>(candidates == null ? 0 : candidates.size());
            if (candidates != null) {
                for (ItemStack candidate : candidates) {
                    if (candidate == null || candidate.isEmpty()) continue;
                    ItemStack copy = candidate.copy();
                    copy.setCount(1);
                    copied.add(copy);
                }
            }
            if (copied.isEmpty()) throw new IllegalArgumentException("token slot requires at least one candidate");
            this.inputIndex = inputIndex;
            this.candidates = Collections.unmodifiableList(copied);
        }

        public int getInputIndex() {
            return inputIndex;
        }

        public List<ItemStack> getCandidates() {
            List<ItemStack> copy = new ArrayList<>(candidates.size());
            for (ItemStack candidate : candidates) copy.add(candidate.copy());
            return Collections.unmodifiableList(copy);
        }

        private boolean accepts(ItemStack stack) {
            for (ItemStack candidate : candidates) {
                if (ItemStack.areItemsEqual(candidate, stack) && ItemStack.areItemStackTagsEqual(candidate, stack)) {
                    return true;
                }
            }
            return false;
        }

        private NBTTagCompound writeToNBT() {
            NBTTagCompound data = new NBTTagCompound();
            data.setInteger("InputIndex", inputIndex);
            NBTTagList serializedCandidates = new NBTTagList();
            for (ItemStack candidate : candidates) {
                serializedCandidates.appendTag(candidate.writeToNBT(new NBTTagCompound()));
            }
            data.setTag("Candidates", serializedCandidates);
            return data;
        }

        @Nullable
        private static Slot readFromNBT(NBTTagCompound data) {
            if (data == null || !data.hasKey("InputIndex", Constants.NBT.TAG_INT) ||
                    !data.hasKey("Candidates", Constants.NBT.TAG_LIST)) {
                return null;
            }
            NBTTagList serializedCandidates = data.getTagList("Candidates", Constants.NBT.TAG_COMPOUND);
            List<ItemStack> candidates = new ArrayList<>(serializedCandidates.tagCount());
            for (int i = 0; i < serializedCandidates.tagCount(); i++) {
                ItemStack candidate = new ItemStack(serializedCandidates.getCompoundTagAt(i));
                if (!candidate.isEmpty()) candidates.add(candidate);
            }
            try {
                return new Slot(data.getInteger("InputIndex"), candidates);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Slot slot) || inputIndex != slot.inputIndex || candidates.size() != slot.candidates.size()) {
                return false;
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (!ItemStack.areItemsEqual(candidates.get(i), slot.candidates.get(i)) ||
                        !ItemStack.areItemStackTagsEqual(candidates.get(i), slot.candidates.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = inputIndex;
            for (ItemStack candidate : candidates) {
                result = 31 * result + Objects.hash(candidate.getItem(), candidate.getMetadata(),
                        candidate.getTagCompound());
            }
            return result;
        }
    }
}
