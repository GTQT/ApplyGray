package applygray.common.metatileentities.multiblock.multiblockpart;

import applygray.api.IAEManagedMetaTileEntity;
import applygray.integration.ae2.ApplyGrayGridNodeSupport;

import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.metatileentity.IAEStatusProvider;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityEnergyHatch;

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
import net.minecraftforge.common.capabilities.Capability;

import ae2.api.config.Actionable;
import ae2.api.config.PowerMultiplier;
import ae2.api.config.PowerUnit;
import ae2.api.networking.IGrid;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.energy.IEnergyService;
import ae2.api.util.AECableType;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import static gregtech.api.GTValues.V;

/**
 * Grid energy hatch: exchanges power between the multiblock's EU container and the attached ME network
 * directly, without an energy acceptor in between.
 *
 * <p>Both directions are driven from {@link #update()}: an input hatch pulls AE power out of the network,
 * an output hatch pushes AE power into it. Everything happens through {@link IEnergyService} as an ordinary
 * network member, so the hatch never registers a power storage of its own — the network therefore keeps
 * owning its totals and no {@code GridPowerStorageChanged} bookkeeping is needed here.</p>
 *
 * <p>Power must also flow while the network is unpowered: that is exactly the case an output hatch exists
 * for. The transfer is therefore gated on the node's grid, never on {@code isActive()} (which requires the
 * network to be powered), and the node idles at 0 AE/t so the hatch cannot drain the network it feeds.</p>
 */
