package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.integration.ae2.ItemHandlerInternalInventory;

import gregtech.api.capability.DualHandler;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.capability.IDataStickIntractable;
import gregtech.api.capability.IGhostSlotConfigurable;
import gregtech.api.capability.impl.GhostCircuitItemStackHandler;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.api.util.GTLog;
import gregtech.api.util.GTUtility;
import gregtech.api.util.Mods;
import gregtech.common.items.MetaItems;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;

import ae2.api.config.Actionable;
import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.inventories.InternalInventory;
import ae2.api.networking.IGrid;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.helpers.patternprovider.PatternContainer;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static gregtech.api.capability.GregtechDataCodes.UPDATE_ACTIVE;
import static gregtech.api.util.GTQTUtility.isFluidTankListEmpty;
import static gregtech.api.util.GTQTUtility.isInventoryEmpty;

public abstract class MetaTileEntityAECraftingPart extends MetaTileEntityAEHostablePart implements ICraftingProvider,
                                                                                                   PatternContainer,
                                                                                                   IMultiblockAbilityPart<IItemHandlerModifiable>,
                                                                                                   IGhostSlotConfigurable,
                                                                                                   IDataStickIntractable {

    // ICONS
    protected final IDrawable CHEST = new ItemDrawable(Blocks.CHEST)
            .asIcon().size(16);
    protected final IDrawable HATCH = new ItemDrawable(getStackForm())
            .asIcon().size(16);
    protected final IDrawable PROXY = new ItemDrawable(Mods.AppliedEnergistics2.getItem("interface"))
            .asIcon().size(16);
    protected final IDrawable TERMINAL = new ItemDrawable(Items.NAME_TAG)
            .asIcon().size(16);
    protected final IDrawable FILTER = new ItemDrawable(Items.PAPER)
            .asIcon().size(16);

    @Nullable
    protected List<IPatternDetails> patternDetails;

    @Nullable
    protected GhostCircuitItemStackHandler circuitInventory;

    // AE
    protected BlockPos AEProxy_pos = new BlockPos(0, 0, 0);

    protected boolean useProxy;

    protected boolean export = false;

    protected boolean needPatternSync = true;

    protected boolean autoCollapse;

    protected boolean blockedMode = true;

    protected boolean advancedCircuit = false;
    // SLOTS
    protected IItemHandlerModifiable actualImportItems;
    @Nullable
    protected ItemStackHandler extraItem;
    @Nullable
    protected ItemStackHandler patternSlot;
    @Nullable
    protected DualHandler dualHandler;

    protected String showName = this.getMetaFullName();
    protected boolean hideInfo = false;

    public MetaTileEntityAECraftingPart(ResourceLocation metaTileEntityId, int tier, boolean isExportHatch) {
        super(metaTileEntityId, tier, isExportHatch);
    }

    public boolean isUseProxy() {
        return useProxy;
    }

    public void setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
    }

    public boolean isExport() {
        return export;
    }

    public void setExport(boolean export) {
        this.export = export;
    }

    public boolean isNeedPatternSync() {
        return needPatternSync;
    }

    public void setNeedPatternSync(boolean needPatternSync) {
        this.needPatternSync = needPatternSync;
    }

    public boolean isAutoCollapse() {
        return autoCollapse;
    }

    public boolean isBlockedMode() {
        return blockedMode;
    }

    public void setBlockedMode(boolean blockedMode) {
        this.blockedMode = blockedMode;
    }

    public boolean isAdvancedCircuit() {
        return advancedCircuit;
    }

    public void setAdvancedCircuit(boolean advancedCircuit) {
        this.advancedCircuit = advancedCircuit;
    }

    public IItemHandlerModifiable getActualImportItems() {
        return actualImportItems;
    }

    @Nullable
    public ItemStackHandler getPatternSlot() {
        return patternSlot;
    }

    @Nullable
    public DualHandler getDualHandler() {
        return dualHandler;
    }

    public String getShowName() {
        return showName;
    }

    public void setShowName(String showName) {
        this.showName = showName;
    }

    public boolean isHideInfo() {
        return hideInfo;
    }

    public void setHideInfo(boolean hideInfo) {
        this.hideInfo = hideInfo;
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeBoolean(isBlockedMode());
        buf.writeBoolean(this.export);
        buf.writeBoolean(isAutoCollapse());
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        setBlockedMode(buf.readBoolean());
        setExport(buf.readBoolean());
        setAutoCollapse(buf.readBoolean());
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == UPDATE_ACTIVE) {
            setBlockedMode(buf.readBoolean());
        }
    }

    @Override
    public boolean hasGhostCircuitInventory() {
        return true;
    }

    @Override
    public int getGhostCircuitConfig() {
        if (this.circuitInventory == null) {
            return 0;
        }
        return this.circuitInventory.getCircuitValue();
    }

    @Override
    public void setGhostCircuitConfig(int config) {
        if (this.circuitInventory == null || this.circuitInventory.getCircuitValue() == config) {
            return;
        }
        this.circuitInventory.setCircuitValue(config);
        if (!getWorld().isRemote) {
            markDirty();
        }
    }

    @Override
    public @Nullable MultiblockAbility<IItemHandlerModifiable> getAbility() {
        return MultiblockAbility.IMPORT_ITEMS;
    }

    @Override
    public void registerAbilities(@NotNull AbilityInstances abilityInstances) {
        abilityInstances.add(dualHandler);
    }

    public void returnToNet() {
        Utils.returnItems(getItemMonitor(), getImportItems(), getActionSource());
        Utils.returnFluids(getFluidMonitor(), getImportFluids(), getActionSource());
    }

    public boolean MEPatternChange() {
        if (!isActive()) {
            return true;
        }
        ICraftingProvider.requestUpdate(getMainNode());
        return false;
    }

    @Override
    public List<? extends IPatternDetails> getAvailablePatterns() {
        if (!isActive() || patternDetails == null) {
            return Collections.emptyList();
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
    public boolean canMergePatternPush(IPatternDetails patternDetails) {
        return false;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails patternDetails, int maxMultiplier) {
        return 0;
    }

    /**
     * 获取样板槽位数量，子类应覆盖以提供实际的槽位数量。
     *
     * @return 样板槽位数量，默认返回 0
     */
    protected int getPatternSlotCount() {
        return 0;
    }

    /**
     * 设置样板详情。遍历所有样板槽位，将有效的样板物品转换为样板详情。
     * 子类只需覆盖 {@link #getPatternSlotCount()} 提供正确的槽位数量即可复用此方法。
     * 如果子类有不同的样板生成逻辑，可以直接覆盖本方法。
     */
    public void setPatternDetails() {
        if (patternSlot == null || patternDetails == null) {
            return;
        }

        int slotCount = getPatternSlotCount();
        for (int i = 0; i < slotCount; i++) {
            ItemStack pattern = patternSlot.getStackInSlot(i);
            if (pattern.isEmpty()) {
                patternDetails.set(i, null);
                continue;
            }
            patternDetails.set(i, PatternDetailsHelper.decodePattern(pattern, getWorld()));
        }
    }

    public boolean addItemAndFluid(KeyCounter[] inputHolder) {
        for (KeyCounter holder : inputHolder) {
            for (var entry : holder) {
                if (!canAccept(entry.getKey(), entry.getLongValue())) {
                    return false;
                }
            }
        }
        for (KeyCounter holder : inputHolder) {
            for (var entry : holder) {
                if (!accept(entry.getKey(), entry.getLongValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder, int multiplier) {
        if (!isActive()) {
            GTLog.logger.debug("Machine is not active, rejecting pattern");
            return false;
        }

        boolean isEmpty = isInventoryEmpty(getImportItems()) && isFluidTankListEmpty(getImportFluids());

        if (!isEmpty && isBlockedMode() && !checkBlockedModeCompatibility(inputHolder)) {
                GTLog.logger.debug("Pattern rejected by blocked mode compatibility check");
                return false;
        }
        return addItemAndFluid(inputHolder);
    }

    protected boolean checkBlockedModeCompatibility(KeyCounter[] inputHolder) {
        for (KeyCounter holder : inputHolder) {
            for (var entry : holder) {
                AEKey key = entry.getKey();
                if (key instanceof AEFluidKey fluidKey) {
                    if (!GTUtility.hasMatchingFluid(fluidKey.toStack(1), getImportFluids())) {
                        return false;
                    }
                    continue;
                }
                if (!(key instanceof AEItemKey itemKey)) {
                    return false;
                }
                ItemStack itemStack = itemKey.toStack(1);
                if (MetaItems.INTEGRATED_CIRCUIT.isItemEqual(itemStack)) {
                    if (IntCircuitIngredient.getCircuitConfiguration(itemStack) != getGhostCircuitConfig()) {
                        return false;
                    }
                } else if (!GTUtility.hasMatchingItem(itemStack, importItems)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean canAccept(AEKey key, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (key instanceof AEFluidKey fluidKey) {
            return canFillFluid(fluidKey, amount);
        }
        if (!(key instanceof AEItemKey itemKey)) {
            return false;
        }
        ItemStack stack = itemKey.toStack(1);
        if (isAdvancedCircuit() && isOnline && MetaItems.INTEGRATED_CIRCUIT.isItemEqual(stack)) {
            return getNetworkStorage() != null && getNetworkStorage().insert(key, amount, Actionable.SIMULATE,
                    getActionSource()) == amount;
        }
        return canInsertItem(itemKey, amount);
    }

    private boolean accept(AEKey key, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (key instanceof AEFluidKey fluidKey) {
            return fillFluid(fluidKey, amount);
        }
        if (!(key instanceof AEItemKey itemKey)) {
            return false;
        }
        ItemStack stack = itemKey.toStack(1);
        if (isAdvancedCircuit() && isOnline && MetaItems.INTEGRATED_CIRCUIT.isItemEqual(stack)) {
            if (getNetworkStorage() == null || getNetworkStorage().insert(key, amount, Actionable.MODULATE,
                    getActionSource()) != amount) {
                return false;
            }
            setGhostCircuitConfig(IntCircuitIngredient.getCircuitConfiguration(stack));
            return true;
        }
        return insertItem(itemKey, amount);
    }

    private boolean canFillFluid(AEFluidKey fluidKey, long amount) {
        long remaining = amount;
        while (remaining > 0) {
            int part = (int) Math.min(Integer.MAX_VALUE, remaining);
            FluidStack fluid = fluidKey.toStack(part);
            if (getImportFluids().fill(fluid, false) != part) {
                return false;
            }
            remaining -= part;
        }
        return true;
    }

    private boolean fillFluid(AEFluidKey fluidKey, long amount) {
        long remaining = amount;
        while (remaining > 0) {
            int part = (int) Math.min(Integer.MAX_VALUE, remaining);
            if (getImportFluids().fill(fluidKey.toStack(part), true) != part) {
                return false;
            }
            remaining -= part;
        }
        return true;
    }

    private boolean canInsertItem(AEItemKey itemKey, long amount) {
        long remaining = amount;
        while (remaining > 0) {
            int part = (int) Math.min(Integer.MAX_VALUE, remaining);
            if (!insertItemStack(itemKey.toStack(part), true).isEmpty()) {
                return false;
            }
            remaining -= part;
        }
        return true;
    }

    private boolean insertItem(AEItemKey itemKey, long amount) {
        long remaining = amount;
        while (remaining > 0) {
            int part = (int) Math.min(Integer.MAX_VALUE, remaining);
            if (!insertItemStack(itemKey.toStack(part), false).isEmpty()) {
                return false;
            }
            remaining -= part;
        }
        return true;
    }

    private ItemStack insertItemStack(ItemStack stack, boolean simulate) {
        ItemStack remainder = stack;
        if (isAutoCollapse()) {
            for (int slot = 0; slot < importItems.getSlots() && !remainder.isEmpty(); slot++) {
                remainder = importItems.insertItem(slot, remainder, simulate);
            }
            return remainder;
        }

        for (int slot = 0; slot < importItems.getSlots() && !remainder.isEmpty(); slot++) {
            if (importItems.getStackInSlot(slot).isEmpty()) {
                remainder = importItems.insertItem(slot, remainder, simulate);
            }
        }
        for (int slot = 0; slot < importItems.getSlots() && !remainder.isEmpty(); slot++) {
            remainder = importItems.insertItem(slot, remainder, simulate);
        }
        return remainder;
    }

    @Override
    public boolean onScrewdriverClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing,
                                      CuboidRayTraceResult hitResult) {
        setAutoCollapse(!this.autoCollapse);

        if (!getWorld().isRemote) {
            if (this.autoCollapse) {
                playerIn.sendStatusMessage(new TextComponentTranslation("gregtech.bus.collapse_true"), true);
            } else {
                playerIn.sendStatusMessage(new TextComponentTranslation("gregtech.bus.collapse_false"), true);
            }
        }
        return true;
    }

    public void setAutoCollapse(boolean inverted) {
        autoCollapse = inverted;
        if (!getWorld().isRemote) {
            if (autoCollapse) {
                addNotifiedInput(super.getImportItems());
                addNotifiedInput(this.getImportFluids());
            }
            writeCustomData(GregtechDataCodes.TOGGLE_COLLAPSE_ITEMS,
                    packetBuffer -> packetBuffer.writeBoolean(autoCollapse));
            notifyBlockUpdate();
            markDirty();
        }
    }

    @Override
    public void addToolUsages(ItemStack stack, @Nullable World world, List<String> tooltip, boolean advanced) {
        tooltip.add(I18n.format("gregtech.tool_action.screwdriver.access_covers"));
        tooltip.add(I18n.format("gregtech.tool_action.screwdriver.auto_collapse"));
        tooltip.add(I18n.format("gregtech.tool_action.wrench.set_facing"));
        super.addToolUsages(stack, world, tooltip, advanced);
    }

    @Override
    public void gridChanged() {
        setNeedPatternSync(true);
    }

    public boolean isPowered() {
        return getMainNode().isPowered();
    }

    public boolean isActive() {
        return getMainNode().isActive();
    }

    @Override
    public boolean isBusy() {
        return isExport();
    }

    @Override
    public void onDataStickLeftClick(EntityPlayer player, ItemStack dataStick) {
        NBTTagCompound tag = new NBTTagCompound();

        tag.setTag("BudgetCRIB", writeLocationToTag());
        dataStick.setTagCompound(tag);
        dataStick.setTranslatableName("gregtech.machine.budget_crib.data_stick_name");
        player.sendStatusMessage(new TextComponentTranslation("gregtech.machine.budget_crib.data_stick_use"), true);
    }

    private NBTTagCompound writeLocationToTag() {
        NBTTagCompound tag = new NBTTagCompound();

        tag.setInteger("MainX", getPos().getX());
        tag.setInteger("MainY", getPos().getY());
        tag.setInteger("MainZ", getPos().getZ());

        return tag;
    }

    @Override
    public boolean onDataStickRightClick(EntityPlayer player, ItemStack dataStick) {
        NBTTagCompound tag = dataStick.getTagCompound();
        if (player.isSneaking() && tag != null && tag.hasKey("CommonPos")) {
            useProxy = false;
            readLocationFromTag(tag.getCompoundTag("CommonPos"));
            useProxy = true;
            player.sendStatusMessage(new TextComponentTranslation("gregtech.machine.me.proxy.data_stick_loaded"), true);
            return true;
        }

        // Keep right-click behavior aligned with mapping/proxy tooltip:
        // right-click provider writes master position to data stick.
        onDataStickLeftClick(player, dataStick);
        return true;
    }

    private void readLocationFromTag(NBTTagCompound tag) {
        AEProxy_pos = new BlockPos(tag.getInteger("MainX"), tag.getInteger("MainY"), tag.getInteger("MainZ"));
    }

    @Override
    public @Nullable IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public boolean isVisibleInTerminal() {
        return !hideInfo;
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        if (patternSlot == null) {
            return InternalInventory.empty();
        }
        return new ItemHandlerInternalInventory(patternSlot, this::onTerminalPatternInventoryChanged);
    }

    @Override
    public boolean containsPattern(AEItemKey pattern) {
        if (patternSlot == null) {
            return false;
        }
        for (int slot = 0; slot < patternSlot.getSlots(); slot++) {
            AEItemKey stored = AEItemKey.of(patternSlot.getStackInSlot(slot));
            if (pattern.equals(stored)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        AEItemKey icon = AEItemKey.of(getStackForm());
        return new PatternContainerGroup(icon, new TextComponentString(getTerminalDisplayName()), List.of());
    }

    private String getTerminalDisplayName() {
        if (getController() != null && getShowName().equals(getMetaFullName())) {
            return getController().getMetaFullName();
        }
        return getShowName();
    }

    private void onTerminalPatternInventoryChanged() {
        setPatternDetails();
        setNeedPatternSync(true);
        if (getWorld() != null && !getWorld().isRemote) {
            markDirty();
        }
    }
}
