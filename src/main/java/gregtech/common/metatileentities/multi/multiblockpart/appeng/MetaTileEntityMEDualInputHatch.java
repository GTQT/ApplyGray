package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.client.renderer.texture.ApplyGrayTextures;

import gregtech.api.capability.DualHandler;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.IDataStickIntractable;
import gregtech.api.capability.IGhostSlotConfigurable;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.capability.impl.GhostCircuitItemStackHandler;
import gregtech.api.capability.impl.ItemHandlerList;
import gregtech.api.capability.impl.NotifiableItemStackHandler;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.mui.GTGuiTextures;
import gregtech.api.mui.GTGuis;
import gregtech.api.mui.sync.appeng.AEFluidSyncHandler;
import gregtech.api.mui.sync.appeng.AEItemSyncHandler;
import gregtech.api.mui.sync.appeng.AESyncHandler;
import gregtech.api.mui.widget.GhostCircuitSlotWidget;
import gregtech.api.mui.widget.appeng.fluid.AEFluidConfigSlot;
import gregtech.api.mui.widget.appeng.fluid.AEFluidDisplaySlot;
import gregtech.api.mui.widget.appeng.item.AEItemConfigSlot;
import gregtech.api.mui.widget.appeng.item.AEItemDisplaySlot;
import gregtech.api.util.GTUtility;
import gregtech.api.util.Mods;
import gregtech.common.ConfigHolder;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEFluidList;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEFluidSlot;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEItemList;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEItemSlot;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAESlot;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.IExportOnlyAEStackList;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import ae2.api.config.Actionable;
import ae2.api.storage.MEStorage;
import ae2.api.stacks.GenericStack;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.SlotGroupWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Grid;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

import java.util.Arrays;
import java.util.List;

