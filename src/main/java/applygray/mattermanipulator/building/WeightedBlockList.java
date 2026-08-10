package applygray.mattermanipulator.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/** Ordered weighted material list used by a geometry role. */
public final class WeightedBlockList {

    private static final String KEY_ENTRIES = "Entries";
    private static final String KEY_SPEC = "Spec";
    private static final String KEY_WEIGHT = "Weight";

    private final List<Entry> entries = new ArrayList<>();

    public WeightedBlockList(BlockSpec... defaults) {
        for (BlockSpec spec : defaults) {
            add(spec);
        }
    }

    public void add(BlockSpec spec) {
        add(spec, 1);
    }

    /** Replaces this role's weighted choices with one exact block material. */
    public void setSingle(BlockSpec spec) {
        Objects.requireNonNull(spec, "spec");
        entries.clear();
        entries.add(new Entry(spec, 1));
    }

    public void add(BlockSpec spec, int weight) {
        Objects.requireNonNull(spec, "spec");
        if (weight <= 0) throw new IllegalArgumentException("weight must be positive");

        for (int index = 0; index < entries.size(); index++) {
            Entry existing = entries.get(index);
            if (existing.spec.equals(spec)) {
                entries.set(index, new Entry(spec, Math.addExact(existing.weight, weight)));
                return;
            }
        }
        entries.add(new Entry(spec, weight));
    }

    public void clear() {
        entries.clear();
    }

    public boolean contains(BlockSpec spec) {
        return entries.stream().anyMatch(entry -> entry.spec.equals(spec));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public BlockSpec select(Random random) {
        Objects.requireNonNull(random, "random");
        if (entries.isEmpty()) return BlockSpec.air();

        int totalWeight = 0;
        for (Entry entry : entries) {
            totalWeight = Math.addExact(totalWeight, entry.weight);
        }
        int selector = random.nextInt(totalWeight);
        for (Entry entry : entries) {
            if (selector < entry.weight) return entry.spec;
            selector -= entry.weight;
        }
        throw new IllegalStateException("Weighted block list has no selectable entry");
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        NBTTagList serializedEntries = new NBTTagList();
        for (Entry entry : entries) {
            NBTTagCompound serializedEntry = new NBTTagCompound();
            serializedEntry.setTag(KEY_SPEC, entry.spec.writeToNbt());
            serializedEntry.setInteger(KEY_WEIGHT, entry.weight);
            serializedEntries.appendTag(serializedEntry);
        }
        data.setTag(KEY_ENTRIES, serializedEntries);
        return data;
    }

    public static WeightedBlockList readFromNbt(NBTTagCompound data, BlockSpec defaultSpec) {
        WeightedBlockList list = new WeightedBlockList();
        if (data != null && data.hasKey(KEY_ENTRIES, Constants.NBT.TAG_LIST)) {
            NBTTagList serializedEntries = data.getTagList(KEY_ENTRIES, Constants.NBT.TAG_COMPOUND);
            for (int index = 0; index < serializedEntries.tagCount(); index++) {
                NBTTagCompound serializedEntry = serializedEntries.getCompoundTagAt(index);
                if (!serializedEntry.hasKey(KEY_SPEC, Constants.NBT.TAG_COMPOUND) ||
                        !serializedEntry.hasKey(KEY_WEIGHT, Constants.NBT.TAG_INT)) {
                    continue;
                }
                int weight = serializedEntry.getInteger(KEY_WEIGHT);
                if (weight > 0) list.add(BlockSpec.readFromNbt(serializedEntry.getCompoundTag(KEY_SPEC)), weight);
            }
        }
        if (list.entries.isEmpty() && defaultSpec != null) list.add(defaultSpec);
        return list;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WeightedBlockList list && entries.equals(list.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    public record Entry(BlockSpec spec, int weight) {

        public Entry {
            Objects.requireNonNull(spec, "spec");
            if (weight <= 0) throw new IllegalArgumentException("weight must be positive");
        }
    }
}
