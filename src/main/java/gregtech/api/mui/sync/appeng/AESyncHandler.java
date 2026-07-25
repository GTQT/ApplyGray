package gregtech.api.mui.sync.appeng;

import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.IConfigurableSlot;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.utils.serialization.IByteBufAdapter;
import gregtech.api.mui.sync.RecipeSyncHandler;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.IntConsumer;
import java.util.function.LongBinaryOperator;

public abstract class AESyncHandler extends RecipeSyncHandler {

    public static final int SLOT_SYNC_ID = 0;
    public static final int SET_CONFIG_ID = 1;
    public static final int CLEAR_CONFIG_ID = 2;
    public static final int BULK_CLEAR_CONFIG_ID = 3;
    public static final int CHANGE_CONFIG_AMOUNT_ID = 4;
    public static final int BULK_CONFIG_AMOUNT_CHANGE_ID = 5;
    public static final int SYNC_CONFIG_AMOUNT_FROM_POPUP = 20;

    protected final boolean isStocking;
    protected final IntConsumer ghostCircuitConfig;
    protected final @NotNull IConfigurableSlot[] slots;
    private final @NotNull IConfigurableSlot[] cached;
    private final Int2ObjectMap<@NotNull IConfigurableSlot> changeMap = new Int2ObjectOpenHashMap<>();

    private final IByteBufAdapter<GenericStack> byteBufAdapter;

    @Nullable
    private final Runnable dirtyNotifier;

    public AESyncHandler(IConfigurableSlot[] slots, boolean isStocking, @Nullable Runnable dirtyNotifier,
                         @NotNull IntConsumer ghostCircuitConfig) {
        this.slots = slots;
        this.isStocking = isStocking;
        this.ghostCircuitConfig = ghostCircuitConfig;
        this.dirtyNotifier = dirtyNotifier;
        this.cached = initializeCache();
        this.byteBufAdapter = initializeByteBufAdapter();
    }

    protected abstract @NotNull IConfigurableSlot @NotNull [] initializeCache();

    protected abstract @NotNull IByteBufAdapter<GenericStack> initializeByteBufAdapter();

    public abstract boolean isStackValidForSlot(int index, @Nullable GenericStack stack);

    @Override
    public void detectAndSendChanges(boolean init) {
        for (int index = 0; index < slots.length; index++) {
            IConfigurableSlot slot = slots[index];
            IConfigurableSlot cache = cached[index];

            GenericStack newConfig = slot.getConfig();
            GenericStack cachedConfig = cache.getConfig();
            GenericStack newStock = slot.getStock();
            GenericStack cachedStock = cache.getStock();

            if (init || !areStackCountEquals(newConfig, cachedConfig) ||
                    !areStackCountEquals(newStock, cachedStock)) {
                IConfigurableSlot newCache = slot.copy();
                cached[index] = newCache;
                changeMap.put(index, newCache);
            }
        }

        if (!changeMap.isEmpty()) {
            syncToClient(SLOT_SYNC_ID, buf -> {
                buf.writeVarInt(changeMap.size());
                for (int index : changeMap.keySet()) {
                    buf.writeVarInt(index);
                    writeStack(buf, changeMap.get(index).getConfig());
                    writeStack(buf, changeMap.get(index).getStock());
                }
            });

            if (dirtyNotifier != null) {
                dirtyNotifier.run();
            }

            changeMap.clear();
        }
    }

    @Override
    public void readOnServer(int id, PacketBuffer buf) throws IOException {
        switch (id) {
            case CLEAR_CONFIG_ID -> slots[buf.readVarInt()].setConfig(null);

            case CHANGE_CONFIG_AMOUNT_ID -> {
                int index = buf.readVarInt();
                setConfigAmountDirect(index, buf.readLong());
            }

            case SET_CONFIG_ID -> {
                int index = buf.readVarInt();
                GenericStack newConfig = readStack(buf);
                if (isStackValidForSlot(index, newConfig)) {
                    slots[index].setConfig(newConfig);
                }
            }

            case BULK_CLEAR_CONFIG_ID -> {
                int indexFrom = buf.readVarInt();
                for (int index = indexFrom; index < slots.length; index++) {
                    slots[index].setConfig(null);
                }
            }

            case BULK_CONFIG_AMOUNT_CHANGE_ID -> {
                long[] changes = buf.readLongArray(new long[slots.length]);
                for (int index = 0; index < slots.length; index++) {
                    GenericStack config = slots[index].getConfig();
                    if (config != null && changes[index] > 0) {
                        slots[index].setConfig(withAmount(config, changes[index]));
                    }
                }
            }

            case SYNC_CONFIG_AMOUNT_FROM_POPUP -> {
                int index = buf.readVarInt();
                setConfigAmountDirect(index, buf.readLong());
            }

            default -> {
            }
        }
    }