public class MetaTileEntityMEDualInputHatch extends MetaTileEntityAEHostablePart
        implements IMultiblockAbilityPart<DualHandler>, IControllable, IGhostSlotConfigurable,
                   IDataStickIntractable {

    public static final int CONFIG_SIZE = 16;
    public static final String WORKING_TAG = "WorkingEnabled";
    public static final String SYNC_HANDLER_NAME = "aeSync";
    public final static String ITEM_BUFFER_TAG = "ItemBuffer";
    public final static String FLUID_BUFFER_TAG = "FluidBuffer";
    private static final String REFRESH_RATE_TAG = "RefreshRate";
    private final IDrawable CONTROLLER_ICON = new ItemDrawable(Mods.AppliedEnergistics2.getItem("controller"))
            .asIcon().size(16);
    protected IExportOnlyAEStackList aeItemHandler;
    protected IExportOnlyAEStackList aeFluidHandler;
    protected GhostCircuitItemStackHandler circuitInventory;
    protected NotifiableItemStackHandler extraSlotInventory;
    protected boolean workingEnabled = true;

    public MetaTileEntityMEDualInputHatch(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, 6, false);
    }

    @Override
    protected void initializeInventory() {
        this.aeItemHandler = initializeItemAEHandler();
        this.aeFluidHandler = initializeFluidAEHandler();
        this.circuitInventory = new GhostCircuitItemStackHandler(this);

        this.extraSlotInventory = new NotifiableItemStackHandler(this, 1, this, false);
        this.extraSlotInventory.addNotifiableMetaTileEntity(this);
        super.initializeInventory();
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        if (this.shouldRenderOverlay()) {
            if (isOnline()) {
                ApplyGrayTextures.ME_DUAL_INPUT_HATCH.renderSided(getFrontFacing(), renderState, translation, pipeline);
            } else {
                ApplyGrayTextures.ME_DUAL_INPUT_HATCH_ACTIVE.renderSided(getFrontFacing(), renderState, translation, pipeline);
            }
        }
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityMEDualInputHatch(metaTileEntityId);
    }

    @Override
    protected FluidTankList createImportFluidHandler() {
        return new FluidTankList(false, getFluidAEHandler().getInventory());
    }

    @Override
    protected IItemHandlerModifiable createImportItemHandler() {
        return new ItemHandlerList(getItemAEHandler(), circuitInventory, extraSlotInventory);
    }

    @Override
    public void clearMachineInventory(@NotNull List<@NotNull ItemStack> itemBuffer) {
        ItemStack extraSlotStack = extraSlotInventory.getStackInSlot(0);
        if (!extraSlotStack.isEmpty()) {
            itemBuffer.add(extraSlotStack);
        }
    }

    protected @NotNull IExportOnlyAEStackList initializeItemAEHandler() {
        return new ExportOnlyAEItemList(this, CONFIG_SIZE, this.getController());
    }

    protected @NotNull IExportOnlyAEStackList initializeFluidAEHandler() {
        return new ExportOnlyAEFluidList(this, CONFIG_SIZE, this.getController());
    }

    public boolean isAutoPull() {
        return aeItemHandler.isAutoPull() || aeFluidHandler.isAutoPull();
    }

    public boolean isStocking() {
        return aeItemHandler.isStocking() || aeFluidHandler.isStocking();
    }

    @Override
    public void update() {
        super.update();
        if (!getWorld().isRemote && workingEnabled && isOnline && shouldSyncME()) {
            operateOnME();
        }
    }

    protected void operateOnME() {
        syncME();
    }

    protected void syncME() {
        MEStorage monitor = getNetworkStorage();
        if (monitor != null) {
            for (ExportOnlyAESlot slot : aeItemHandler.getInventory()) {
                processSlot(slot, monitor);
            }
            for (ExportOnlyAESlot slot : aeFluidHandler.getInventory()) {
                processSlot(slot, monitor);
            }
        }
    }

    /**
     * 通用槽位处理逻辑，避免代码重复
     */
    private void processSlot(ExportOnlyAESlot slot, MEStorage monitor) {
        GenericStack exceedStack = slot.exceedStack();
        if (exceedStack != null) {
            long inserted = monitor.insert(exceedStack.what(), exceedStack.amount(), Actionable.MODULATE,
                    getActionSource());
            slot.decrementStock(inserted);
        }

        GenericStack requestStack = slot.requestStack();
        if (requestStack == null) return;

        long extracted = monitor.extract(requestStack.what(), requestStack.amount(), Actionable.MODULATE,
                getActionSource());
        if (extracted > 0) {
            slot.addStack(new GenericStack(requestStack.what(), extracted));
        }
    }

    @Override
    public void onRemoval() {
        flushInventory();
        super.onRemoval();
    }

    protected void flushInventory() {
        MEStorage monitor = getNetworkStorage();
        if (monitor != null) {
            for (ExportOnlyAESlot slot : aeItemHandler.getInventory()) {
                GenericStack stock = slot.getStock();
                if (stock != null) monitor.insert(stock.what(), stock.amount(), Actionable.MODULATE, getActionSource());
            }
            for (ExportOnlyAESlot slot : aeFluidHandler.getInventory()) {
                GenericStack stock = slot.getStock();
                if (stock != null) monitor.insert(stock.what(), stock.amount(), Actionable.MODULATE, getActionSource());
            }
        }
    }

    @Override
    public boolean usesMui2() {
        return true;
    }

    protected @NotNull AESyncHandler createAEItemSyncHandler() {
        return new AEItemSyncHandler(getItemAEHandler(), this::markDirty, circuitInventory::setCircuitValue);
    }

    @NotNull
    protected ExportOnlyAEItemList getItemAEHandler() {
        return (ExportOnlyAEItemList) aeItemHandler;
    }

    protected @NotNull AESyncHandler createAEFluidSyncHandler() {
        return new AEFluidSyncHandler(getFluidAEHandler(), this::markDirty, circuitInventory::setCircuitValue);
    }

    @NotNull
    protected ExportOnlyAEFluidList getFluidAEHandler() {
        return (ExportOnlyAEFluidList) aeFluidHandler;
    }

    @Override
    public ModularPanel buildUI(PosGuiData guiData, PanelSyncManager panelSyncManager, UISettings settings) {
        ModularPanel mainPanel = GTGuis.createPanel(this, 176, 18 + 18 * 4 + 94);
        final boolean isStocking = isStocking();

        // 注册双通道同步处理器
        panelSyncManager.syncValue(SYNC_HANDLER_NAME + "item", 0, createAEItemSyncHandler());
        panelSyncManager.syncValue(SYNC_HANDLER_NAME + "fluid", 0, createAEFluidSyncHandler());

        return mainPanel.child(IKey.lang(getMetaFullName()).asWidget().pos(5, 5))
                .child(SlotGroupWidget.playerInventory(false).left(7).bottom(7))
                .child(IKey.lang(() -> isOnline() ? "gregtech.gui.me_network.online" :
                                "gregtech.gui.me_network.offline")
                        .asWidget().marginLeft(5).widthRel(1.0f).top(15))
                .child(createConfigGrid(guiData, panelSyncManager))
                .child(Flow.column()
                        .pos(7 + 18 * 4, 25)
                        .size(18, 18 * 4)
                        .child(createMainColumnWidget(0, guiData, panelSyncManager))
                        .child(createMainColumnWidget(1, guiData, panelSyncManager))
                        .child(createMainColumnWidget(2, guiData, panelSyncManager))
                        .child(createMainColumnWidget(3, guiData, panelSyncManager)))
                .child(createDisplayGrid(guiData, panelSyncManager))
                .child(Flow.row()
                        .width(isStocking ? 18 : 18 * 2)
                        .height(18)
                        .top(5)
                        .right(7)
                        .childIf(!isStocking, () -> getMultiplierWidget(guiData, panelSyncManager))
                        .child(getSettingWidget(guiData, panelSyncManager)));
    }

    protected @NotNull Widget<?> createConfigGrid(@NotNull PosGuiData guiData,
                                                  @NotNull PanelSyncManager panelSyncManager) {
        // 物品配置网格（上半部分）
        Grid itemGrid = new Grid()
                .pos(7, 25)
                .size(18 * 4)
                .minElementMargin(0, 0)
                .minColWidth(18)
                .minRowHeight(18)
                .gridOfSizeWidth(CONFIG_SIZE, (int) Math.sqrt(CONFIG_SIZE),
                        (x, y, index) -> new AEItemConfigSlot(isStocking(), index, this::isAutoPull)
                                .syncHandler(SYNC_HANDLER_NAME + "item", 0)
                                .name("Item Index " + index));

        for (IWidget slotUpper : itemGrid.getChildren()) {
            ((AEItemConfigSlot) slotUpper).onSelect(() -> {
                for (IWidget slotLower : itemGrid.getChildren()) {
                    ((AEItemConfigSlot) slotLower).deselect();
                }
            });
        }

        // 流体配置网格（下半部分）
        Grid fluidGrid = new Grid()
                .pos(7, 25 + 18 * 4 + 4)
                .size(18 * 4)
                .minElementMargin(0, 0)
                .minColWidth(18)
                .minRowHeight(18)
                .gridOfSizeWidth(CONFIG_SIZE, (int) Math.sqrt(CONFIG_SIZE),
                        (x, y, index) -> new AEFluidConfigSlot(isStocking(), index, this::isAutoPull)
                                .syncHandler(SYNC_HANDLER_NAME + "fluid", 0)
                                .name("Fluid Index " + index));

        for (IWidget slotLower : fluidGrid.getChildren()) {
            ((AEFluidConfigSlot) slotLower).onSelect(() -> {
                for (IWidget slot : fluidGrid.getChildren()) {
                    ((AEFluidConfigSlot) slot).deselect();
                }
            });
        }

        return Flow.column()
                .pos(7 + 18 * 5, 25)
                .size(18 * 4, 18 * 4 + 4 + 18 * 4)
                .child(itemGrid)
                .child(fluidGrid);
    }

    protected @NotNull Widget<?> createDisplayGrid(@NotNull PosGuiData guiData,
                                                   @NotNull PanelSyncManager panelSyncManager) {
        // 物品显示网格（上半部分）
        Grid itemGrid = new Grid()
                .pos(7 + 18 * 5, 25)
                .size(18 * 4)
                .minElementMargin(0, 0)
                .minColWidth(18)
                .minRowHeight(18)
                .gridOfSizeWidth(CONFIG_SIZE, (int) Math.sqrt(CONFIG_SIZE),
                        (x, y, index) -> new AEItemDisplaySlot(index)
                                .background(GTGuiTextures.SLOT_DARK)
                                .syncHandler(SYNC_HANDLER_NAME + "item", 0)
                                .name("Item Index " + index));

        // 流体显示网格（下半部分）
        Grid fluidGrid = new Grid()
                .pos(7 + 18 * 5, 25 + 18 * 4 + 4)
                .size(18 * 4)
                .minElementMargin(0, 0)
                .minColWidth(18)
                .minRowHeight(18)
                .gridOfSizeWidth(CONFIG_SIZE, (int) Math.sqrt(CONFIG_SIZE),
                        (x, y, index) -> new AEFluidDisplaySlot(index)
                                .background(GTGuiTextures.SLOT_DARK)
                                .syncHandler(SYNC_HANDLER_NAME + "fluid", 0)
                                .name("Fluid Index " + index));

        return Flow.column()
                .pos(7 + 18 * 5, 25)
                .size(18 * 4, 18 * 4 + 4 + 18 * 4)
                .child(itemGrid)
                .child(fluidGrid);
    }

    protected @NotNull Widget<?> createMainColumnWidget(@Range(from = 0, to = 3) int index, @NotNull PosGuiData guiData,
                                                        @NotNull PanelSyncManager panelSyncManager) {
        return switch (index) {
            case 1 -> GTGuiTextures.ARROW_DOUBLE.asWidget();
            case 2 -> createGhostCircuitWidget();
            default -> new Widget<>()
                    .size(18);
        };
    }

    protected @NotNull gregtech.api.mui.widget.GhostCircuitSlotWidget createGhostCircuitWidget() {
        return new GhostCircuitSlotWidget()
                .slot(circuitInventory, 0)
                .background(GTGuiTextures.SLOT, GTGuiTextures.INT_CIRCUIT_OVERLAY);
    }

    protected Widget<?> getSettingWidget(@NotNull PosGuiData guiData, @NotNull PanelSyncManager guiSyncManager) {
        IPanelHandler settingPopup = guiSyncManager.syncedPanel("settings_panel", true, this::buildSettingsPopup);

        return new ButtonWidget<>()
                .onMousePressed(mouse -> {
                    settingPopup.togglePanel();
                    return true;
                })
                .addTooltipLine(IKey.lang("gregtech.machine.me.settings.button"))
                .overlay(GTGuiTextures.FILTER_SETTINGS_OVERLAY);
    }

    protected ModularPanel buildSettingsPopup(PanelSyncManager syncManager, IPanelHandler syncHandler) {
        IntSyncValue refreshRateSync = new IntSyncValue(this::getRefreshRate, this::setRefreshRate);
        final int width = 110;

        return GTGuis.createPopupPanel("settings", width, getSettingsPopupHeight())
                .child(Flow.row()
                        .pos(4, 4)
                        .height(16)
                        .child(CONTROLLER_ICON.asWidget().size(16).marginRight(4))
                        .child(IKey.lang("gregtech.machine.me.settings.button").asWidget().heightRel(1.0f)))
                .child(IKey.lang("gregtech.machine.me.settings.refresh_rate").asWidget().left(5).top(5 + 18))
                .child(new TextFieldWidget()
                        .left(5)
                        .top(15 + 18)
                        .size(width - 10, 10)
                        .setNumbers(1, Integer.MAX_VALUE)
                        .setDefaultNumber(ConfigHolder.compat.ae2.updateIntervals)
                        .value(refreshRateSync));
    }

    protected int getSettingsPopupHeight() {
        return 33 + 14 + 5;
    }

    protected Widget<?> getMultiplierWidget(@NotNull PosGuiData guiData, @NotNull PanelSyncManager syncManager) {
        IPanelHandler multiplierPopup = syncManager.syncedPanel("multiplier_panel", true, this::buildMultiplierPopup);

        return new ButtonWidget<>()
                .onMousePressed(mouse -> {
                    multiplierPopup.togglePanel();
                    return true;
                })
                .addTooltipLine(IKey.lang("gregtech.machine.me.multiplier.button"))
                .overlay(GTGuiTextures.ARROW_OPPOSITE);
    }

    protected ModularPanel buildMultiplierPopup(PanelSyncManager syncManager, IPanelHandler syncHandler) {
        AESyncHandler itemSync = syncManager
                .findSyncHandler(SYNC_HANDLER_NAME + "item", 0, AESyncHandler.class);
        AESyncHandler fluidSync = syncManager
                .findSyncHandler(SYNC_HANDLER_NAME + "fluid", 0, AESyncHandler.class);
        IntValue multiplier = new IntValue(2);

        return GTGuis.createPopupPanel("multiplier", 100, 35)
                .child(new ButtonWidget<>()
                        .onMousePressed(mouse -> {
                            if (itemSync != null) {
                                itemSync.modifyConfigAmounts(
                                        (index, amount) -> Math.max(1, amount / multiplier.getIntValue()));
                            }
                            if (fluidSync != null) {
                                fluidSync.modifyConfigAmounts(
                                        (index, amount) -> Math.max(1, amount / multiplier.getIntValue()));
                            }
                            return true;
                        })
                        .left(5)
                        .top(7)
                        .overlay(IKey.str("/")))
                .child(new TextFieldWidget()
                        .horizontalCenter()
                        .top(5)
                        .widthRel(0.5f)
                        .height(20)
                        .setNumbers(2, Integer.MAX_VALUE)
                        .setDefaultNumber(2)
                        .value(multiplier))
                .child(new ButtonWidget<>()
                        .onMousePressed(mouse -> {
                            if (itemSync != null) {
                                itemSync.modifyConfigAmounts((index, amount) -> GTUtility.multiplySaturated(amount,
                                        multiplier.getIntValue()));
                            }
                            if (fluidSync != null) {
                                fluidSync.modifyConfigAmounts((index, amount) -> GTUtility.multiplySaturated(amount,
                                        multiplier.getIntValue()));
                            }
                            return true;
                        })
                        .right(5)
                        .top(7)
                        .overlay(IKey.str("x")));
    }

    @Override
    public boolean isWorkingEnabled() {
        return workingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean workingEnabled) {
        this.workingEnabled = workingEnabled;
        World world = getWorld();
        if (world != null && !world.isRemote) {
            writeCustomData(GregtechDataCodes.WORKING_ENABLED, buf -> buf.writeBoolean(workingEnabled));
        }
    }

    @Override
    public boolean hasGhostCircuitInventory() {
        return true;
    }

    @Override
    public int getGhostCircuitConfig() {
        return circuitInventory.getCircuitValue();
    }

    @Override
    public void setGhostCircuitConfig(int config) {
        if (this.circuitInventory.getCircuitValue() == config) return;
        this.circuitInventory.setCircuitValue(config);
        if (!getWorld().isRemote) markDirty();
    }

    @Override
    public void setGhostCustomStack(@NotNull ItemStack stack) {
        if (this.circuitInventory == null) return;
        this.circuitInventory.setCustomStack(stack);
        if (!getWorld().isRemote) markDirty();
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
        data.setBoolean(WORKING_TAG, workingEnabled);
        this.circuitInventory.write(data);

        NBTTagList slots = new NBTTagList();
        for (int i = 0; i < CONFIG_SIZE; i++) {
            ExportOnlyAEItemSlot slot = this.getItemAEHandler().getInventory()[i];
            NBTTagCompound slotTag = new NBTTagCompound();
            slotTag.setInteger("slot", i);
            slotTag.setTag("stack", slot.serializeNBT());
            slots.appendTag(slotTag);
        }
        data.setTag(ITEM_BUFFER_TAG, slots);

        NBTTagList tanks = new NBTTagList();
        for (int i = 0; i < CONFIG_SIZE; i++) {
            ExportOnlyAEFluidSlot tank = this.getFluidAEHandler().getInventory()[i];
            NBTTagCompound tankTag = new NBTTagCompound();
            tankTag.setInteger("slot", i);
            tankTag.setTag("tank", tank.serializeNBT());
            tanks.appendTag(tankTag);
        }
        data.setTag(FLUID_BUFFER_TAG, tanks);

        GTUtility.writeItems(this.extraSlotInventory, "ExtraInventory", data);

        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        if (data.hasKey(WORKING_TAG, Constants.NBT.TAG_BYTE)) {
            this.workingEnabled = data.getBoolean(WORKING_TAG);
        }
        this.circuitInventory.read(data);

        if (data.hasKey(ITEM_BUFFER_TAG, 9)) {
            NBTTagList slots = (NBTTagList) data.getTag(ITEM_BUFFER_TAG);
            for (NBTBase nbtBase : slots) {
                NBTTagCompound slotTag = (NBTTagCompound) nbtBase;
                ExportOnlyAEItemSlot slot = this.getItemAEHandler().getInventory()[slotTag.getInteger("slot")];
                slot.deserializeNBT(slotTag.getCompoundTag("stack"));
            }
        }

        GTUtility.readItems(this.extraSlotInventory, "ExtraInventory", data);
        this.importItems = createImportItemHandler();

        if (data.hasKey(FLUID_BUFFER_TAG, 9)) {
            NBTTagList tanks = (NBTTagList) data.getTag(FLUID_BUFFER_TAG);
            for (NBTBase nbtBase : tanks) {
                NBTTagCompound tankTag = (NBTTagCompound) nbtBase;
                ExportOnlyAEFluidSlot tank = this.getFluidAEHandler().getInventory()[tankTag.getInteger("slot")];
                tank.deserializeNBT(tankTag.getCompoundTag("tank"));
            }
        }
    }

    @Override
    public final void onDataStickLeftClick(EntityPlayer player, ItemStack dataStick) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("MEInputBus", writeConfigToTag());
        dataStick.setTagCompound(tag);
        dataStick.setTranslatableName("gregtech.machine.me.item_import.data_stick.name");
        player.sendStatusMessage(new TextComponentTranslation("gregtech.machine.me.import_copy_settings"), true);
    }

    protected NBTTagCompound writeConfigToTag() {
        NBTTagCompound tag = new NBTTagCompound();

        // 保存物品配置
        NBTTagCompound itemConfigStacks = new NBTTagCompound();
        ExportOnlyAESlot[] itemInventory = aeItemHandler.getInventory();
        for (int index = 0; index < CONFIG_SIZE; index++) {
            ExportOnlyAESlot slot = itemInventory[index];
            GenericStack config = slot.getConfig();
            if (config == null) continue;
            itemConfigStacks.setTag("I" + index, GenericStack.writeTag(config));
        }
        tag.setTag("ItemConfigStacks", itemConfigStacks);

        // 保存流体配置
        NBTTagCompound fluidConfigStacks = new NBTTagCompound();
        ExportOnlyAESlot[] fluidInventory = aeFluidHandler.getInventory();
        for (int index = 0; index < CONFIG_SIZE; index++) {
            ExportOnlyAESlot slot = fluidInventory[index];
            GenericStack config = slot.getConfig();
            if (config == null) continue;
            fluidConfigStacks.setTag("F" + index, GenericStack.writeTag(config));
        }
        tag.setTag("FluidConfigStacks", fluidConfigStacks);

        tag.setByte("GhostCircuit", (byte) this.circuitInventory.getCircuitValue());
        tag.setInteger(REFRESH_RATE_TAG, getRefreshRate());
        return tag;
    }

    @Override
    public final boolean onDataStickRightClick(EntityPlayer player, ItemStack dataStick) {
        NBTTagCompound tag = dataStick.getTagCompound();
        if (tag == null || !tag.hasKey("MEInputBus")) return false;
        readConfigFromTag(tag.getCompoundTag("MEInputBus"));
        syncME();
        player.sendStatusMessage(new TextComponentTranslation("gregtech.machine.me.import_paste_settings"), true);
        return true;
    }

    protected void readConfigFromTag(NBTTagCompound tag) {
        // 读取物品配置
        if (tag.hasKey("ItemConfigStacks")) {
            ExportOnlyAESlot[] itemInventory = aeItemHandler.getInventory();
            NBTTagCompound itemConfigStacks = tag.getCompoundTag("ItemConfigStacks");
            for (int index = 0; index < CONFIG_SIZE; index++) {
                GenericStack stack = null;
                String key = "I" + index;
                if (itemConfigStacks.hasKey(key)) {
                    NBTTagCompound configTag = itemConfigStacks.getCompoundTag(key);
                    stack = GenericStack.readTag(configTag);
                }
                itemInventory[index].setConfig(stack);
            }
        }

        // 读取流体配置
        if (tag.hasKey("FluidConfigStacks")) {
            ExportOnlyAESlot[] fluidInventory = aeFluidHandler.getInventory();
            NBTTagCompound fluidConfigStacks = tag.getCompoundTag("FluidConfigStacks");
            for (int index = 0; index < CONFIG_SIZE; index++) {
                GenericStack stack = null;
                String key = "F" + index;
                if (fluidConfigStacks.hasKey(key)) {
                    NBTTagCompound configTag = fluidConfigStacks.getCompoundTag(key);
                    stack = GenericStack.readTag(configTag);
                }
                fluidInventory[index].setConfig(stack);
            }
        }

        if (tag.hasKey("GhostCircuit")) {
            this.setGhostCircuitConfig(tag.getByte("GhostCircuit"));
        }
        if (tag.hasKey(REFRESH_RATE_TAG)) {
            setRefreshRate(tag.getInteger(REFRESH_RATE_TAG));
        }
    }

    /** Portable configuration boundary used by Matter Manipulator copy/move operations. */
    public final NBTTagCompound applygray$exportPortableConfiguration() {
        NBTTagCompound tag = writeConfigToTag();
        tag.setBoolean(WORKING_TAG, workingEnabled);
        return tag;
    }

    /** Restores ME item/fluid configuration without restoring live network buffers. */
    public final void applygray$importPortableConfiguration(NBTTagCompound tag) {
        readConfigFromTag(tag);
        if (tag.hasKey(WORKING_TAG, Constants.NBT.TAG_BYTE)) workingEnabled = tag.getBoolean(WORKING_TAG);
    }

    @Override
    public @NotNull List<MultiblockAbility<?>> getAbilities() {
        return Arrays.asList(MultiblockAbility.IMPORT_ITEMS, MultiblockAbility.IMPORT_FLUIDS);
    }

    @Override
    public void registerAbilities(@NotNull AbilityInstances abilityInstances) {
        if (abilityInstances.isKey(MultiblockAbility.IMPORT_ITEMS))
            abilityInstances.add(this.importItems);
        if (abilityInstances.isKey(MultiblockAbility.IMPORT_FLUIDS))
            abilityInstances.add(Arrays.asList(getFluidAEHandler().getInventory()));
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, @NotNull List<String> tooltip,
                               boolean advanced) {
        super.addInformation(stack, player, tooltip, advanced);
        tooltip.add(I18n.format("gregtech.machine.dual_hatch.import.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.dual_import.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me_import_dual_hatch.configs.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.copy_paste.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.extra_connections.tooltip"));
        tooltip.add(I18n.format("gregtech.universal.enabled"));
    }
}
