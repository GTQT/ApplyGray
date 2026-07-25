package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.INotifiableHandler;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.mui.GTGuiTextures;
import gregtech.api.mui.GTGuis;
import gregtech.common.mui.widget.ScrollableTextWidget;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.config.Actionable;
import ae2.api.storage.MEStorage;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.drawable.IRichTextBuilder;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.List;
import java.util.function.Consumer;

public abstract class MetaTileEntityMEOutputBase extends MetaTileEntityAEHostableChannelPart
        implements IControllable {

    public final static String WORKING_TAG = "WorkingEnabled";

    protected boolean workingEnabled = true;
    protected List<GenericStack> internalBuffer;

    public MetaTileEntityMEOutputBase(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, tier, true);
    }

    @Override
    protected void initializeInventory() {
        super.initializeInventory();
        this.internalBuffer = new ObjectArrayList<>();
    }

    @Override
    public void update() {
        super.update();
        if (!getWorld().isRemote && workingEnabled && isOnline && shouldSyncME()) {
            if (this.internalBuffer.isEmpty()) return;

            MEStorage monitor = getMonitor();
            if (monitor == null) return;

            ListIterator<GenericStack> internalBufferIterator = internalBuffer.listIterator();
            while (internalBufferIterator.hasNext()) {
                GenericStack stackInBuffer = internalBufferIterator.next();
                long inserted = monitor.insert(stackInBuffer.what(), stackInBuffer.amount(), Actionable.MODULATE,
                        getActionSource());
                if (inserted < stackInBuffer.amount()) {
                    internalBufferIterator.set(new GenericStack(stackInBuffer.what(), stackInBuffer.amount() - inserted));
                } else {
                    internalBufferIterator.remove();
                }
            }
        }
    }

    @SideOnly(Side.CLIENT)
    protected abstract void addStackLine(@NotNull IRichTextBuilder<?> text,
                                         @NotNull GenericStack stack);

    protected final void addToBuffer(@NotNull GenericStack stack) {
        for (ListIterator<GenericStack> iterator = internalBuffer.listIterator(); iterator.hasNext();) {
            GenericStack buffered = iterator.next();
            if (buffered.what().equals(stack.what())) {
                iterator.set(GenericStack.sum(buffered, stack));
                return;
            }
        }
        internalBuffer.add(stack);
    }

    @Override
    public boolean usesMui2() {
        return true;
    }

    @Override
    public ModularPanel buildUI(PosGuiData guiData, PanelSyncManager panelSyncManager, UISettings settings) {
        BooleanSyncValue onlineSync = new BooleanSyncValue(this::isOnline);
        panelSyncManager.syncValue("online", 0, onlineSync);

        AEStackListSyncHandler bufferSync = new AEStackListSyncHandler();
        panelSyncManager.syncValue("buffer", 0, bufferSync);

        ScrollableTextWidget textList = new ScrollableTextWidget();
        bufferSync.setChangeListener(textList::markDirty);

        return GTGuis.createPanel(this, 176, 18 + 18 * 4 + 94)
                .child(IKey.lang(getMetaFullName())
                        .asWidget()
                        .pos(5, 5))
                .child(IKey.lang(() -> onlineSync.getBoolValue() ?
                                "gregtech.gui.me_network.online" : "gregtech.gui.me_network.offline")
                        .asWidget()
                        .marginLeft(5)
                        .widthRel(1.0f)
                        .top(15))
                .child(textList.pos(9, 25 + 4)
                        .size(158, 18 * 4 - 6)
                        .textBuilder(text -> bufferSync.cacheForEach(stack -> addStackLine(text, stack)))
                        .alignment(Alignment.TopLeft)
                        .background(GTGuiTextures.DISPLAY.asIcon()
                                .margin(-2, -2)))
                .child(SlotGroupWidget.playerInventory(false)
                        .left(7)
                        .bottom(7));
    }

    @Override
    public void onRemoval() {
        MEStorage monitor = getMonitor();
        if (monitor != null) {
            for (GenericStack stack : this.internalBuffer) {
                monitor.insert(stack.what(), stack.amount(), Actionable.MODULATE, this.getActionSource());
            }
        }

        super.onRemoval();
    }

    @Override
    public boolean isWorkingEnabled() {
        return this.workingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean workingEnabled) {
        this.workingEnabled = workingEnabled;

        World world = this.getWorld();
        if (world != null && !world.isRemote) {
            writeCustomData(GregtechDataCodes.WORKING_ENABLED, buf -> buf.writeBoolean(workingEnabled));
        }
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }

        return super.getCapability(capability, side);
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeBoolean(workingEnabled);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.workingEnabled = buf.readBoolean();
    }

    @Override
    protected boolean shouldSerializeInventories() {
        return false;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean(WORKING_TAG, this.workingEnabled);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        if (data.hasKey(WORKING_TAG)) {
            this.workingEnabled = data.getBoolean(WORKING_TAG);
        }
    }

    protected abstract static class InaccessibleInfiniteHandler implements INotifiableHandler {

        protected final List<MetaTileEntity> notifiableEntities = new ArrayList<>();
        protected final MetaTileEntity holder;

        public InaccessibleInfiniteHandler(@NotNull MetaTileEntity holder,
                                           @NotNull MetaTileEntity mte) {
            this.holder = holder;
            this.notifiableEntities.add(mte);
        }

        @Override
        public void addNotifiableMetaTileEntity(MetaTileEntity metaTileEntity) {
            this.notifiableEntities.add(metaTileEntity);
        }

        @Override
        public void removeNotifiableMetaTileEntity(MetaTileEntity metaTileEntity) {
            this.notifiableEntities.remove(metaTileEntity);
        }

        protected void trigger() {
            this.holder.markDirty();
            for (MetaTileEntity metaTileEntity : this.notifiableEntities) {
                if (metaTileEntity != null && metaTileEntity.isValid()) {
                    this.addToNotifiedList(metaTileEntity, this, true);
                }
            }
        }
    }

    protected class AEStackListSyncHandler extends SyncHandler {

        private final ObjectArrayList<GenericStack> cache = new ObjectArrayList<>();
        private final IntSet changedIndexes = new IntOpenHashSet();
        @Nullable
        private Runnable changeListener;

        @Override
        public void detectAndSendChanges(boolean init) {
            int sourceSize = internalBuffer.size();
            boolean cacheSizeChange = cache.size() != sourceSize;
            if (cacheSizeChange) {
                cache.size(sourceSize);
            }

            for (int index = 0; index < internalBuffer.size(); index++) {
                GenericStack newStack = internalBuffer.get(index);
                GenericStack cachedStack = cache.get(index);

                if (init || !newStack.equals(cachedStack)) {
                    GenericStack copy = new GenericStack(newStack.what(), newStack.amount());
                    changedIndexes.add(index);
                    cache.set(index, copy);
                }
            }

            if (!changedIndexes.isEmpty() || cacheSizeChange) {
                syncToClient(0, buf -> {
                    buf.writeVarInt(cache.size());
                    buf.writeVarInt(changedIndexes.size());

                    for (int index : changedIndexes) {
                        buf.writeVarInt(index);
                        GenericStack.writeBuffer(cache.get(index), buf);
                    }
                });

                onChange();
                changedIndexes.clear();
            }
        }

        @Override
        public void readOnClient(int id, PacketBuffer buf) throws IOException {
            if (id != 0) return;

            cache.size(buf.readVarInt());
            int changed = buf.readVarInt();
            for (int ignore = 0; ignore < changed; ignore++) {
                int index = buf.readVarInt();
                GenericStack newStack = GenericStack.readBuffer(buf);
                if (newStack != null) {
                    cache.set(index, newStack);
                }
            }

            onChange();
        }

        @Override
        public void readOnServer(int id, PacketBuffer buf) {
            // This sync handler is Server -> Client only.
        }

        public void setChangeListener(@NotNull Runnable listener) {
            this.changeListener = listener;
        }

        private void onChange() {
            if (changeListener != null) {
                changeListener.run();
            }
        }

        public void cacheForEach(@NotNull Consumer<GenericStack> consumer) {
            for (GenericStack stack : cache) {
                consumer.accept(stack);
            }
        }
    }
}