    @Override
    public void readOnClient(int id, PacketBuffer buf) throws IOException {
        if (id == SLOT_SYNC_ID) {
            int size = buf.readVarInt();
            for (int i = 0; i < size; i++) {
                int index = buf.readVarInt();
                IConfigurableSlot slot = slots[index];
                slot.setConfig(readStack(buf));
                slot.setStock(readStack(buf));
            }
        } else if (id == SYNC_CONFIG_AMOUNT_FROM_POPUP) {
            int index = buf.readVarInt();
            GenericStack config = slots[index].getConfig();
            if (config != null) {
                slots[index].setConfig(withAmount(config, buf.readLong()));
                if (index < cached.length) {
                    cached[index] = null;
                }
            }
        }
    }

    private void setConfigAmountDirect(int index, long newAmount) {
        GenericStack config = slots[index].getConfig();
        if (config != null && newAmount > 0) {
            slots[index].setConfig(withAmount(config, newAmount));
            syncToClient(SYNC_CONFIG_AMOUNT_FROM_POPUP, buffer -> {
                buffer.writeVarInt(index);
                buffer.writeLong(newAmount);
            });
            if (dirtyNotifier != null) {
                dirtyNotifier.run();
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public void clearConfig(int index) {
        syncToServer(CLEAR_CONFIG_ID, buf -> buf.writeVarInt(index));
    }

    @SideOnly(Side.CLIENT)
    public void clearConfigFrom(int startingIndex) {
        syncToServer(BULK_CLEAR_CONFIG_ID, buf -> buf.writeVarInt(startingIndex));
    }

    @SideOnly(Side.CLIENT)
    public void setConfig(int index, @Nullable GenericStack newConfig) {
        syncToServer(SET_CONFIG_ID, buf -> {
            buf.writeVarInt(index);
            writeStack(buf, newConfig);
        });
    }

    @Nullable
    public GenericStack getConfig(int index) {
        return slots[index].getConfig();
    }

    public boolean hasConfig(int index) {
        return getConfig(index) != null;
    }

    public long getConfigAmount(int index) {
        GenericStack config = getConfig(index);
        return config == null ? 0 : config.amount();
    }

    @SideOnly(Side.CLIENT)
    public void setConfigAmount(int index, long newAmount) {
        syncToServer(CHANGE_CONFIG_AMOUNT_ID, buf -> {
            buf.writeVarInt(index);
            buf.writeLong(newAmount);
        });
    }

    @Nullable
    public GenericStack getStock(int index) {
        return slots[index].getStock();
    }

    @SideOnly(Side.CLIENT)
    public boolean modifyConfigAmounts(@NotNull LongBinaryOperator function) {
        long[] newAmounts = new long[slots.length];
        boolean anyChanged = false;
        for (int index = 0; index < slots.length; index++) {
            GenericStack config = slots[index].getConfig();
            if (config != null) {
                long newSize = function.applyAsLong(index, config.amount());
                if (newSize != config.amount() && newSize > 0) {
                    anyChanged = true;
                    newAmounts[index] = newSize;
                }
            }
        }

        if (anyChanged) {
            syncToServer(BULK_CONFIG_AMOUNT_CHANGE_ID, buf -> buf.writeLongArray(newAmounts));
        }

        return anyChanged;
    }

    public final boolean areStackCountEquals(@Nullable GenericStack stack1, @Nullable GenericStack stack2) {
        if (stack2 == stack1) {
            return true;
        }
        return stack1 != null && stack2 != null && stack1.amount() == stack2.amount() &&
                stack1.what().equals(stack2.what());
    }

    private void writeStack(PacketBuffer buffer, @Nullable GenericStack stack) throws IOException {
        buffer.writeBoolean(stack != null);
        if (stack != null) {
            byteBufAdapter.serialize(buffer, stack);
        }
    }

    @Nullable
    private GenericStack readStack(PacketBuffer buffer) throws IOException {
        return buffer.readBoolean() ? byteBufAdapter.deserialize(buffer) : null;
    }

    private static GenericStack withAmount(GenericStack stack, long amount) {
        return new GenericStack(stack.what(), amount);
    }
}
