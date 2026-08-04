package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.integration.ae2.ExactPatternInputRegistry;
import gregtech.api.capability.IDataStickIntractable;
import gregtech.api.util.GTLog;
import gregtech.api.util.Mods;
import gregtech.api.util.TextFormattingUtil;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;
import gregtech.integration.ae2.GTCircuitHelper;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.items.ItemStackHandler;

import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.KeyCounter;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for AE2 pattern registrars that only register patterns and
 * forward pushed materials to a master PatternProvider. No local item/fluid storage.
 * <p>
 * Subclasses provide pattern generation logic via {@link #createPatterns()}.
 */
public abstract class MetaTileEntityAEPatternRegistrar extends MetaTileEntityAEHostablePart
        implements ICraftingProvider, IDataStickIntractable {

    // UI icons for subclass GUI pages
    protected final IDrawable HATCH = new ItemDrawable(getStackForm())
            .asIcon().size(16);
    protected final IDrawable PROXY = new ItemDrawable(Mods.AppliedEnergistics2.getItem("interface"))
            .asIcon().size(16);
    protected final IDrawable FILTER = new ItemDrawable(Items.PAPER)
            .asIcon().size(16);
    protected final IDrawable LINK = new ItemDrawable(Items.COMPASS)
            .asIcon().size(16);

    @Nullable
    protected List<IPatternDetails> patternDetails;

    // Master connection
    @Nullable
    protected MetaTileEntityMEPatternProvider master;
    @Nullable
    protected BlockPos masterPos;
    protected boolean masterSet = false;
    protected boolean checkForMaster = true;

    // AE proxy mode
    protected boolean useProxy = false;
    protected BlockPos AEProxy_pos = new BlockPos(0, 0, 0);

    // Pattern sync flag
    protected boolean needPatternSync = true;

    // State flags used by subclass GUIs
    protected boolean blockedMode = true;

    protected boolean export = false;

    protected boolean autoCollapse;

    public MetaTileEntityAEPatternRegistrar(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, tier, false);
    }

    // ==================== Utility methods for subclass GUIs ====================

    public boolean isUseProxy() {
        return useProxy;
    }

    public void setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
    }

    public boolean isNeedPatternSync() {
        return needPatternSync;
    }

    public void setNeedPatternSync(boolean needPatternSync) {
        this.needPatternSync = needPatternSync;
    }

    public boolean isBlockedMode() {
        return blockedMode;
    }

    public void setBlockedMode(boolean blockedMode) {
        this.blockedMode = blockedMode;
    }

    public boolean isExport() {
        return export;
    }

    public void setExport(boolean export) {
        this.export = export;
    }

    public boolean isAutoCollapse() {
        return autoCollapse;
    }

    public void setAutoCollapse(boolean value) {
        this.autoCollapse = value;
    }

    // ==================== Master connection ====================

    protected void tryToSetMaster() {
        MetaTileEntityMEPatternProvider resolved = MasterNodeResolver.resolve(getWorld(), masterPos);
        if (resolved != null) {
            setMasterAndRegister(resolved);
        } else {
            this.master = null;
            this.checkForMaster = true;
        }
    }

    private void setMasterAndRegister(MetaTileEntityMEPatternProvider newMaster) {
        if (this.master != null && this.master != newMaster) {
            this.master.removeOrePrefixRegistrar(this);
        }
        this.master = newMaster;
        this.master.addOrePrefixRegistrar(this);
        this.checkForMaster = false;
    }

    public boolean hasMaster() {
        return master != null && master.isValid();
    }

    /**
     * Called by master when it is being removed from the world.
     */
    public void onMasterRemoved() {
        this.master = null;
        this.checkForMaster = true;
    }

    // ==================== AE2 ICraftingProvider ====================

    @Override
    public List<? extends IPatternDetails> getAvailablePatterns() {
        setPatternDetails();
        if (!isActive() || patternDetails == null) {
            return java.util.Collections.emptyList();
        }
        List<IPatternDetails> result = new ArrayList<>(patternDetails.size());
        for (IPatternDetails detail : patternDetails) {
            if (detail != null) {
                result.add(detail);
            }
        }
        return result;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder, int multiplier) {
        if (!isActive() || !hasMaster()) {
            return false;
        }
        return master.pushToBuffer(withProgrammableExtraInputs(inputHolder), null, null);
    }

    @Override
    public boolean isBusy() {
        if (!hasMaster()) return true;
        return master.isBusy();
    }

    /**
     * Wrap extraInput items (slots 1-8 in the InventoryCrafting) as ProgrammableCircuit
     * so the master's pushToBuffer() can route them to the circuit slot instead of item slots.
     * Slot 0 is the main input (consumable), slots 1+ are extraInput (non-consumable).
     */
    protected KeyCounter[] withProgrammableExtraInputs(KeyCounter[] inputHolder) {
        return inputHolder;
    }

    protected KeyCounter[] appendProgrammableExtraInputs(KeyCounter[] inputHolder, ItemStackHandler extraInputs) {
        KeyCounter extras = new KeyCounter();
        for (int slot = 0; slot < extraInputs.getSlots(); slot++) {
            ItemStack source = extraInputs.getStackInSlot(slot);
            ItemStack wrapped = wrapAsProgrammable(source);
            AEItemKey key = AEItemKey.of(wrapped == null ? ItemStack.EMPTY : wrapped);
            if (key != null) {
                extras.add(key, 1);
            }
        }
        if (extras.isEmpty()) {
            return inputHolder;
        }
        KeyCounter[] result = java.util.Arrays.copyOf(inputHolder, inputHolder.length + 1);
        result[inputHolder.length] = extras;
        return result;
    }

    @Nullable
    protected ItemStack wrapAsProgrammable(ItemStack source) {
        if (source.isEmpty() || MetaItems.PROGRAMMABLE_CIRCUIT == null) return null;
        ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
        ItemStack wrappedItem = source.copy();
        wrappedItem.setCount(1);
        ProgrammableCircuit.wrap(wrappedItem, programmable);
        return programmable;
    }

    // ==================== Pattern generation (to be implemented by subclasses) ====================

    /**
     * Generate the list of virtual encoded patterns. Called by {@link #setPatternDetails()}.
     */
    protected abstract List<ItemStack> createPatterns();

    /**
     * Refresh pattern details from generated patterns.
     */
    public void setPatternDetails() {
        patternDetails = new ArrayList<>();
        List<ItemStack> patternSlot = createPatterns();
        for (int i = 0; i < patternSlot.size(); i++) {
            ItemStack pattern = patternSlot.get(i);
            if (pattern.isEmpty()) {
                patternDetails.add(i, null);
                continue;
            }

            IPatternDetails detail = PatternDetailsHelper.decodePattern(pattern, getWorld());
            patternDetails.add(i, detail);
            if (detail != null) {
                ExactPatternInputRegistry.registerPattern(detail);
            }
        }
    }

    public boolean requestPatternUpdate() {
        if (!isActive()) {
            return true;
        }
        ICraftingProvider.requestUpdate(getMainNode());
        return false;
    }

    @Override
    public boolean canMergePatternPush(IPatternDetails patternDetails) {
        return false;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails patternDetails, int maxMultiplier) {
        return 0;
    }

    public boolean isPowered() {
        return getMainNode().isPowered();
    }

    public boolean isActive() {
        return getMainNode().isActive();
    }

    @Override
    public void gridChanged() {
        setNeedPatternSync(true);
    }

    // ==================== Lifecycle ====================

    @Override
    public void update() {
        super.update();
        if (!getWorld().isRemote) {
            if (getOffsetTimer() % 20 == 0) {
                if (checkForMaster && !hasMaster()) {
                    tryToSetMaster();
                }
            }
            if (isWorkingEnabled() && isOnline && shouldSyncME()) {
                if (isNeedPatternSync()) {
                    setNeedPatternSync(requestPatternUpdate());
                }
            }
        }
    }

    @Override
    public void onRemoval() {
        if (this.master != null) {
            this.master.removeOrePrefixRegistrar(this);
        }
        super.onRemoval();
    }

    // ==================== Data Stick ====================

    @Override
    public void onDataStickLeftClick(EntityPlayer player, ItemStack dataStick) {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound cribTag = new NBTTagCompound();
        cribTag.setInteger("MainX", getPos().getX());
        cribTag.setInteger("MainY", getPos().getY());
        cribTag.setInteger("MainZ", getPos().getZ());
        tag.setTag("BudgetCRIB", cribTag);
        dataStick.setTagCompound(tag);
        dataStick.setTranslatableName("gregtech.machine.budget_crib.data_stick_name");
        player.sendStatusMessage(new TextComponentTranslation("gregtech.machine.budget_crib.data_stick_use"), true);
    }

    @Override
    public boolean onDataStickRightClick(EntityPlayer player, ItemStack dataStick) {
        NBTTagCompound tag = dataStick.getTagCompound();
        if (tag == null || !tag.hasKey("BudgetCRIB")) return false;

        NBTTagCompound cribTag = tag.getCompoundTag("BudgetCRIB");
        // Unregister from old master before switching
        if (this.master != null) {
            this.master.removeOrePrefixRegistrar(this);
        }
        this.masterPos = new BlockPos(
                cribTag.getInteger("MainX"),
                cribTag.getInteger("MainY"),
                cribTag.getInteger("MainZ"));
        this.masterSet = true;
        this.master = null;
        this.checkForMaster = true;

        player.sendStatusMessage(new TextComponentTranslation(
                "gregtech.machine.pattern_mapping_slave.data_stick_use",
                TextFormattingUtil.formatNumbers(masterPos.getX()),
                TextFormattingUtil.formatNumbers(masterPos.getY()),
                TextFormattingUtil.formatNumbers(masterPos.getZ())), true);

        tryToSetMaster();
        return true;
    }

    // ==================== NBT ====================

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("MasterSet", masterSet);
        if (masterPos != null) {
            data.setInteger("MasterX", masterPos.getX());
            data.setInteger("MasterY", masterPos.getY());
            data.setInteger("MasterZ", masterPos.getZ());
        }
        data.setBoolean("useProxy", useProxy);
        data.setInteger("aeProxy_x", AEProxy_pos.getX());
        data.setInteger("aeProxy_y", AEProxy_pos.getY());
        data.setInteger("aeProxy_z", AEProxy_pos.getZ());
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.masterSet = data.getBoolean("MasterSet");
        if (masterSet) {
            this.masterPos = new BlockPos(
                    data.getInteger("MasterX"),
                    data.getInteger("MasterY"),
                    data.getInteger("MasterZ"));
        }
        this.useProxy = data.getBoolean("useProxy");
        this.AEProxy_pos = new BlockPos(
                data.getInteger("aeProxy_x"),
                data.getInteger("aeProxy_y"),
                data.getInteger("aeProxy_z"));
    }

    // ==================== Sync ====================

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeBoolean(masterSet);
        if (masterPos != null) {
            buf.writeBlockPos(masterPos);
        }
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.masterSet = buf.readBoolean();
        if (masterSet) {
            this.masterPos = buf.readBlockPos();
        }
    }
}