public class MetaTileEntityMEGridEnergyHatch extends MetaTileEntityEnergyHatch
        implements IAEManagedMetaTileEntity, IAEStatusProvider, IControllable {

    private static final String EXTRA_CONNECTIONS_TAG = "AllowExtraConnections";
    private static final String WORKING_ENABLED_TAG = "WorkingEnabled";

    @Nullable
    private IManagedGridNode mainNode;
    private boolean allowsExtraConnections;
    private boolean workingEnabled = true;

    public MetaTileEntityMEGridEnergyHatch(ResourceLocation metaTileEntityId, int tier, int amperage,
                                           boolean isExportHatch) {
        super(metaTileEntityId, tier, amperage, isExportHatch);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityMEGridEnergyHatch(metaTileEntityId, getTier(), amperage, isExportHatch);
    }

    // ## ME node

    @Override
    public @NotNull IManagedGridNode getMainNode() {
        if (mainNode == null) {
            mainNode = ApplyGrayGridNodeSupport.createMainNode(this)
                    .setTagName("applygray_grid_energy_hatch")
                    // No REQUIRE_CHANNEL: this hatch is a power conduit, not a channel device. Requiring a channel
                    // would make it unable to charge a network that has no controller or no power yet.
                    .setIdlePowerUsage(0.0)
                    .setExposedOnSides(getConnectableSides())
                    .setVisualRepresentation(getStackForm());
        }
        return mainNode;
    }

    @Override
    public void destroyMainNode() {
        if (mainNode != null) {
            mainNode.destroy();
            mainNode = null;
        }
    }

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull EnumFacing side) {
        return allowsExtraConnections || side == getFrontFacing() ? AECableType.SMART : AECableType.NONE;
    }

    public EnumSet<EnumFacing> getConnectableSides() {
        return allowsExtraConnections ? EnumSet.allOf(EnumFacing.class) : EnumSet.of(getFrontFacing());
    }

    private void updateConnectableSides() {
        if (mainNode != null) {
            mainNode.setExposedOnSides(getConnectableSides());
        }
    }

    @Override
    public void setFrontFacing(EnumFacing frontFacing) {
        super.setFrontFacing(frontFacing);
        updateConnectableSides();
    }

    // ## Transfer

    @Override
    public void update() {
        super.update();
        World world = getWorld();
        if (world == null || world.isRemote || !workingEnabled) {
            return;
        }

        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        IEnergyService energyService = grid.getEnergyService();
        if (energyService == null) {
            return;
        }

        if (isExportHatch) {
            pushIntoGrid(energyService);
        } else {
            pullFromGrid(energyService);
        }
    }

    /**
     * AE energy per EU, taken from AE2's own EU ratio so a pack that changes AE2's conversion keeps the hatch
     * consistent with every other EU-to-AE adapter instead of pinning a second ratio here.
     */
    private static double aePerEu() {
        return PowerUnit.EU.convertTo(PowerUnit.AE, 1.0);
    }

    /**
     * Moves AE power from the network into the EU container, at most one hatch throughput per tick.
     *
     * <p>The network is asked for a whole number of EU worth of power: a simulated extraction sizes the
     * request, and the real extraction asks for an exact multiple of the ratio again. That keeps the double
     * AE amount and the long EU amount from drifting apart, without keeping a fractional remainder between
     * ticks.</p>
     */
    private void pullFromGrid(IEnergyService energyService) {
        long freeEu = energyContainer.getEnergyCanBeInserted();
        if (freeEu <= 0) {
            return;
        }
        long budgetEu = Math.min(freeEu, throughputEu());
        if (budgetEu <= 0) {
            return;
        }

        double ratio = aePerEu();
        double availableAe = energyService.extractAEPower(budgetEu * ratio, Actionable.SIMULATE, PowerMultiplier.ONE);
        long obtainableEu = (long) Math.floor(availableAe / ratio);
        if (obtainableEu <= 0) {
            return;
        }

        double extractedAe = energyService.extractAEPower(obtainableEu * ratio, Actionable.MODULATE,
                PowerMultiplier.ONE);
        long gainedEu = Math.min(obtainableEu, (long) Math.floor(extractedAe / ratio));
        if (gainedEu > 0) {
            energyContainer.addEnergy(gainedEu);
            markDirty();
        }
    }

    /**
     * Moves EU from the container into the network, at most one hatch throughput per tick.
     *
     * <p>The network is only asked for whole EU worth of AE, and only that amount is billed to the container.
     * An output hatch must not create AE power out of a rounding error, and it must not burn EU the network
     * refused either.</p>
     */
    private void pushIntoGrid(IEnergyService energyService) {
        long storedEu = energyContainer.getEnergyStored();
        if (storedEu <= 0) {
            return;
        }
        long budgetEu = Math.min(storedEu, throughputEu());
        if (budgetEu <= 0) {
            return;
        }

        double ratio = aePerEu();
        double requestedAe = budgetEu * ratio;
        double acceptableAe = requestedAe - energyService.injectPower(requestedAe, Actionable.SIMULATE);
        long acceptedEu = (long) Math.floor(acceptableAe / ratio);
        if (acceptedEu <= 0) {
            return;
        }

        double leftoverAe = energyService.injectPower(acceptedEu * ratio, Actionable.MODULATE);
        long billedEu = Math.min(acceptedEu, (long) Math.floor((acceptedEu * ratio - leftoverAe) / ratio));
        if (billedEu > 0) {
            energyContainer.removeEnergy(billedEu);
            markDirty();
        }
    }

    /** EU/t the hatch may move: one amperage at its tier voltage, exactly like a GT energy hatch. */
    private long throughputEu() {
        return V[getTier()] * amperage;
    }

    // ## Status

    @Override
    public boolean isOnline() {
        return getMainNode().getGrid() != null;
    }

    @Override
    public boolean allowsExtraConnections() {
        return allowsExtraConnections;
    }

    @Override
    public boolean isWorkingEnabled() {
        return workingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean workingEnabled) {
        this.workingEnabled = workingEnabled;
        markDirty();
    }

    // ## Persistence, sync and interaction

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean(EXTRA_CONNECTIONS_TAG, allowsExtraConnections);
        data.setBoolean(WORKING_ENABLED_TAG, workingEnabled);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        allowsExtraConnections = data.getBoolean(EXTRA_CONNECTIONS_TAG);
        if (data.hasKey(WORKING_ENABLED_TAG)) {
            workingEnabled = data.getBoolean(WORKING_ENABLED_TAG);
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeBoolean(allowsExtraConnections);
        buf.writeBoolean(workingEnabled);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        allowsExtraConnections = buf.readBoolean();
        workingEnabled = buf.readBoolean();
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }
        return super.getCapability(capability, side);
    }

    @Override
    public boolean onWireCutterClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing,
                                     CuboidRayTraceResult hitResult) {
        allowsExtraConnections = !allowsExtraConnections;
        updateConnectableSides();
        if (getWorld() != null && !getWorld().isRemote) {
            playerIn.sendStatusMessage(new TextComponentTranslation(allowsExtraConnections
                    ? "gregtech.machine.me.extra_connections.enabled"
                    : "gregtech.machine.me.extra_connections.disabled"), true);
            markDirty();
        }
        return true;
    }

    // ## Tooltips

    @Override
    protected void addDescriptorTooltip(ItemStack stack, @Nullable World world, List<String> tooltip,
                                        boolean advanced) {
        tooltip.add(I18n.format(isExportHatch
                ? "applygray.machine.me_grid_energy_hatch.output.tooltip.1"
                : "applygray.machine.me_grid_energy_hatch.input.tooltip.1"));
        tooltip.add(I18n.format(isExportHatch
                ? "applygray.machine.me_grid_energy_hatch.output.tooltip.2"
                : "applygray.machine.me_grid_energy_hatch.input.tooltip.2"));
        tooltip.add(I18n.format("applygray.machine.me_grid_energy_hatch.tooltip.conversion", formattedAePerEu()));
        tooltip.add(I18n.format("applygray.machine.me_grid_energy_hatch.tooltip.pause"));
        tooltip.add(I18n.format("applygray.machine.me_grid_energy_hatch.tooltip.extra_connections"));
    }

    private static String formattedAePerEu() {
        double ratio = aePerEu();
        if (ratio == Math.floor(ratio)) {
            return Long.toString((long) ratio);
        }
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    @Override
    public void addToolUsages(ItemStack stack, @Nullable World world, List<String> tooltip, boolean advanced) {
        tooltip.add(I18n.format("gregtech.tool_action.wire_cutter.connect"));
        tooltip.add(I18n.format("gregtech.tool_action.soft_mallet.reset"));
        super.addToolUsages(stack, world, tooltip, advanced);
    }
}
