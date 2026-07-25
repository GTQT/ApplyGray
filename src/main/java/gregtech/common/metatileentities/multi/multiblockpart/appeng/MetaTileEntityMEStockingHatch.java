package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.api.mui.ApplyGrayGuiTextures;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.mui.GTGuiTextures;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEFluidList;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.slot.ExportOnlyAEFluidSlot;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import ae2.api.config.Actionable;
import ae2.api.storage.MEStorage;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

import java.util.List;
import java.util.function.Predicate;

import static gregtech.api.capability.GregtechDataCodes.UPDATE_AUTO_PULL;

public class MetaTileEntityMEStockingHatch extends MetaTileEntityMEInputHatch {

    private static final String MINIMUM_STOCK_TAG = "MinimumStackSize";

    private static final int CONFIG_SIZE = 16;
    private boolean autoPull;
    private Predicate<FluidStack> autoPullTest;
    private int minimumStackSize = 0;

    public MetaTileEntityMEStockingHatch(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, tier);
        this.autoPullTest = $ -> false;
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity iGregTechTileEntity) {
        return new MetaTileEntityMEStockingHatch(metaTileEntityId, getTier());
    }

    @Override
    protected @NotNull ExportOnlyAEStockingFluidList initializeAEHandler() {
        return new ExportOnlyAEStockingFluidList(this, CONFIG_SIZE, getController());
    }

    @Override
    @NotNull
    protected ExportOnlyAEStockingFluidList getAEHandler() {
        return (ExportOnlyAEStockingFluidList) this.aeHandler;
    }

    @Override
    public void update() {
        super.update();
        if (!getWorld().isRemote) {
            // Immediately clear cached fluids if the status changed, to prevent running recipes while offline
            if (this.meStatusChanged && !isOnline()) {
                if (autoPull) {
                    this.getAEHandler().clearConfig();
                } else {
                    for (int i = 0; i < CONFIG_SIZE; i++) {
                        getAEHandler().getInventory()[i].setStack(null);
                    }
                }
            }
        }
    }

    @Override
    protected void operateOnME() {
        if (autoPull) {
            refreshList();
        }

        syncME();
    }

    @Override
    protected void syncME() {
        MEStorage monitor = super.getMonitor();
        if (monitor == null) return;

        for (ExportOnlyAEStockingFluidSlot slot : this.getAEHandler().getInventory()) {
            GenericStack config = slot.getConfig();
            if (config == null || !(config.what() instanceof AEFluidKey)) {
                slot.setStack(null);
            } else {
                // Try to fill the slot
                long available = monitor.extract(config.what(), Long.MAX_VALUE, Actionable.SIMULATE,
                        getActionSource());
                slot.setStack(available > 0 ? new GenericStack(config.what(), available) : null);
            }
        }
    }

    @Override
    protected void flushInventory() {
        // no-op, nothing to send back to the network
    }

    @Override
    public void addToMultiBlock(MultiblockControllerBase controllerBase) {
        super.addToMultiBlock(controllerBase);
        this.autoPullTest = stack -> !this.testConfiguredInOtherHatch(stack);
        validateConfig();
    }

    @Override
    public void removeFromMultiBlock(MultiblockControllerBase controllerBase) {
        this.autoPullTest = $ -> false;
        if (this.autoPull) {
            this.getAEHandler().clearConfig();
        }
        super.removeFromMultiBlock(controllerBase);
    }

    private void validateConfig() {
        for (var slot : this.getAEHandler().getInventory()) {
            if (slot.getConfig() != null) {
                GenericStack config = slot.getConfig();
                if (config.what() instanceof AEFluidKey fluidKey &&
                        testConfiguredInOtherHatch(fluidKey.toStack(1))) {
                    slot.setConfig(null);
                    slot.setStock(null);
                }
            }
        }
    }

    private boolean testConfiguredInOtherHatch(FluidStack stack) {
        if (stack == null) return false;
        MultiblockControllerBase controller = getController();
        if (controller == null) return false;

        var abilityList = controller.getAbilities(MultiblockAbility.IMPORT_FLUIDS);
        for (var ability : abilityList) {
            if (ability instanceof ExportOnlyAEStockingFluidSlot aeSlot) {
                if (aeSlot.getConfig() == null) continue;
                if (getAEHandler().ownsSlot(aeSlot)) continue;
                if (aeSlot.getConfig().what() instanceof AEFluidKey fluidKey &&
                        fluidKey.getFluid().equals(stack.getFluid())) {
                    return true;
                }
            }
        }

        return false;
    }

    private void setAutoPull(boolean autoPull) {
        this.autoPull = autoPull;
        markDirty();
        if (!getWorld().isRemote) {
            if (!this.autoPull) {
                this.getAEHandler().clearConfig();
            } else if (isOnline) {
                refreshList();
                syncME();
            }
            writeCustomData(UPDATE_AUTO_PULL, buf -> buf.writeBoolean(this.autoPull));
        }
    }

    private void refreshList() {
        MEStorage monitor = getMonitor();
        if (monitor == null) {
            clearInventory(0);
            return;
        }

        var grid = getMainNode().getGrid();
        if (grid == null) {
            clearInventory(0);
            return;
        }

        int index = 0;
        ExportOnlyAEStockingFluidSlot[] inventory = getAEHandler().getInventory();
        for (var entry : grid.getStorageService().getCachedInventory()) {
            if (index >= CONFIG_SIZE) break;
            if (!(entry.getKey() instanceof AEFluidKey fluidKey)) continue;
            long storedAmount = entry.getLongValue();
            if (storedAmount < minimumStackSize) continue;

            long available = monitor.extract(fluidKey, storedAmount, Actionable.SIMULATE, getActionSource());
            if (available < minimumStackSize) continue;

            FluidStack fluidStack = fluidKey.toStack(1);
            if (fluidStack == null) continue;
            if (autoPullTest != null && !autoPullTest.test(fluidStack)) continue;
            var slot = inventory[index];
            slot.setConfig(new GenericStack(fluidKey, 1));
            slot.setStack(new GenericStack(fluidKey, available));
            index++;
        }

        clearInventory(index);
    }

    private void clearInventory(int startIndex) {
        for (int i = startIndex; i < CONFIG_SIZE; i++) {
            var slot = this.getAEHandler().getInventory()[i];
            slot.setConfig(null);
            slot.setStack(null);
        }
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == UPDATE_AUTO_PULL) {
            this.autoPull = buf.readBoolean();
        }
    }

    @Override
    protected ModularPanel buildSettingsPopup(PanelSyncManager syncManager, IPanelHandler syncHandler) {
        IntSyncValue minimumStockSync = new IntSyncValue(this::getMinimumStackSize, this::setMinimumStackSize);

        return super.buildSettingsPopup(syncManager, syncHandler)
                .child(IKey.lang("gregtech.machine.me.settings.minimum")
                        .asWidget()
                        .left(5)
                        .top(5 + 18 + 18 + 8)
                        .tooltip(tooltip -> tooltip.addLine(IKey.str("拉取阈值，只有大于该值才会被拉取")))
                )
                .child(new TextFieldWidget()
                        .left(5)
                        .top(15 + 18 + 18 + 8)
                        .size(100, 10)
                        .setNumbers(0, Integer.MAX_VALUE)
                        .setDefaultNumber(0)
                        .value(minimumStockSync));
    }

    @Override
    protected int getSettingsPopupHeight() {
        return super.getSettingsPopupHeight() + 20 + 7;
    }

    @Override
    protected @NotNull Widget<?> createMainColumnWidget(@Range(from = 0, to = 3) int index, @NotNull PosGuiData guiData,
                                                        @NotNull PanelSyncManager panelSyncManager) {
        if (index == 0) {
            return new ToggleButton()
                    .size(16)
                    .margin(1)
                    .value(new BooleanSyncValue(this::isAutoPull, this::setAutoPull))
                    .disableHoverBackground()
                    .overlay(false, ApplyGrayGuiTextures.AUTO_PULL[0])
                    .overlay(true, ApplyGrayGuiTextures.AUTO_PULL[1])
                    .addTooltip(false, IKey.lang("gregtech.machine.me.stocking_auto_pull_disabled"))
                    .addTooltip(true, IKey.lang("gregtech.machine.me.stocking_auto_pull_enabled"));
        }

        return super.createMainColumnWidget(index, guiData, panelSyncManager);
    }

    public void setMinimumStackSize(int minimumStackSize) {
        this.minimumStackSize = minimumStackSize;
        if (!getWorld().isRemote) {
            markDirty();
            refreshList();
            syncME();
        }
    }

    public int getMinimumStackSize() {
        return minimumStackSize;
    }

    @Override
    public boolean onScrewdriverClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing,
                                      CuboidRayTraceResult hitResult) {
        if (!getWorld().isRemote) {
            setAutoPull(!autoPull);
            if (autoPull) {
                playerIn.sendStatusMessage(
                        new TextComponentTranslation("gregtech.machine.me.stocking_auto_pull_enabled"), false);
            } else {
                playerIn.sendStatusMessage(
                        new TextComponentTranslation("gregtech.machine.me.stocking_auto_pull_disabled"), false);
            }
        }
        return true;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("AutoPull", this.autoPull);
        data.setInteger(MINIMUM_STOCK_TAG, this.minimumStackSize);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.autoPull = data.getBoolean("AutoPull");

        if (data.hasKey(MINIMUM_STOCK_TAG)) {
            this.minimumStackSize = data.getInteger(MINIMUM_STOCK_TAG);
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeBoolean(this.autoPull);
        buf.writeVarInt(this.minimumStackSize);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.autoPull = buf.readBoolean();
        this.minimumStackSize = buf.readVarInt();
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, @NotNull List<String> tooltip,
                               boolean advanced) {
        tooltip.add(I18n.format("gregtech.machine.fluid_hatch.import.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.stocking_fluid.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me_import_fluid_hatch.configs.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.copy_paste.tooltip"));
        tooltip.add(I18n.format("gregtech.machine.me.stocking_fluid.tooltip.2"));
        tooltip.add(I18n.format("gregtech.machine.me.extra_connections.tooltip"));
        tooltip.add(I18n.format("gregtech.universal.enabled"));
    }

    @Override
    protected NBTTagCompound writeConfigToTag() {
        if (!autoPull) {
            NBTTagCompound tag = super.writeConfigToTag();
            tag.setBoolean("AutoPull", false);
            return tag;
        }
        // if in auto-pull, no need to write actual configured slots
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("AutoPull", true);

        tag.setInteger(MINIMUM_STOCK_TAG, this.minimumStackSize);

        return tag;
    }

    @Override
    protected void readConfigFromTag(NBTTagCompound tag) {
        if (tag.getBoolean("AutoPull")) {
            // if being set to auto-pull, no need to read the configured slots
            this.setAutoPull(true);
            return;
        }
        // set auto pull first to avoid issues with clearing the config after reading from the data stick
        this.setAutoPull(false);

        if (tag.hasKey(MINIMUM_STOCK_TAG)) {
            this.minimumStackSize = tag.getInteger(MINIMUM_STOCK_TAG);
        }

        super.readConfigFromTag(tag);
    }

    public static class ExportOnlyAEStockingFluidSlot extends ExportOnlyAEFluidSlot {

        public ExportOnlyAEStockingFluidSlot(MetaTileEntityMEStockingHatch holder, GenericStack config,
                                             GenericStack stock, MetaTileEntity entityToNotify) {
            super(holder, config, stock, entityToNotify);
        }

        public ExportOnlyAEStockingFluidSlot(MetaTileEntityMEStockingHatch holder, MetaTileEntity entityToNotify) {
            super(holder, entityToNotify);
        }

        @Override
        protected MetaTileEntityMEStockingHatch getHolder() {
            return (MetaTileEntityMEStockingHatch) super.getHolder();
        }

        @Override
        public @NotNull ExportOnlyAEStockingFluidSlot copy() {
            return new ExportOnlyAEStockingFluidSlot(
                    this.getHolder(),
                    this.config == null ? null : new GenericStack(this.config.what(), this.config.amount()),
                    this.stock == null ? null : new GenericStack(this.stock.what(), this.stock.amount()),
                    null);
        }

        @Override
        public @Nullable FluidStack drain(int maxDrain, boolean doDrain) {
            if (this.stock == null || this.stock.amount() <= 0) {
                return null;
            }

            if (this.config != null && this.config.what() instanceof AEFluidKey fluidKey) {
                MEStorage monitor = getHolder().getMonitor();
                if (monitor == null) return null;

                Actionable action = doDrain ? Actionable.MODULATE : Actionable.SIMULATE;
                long extracted = monitor.extract(fluidKey, maxDrain, action, getHolder().getActionSource());
                if (extracted > 0) {
                    int extractedAmount = (int) Math.min(extracted, Integer.MAX_VALUE);
                    if (doDrain) {
                        decrementStock(extractedAmount);
                    }
                    return fluidKey.toStack(extractedAmount);
                }
            }
            return null;
        }
    }

    public static class ExportOnlyAEStockingFluidList extends ExportOnlyAEFluidList {

        private final MetaTileEntityMEStockingHatch holder;

        public ExportOnlyAEStockingFluidList(MetaTileEntityMEStockingHatch holder, int slots,
                                             MetaTileEntity entityToNotify) {
            super(holder, slots, entityToNotify);
            this.holder = holder;
        }

        @Override
        protected void createInventory(MetaTileEntity holder, MetaTileEntity entityToNotify) {
            if (!(holder instanceof MetaTileEntityMEStockingHatch stocking)) {
                throw new IllegalArgumentException("Cannot create Stocking Fluid List for nonstocking MetaTileEntity!");
            }
            this.inventory = new ExportOnlyAEStockingFluidSlot[size];
            for (int i = 0; i < size; i++) {
                this.inventory[i] = new ExportOnlyAEStockingFluidSlot(stocking, entityToNotify);
            }
        }

        @Override
        public @NotNull ExportOnlyAEStockingFluidSlot @NotNull [] getInventory() {
            return (ExportOnlyAEStockingFluidSlot[]) super.getInventory();
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return holder.autoPull;
        }

        @Override
        public boolean hasStackInConfig(FluidStack stack, boolean checkExternal) {
            boolean inThisHatch = super.hasStackInConfig(stack, false);
            if (inThisHatch) return true;
            if (checkExternal) {
                return holder.testConfiguredInOtherHatch(stack);
            }
            return false;
        }
    }
}
