package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.api.IAEManagedMetaTileEntity;
import applygray.integration.ae2.ApplyGrayGridNodeSupport;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.metatileentity.IAEStatusProvider;
import gregtech.api.util.GTLog;
import gregtech.common.ConfigHolder;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockNotifiablePart;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;

import ae2.api.networking.GridFlags;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.security.IActionHost;
import ae2.api.networking.security.IActionSource;
import ae2.api.storage.MEStorage;
import ae2.api.util.AECableType;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

import static gregtech.api.capability.GregtechDataCodes.UPDATE_IO_SPEED;
import static gregtech.api.capability.GregtechDataCodes.UPDATE_ONLINE_STATUS;

public abstract class MetaTileEntityAEHostablePart extends MetaTileEntityMultiblockNotifiablePart implements
                                                    IAEStatusProvider, IControllable, IAEManagedMetaTileEntity {

    public static final String REFRESH_RATE_TAG = "RefreshRate";
    public static final String WORKING_TAG = "WorkingEnabled";

    private boolean workingEnabled = true;
    @Nullable
    private IManagedGridNode mainNode;
    private int refreshRate = ConfigHolder.compat.ae2.updateIntervals;
    protected boolean isOnline;
    protected boolean allowsExtraConnections;
    protected boolean meStatusChanged;
    private boolean wasCraftingProviderActive;
    private boolean craftingProviderRefreshPending = true;

    public MetaTileEntityAEHostablePart(ResourceLocation metaTileEntityId, int tier, boolean isExportHatch) {
        super(metaTileEntityId, tier, isExportHatch);
    }

    @Override
    public @NotNull IManagedGridNode getMainNode() {
        if (mainNode == null) {
            mainNode = ApplyGrayGridNodeSupport.createMainNode(this)
                    .setTagName("applygray_mte_node")
                    .setFlags(GridFlags.REQUIRE_CHANNEL)
                    .setIdlePowerUsage(ConfigHolder.compat.ae2.meHatchEnergyUsage)
                    .setExposedOnSides(getConnectableSides())
                    .setVisualRepresentation(getStackForm());
            if (this instanceof ICraftingProvider provider) {
                mainNode.addService(ICraftingProvider.class, provider);
            }
        }
        return mainNode;
    }

    @Override
    public void update() {
        super.update();
        updateMEStatus();
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeVarInt(refreshRate);
        buf.writeBoolean(isOnline);
        buf.writeBoolean(allowsExtraConnections);
        buf.writeBoolean(workingEnabled);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        refreshRate = buf.readVarInt();
        isOnline = buf.readBoolean();
        allowsExtraConnections = buf.readBoolean();
        workingEnabled = buf.readBoolean();
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == UPDATE_ONLINE_STATUS) {
            boolean online = buf.readBoolean();
            if (isOnline != online) {
                isOnline = online;
                scheduleRenderUpdate();
            }
        } else if (dataId == UPDATE_IO_SPEED) {
            refreshRate = buf.readVarInt();
        }
    }

    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public boolean allowsExtraConnections() {
        return allowsExtraConnections;
    }

    public int getRefreshRate() {
        return refreshRate;
    }

    protected void setRefreshRate(int newRefreshRate) {
        if (newRefreshRate == refreshRate) {
            return;
        }
        refreshRate = Math.max(1, newRefreshRate);
        if (getWorld() != null && !getWorld().isRemote) {
            markDirty();
            writeCustomData(UPDATE_IO_SPEED, buf -> buf.writeVarInt(refreshRate));
        }
    }

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull EnumFacing side) {
        return side != frontFacing && !allowsExtraConnections ? AECableType.NONE : AECableType.SMART;
    }

    public EnumSet<EnumFacing> getConnectableSides() {
        return allowsExtraConnections ? EnumSet.allOf(EnumFacing.class) : EnumSet.of(getFrontFacing());
    }

    public void updateConnectableSides() {
        getMainNode().setExposedOnSides(getConnectableSides());
    }

    @Override
    public boolean onWireCutterClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing,
                                     CuboidRayTraceResult hitResult) {
        allowsExtraConnections = !allowsExtraConnections;
        updateConnectableSides();

        if (!getWorld().isRemote) {
            playerIn.sendStatusMessage(new TextComponentTranslation(allowsExtraConnections
                    ? "gregtech.machine.me.extra_connections.enabled"
                    : "gregtech.machine.me.extra_connections.disabled"), true);
        }
        return true;
    }

    @Override
    public void setFrontFacing(EnumFacing frontFacing) {
        super.setFrontFacing(frontFacing);
        updateConnectableSides();
    }

    protected IActionSource getActionSource() {
        return getHolder() instanceof IActionHost host ? IActionSource.ofMachine(host) : IActionSource.empty();
    }

    @Nullable
    protected MEStorage getNetworkStorage() {
        var grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    public void updateMEStatus() {
        World world = getWorld();
        if (world == null || world.isRemote) {
            return;
        }

        boolean online = getMainNode().isActive();
        if (isOnline != online) {
            writeCustomData(UPDATE_ONLINE_STATUS, buf -> buf.writeBoolean(online));
            isOnline = online;
            meStatusChanged = true;
        } else {
            meStatusChanged = false;
        }
        refreshCraftingProviderState(online, null);
        flushPendingCraftingProviderRefresh(online);
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        World world = getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        boolean active = getMainNode().isActive();
        refreshCraftingProviderState(active, state);
        flushPendingCraftingProviderRefresh(active);
    }

    private void refreshCraftingProviderState(boolean active, @Nullable IGridNodeListener.State state) {
        if (!(this instanceof ICraftingProvider) || wasCraftingProviderActive == active) {
            return;
        }

        wasCraftingProviderActive = active;
        ICraftingProvider.requestUpdate(getMainNode());
        if (active) {
            craftingProviderRefreshPending = false;
        }
        GTLog.logger.debug("Refreshing AE2 crafting patterns for {} at {} after {} (active={})",
                metaTileEntityId, getPos(), state == null ? "periodic state check" : state, active);
    }

    /**
     * Queue a provider-cache refresh until the managed node is actually active.
     *
     * <p>AE2 mounts a provider while its node is joining the grid, before a channel can be assigned. A provider that
     * returns no patterns during that first mount must therefore be refreshed after activation. Keeping this state on
     * the host also makes NBT-loaded pattern inventories independent of the machine update interval.</p>
     */
    protected final void queueCraftingProviderRefresh() {
        if (!(this instanceof ICraftingProvider)) {
            return;
        }

        craftingProviderRefreshPending = true;
        World world = getWorld();
        if (world != null && !world.isRemote) {
            flushPendingCraftingProviderRefresh(getMainNode().isActive());
        }
    }

    private void flushPendingCraftingProviderRefresh(boolean active) {
        if (!craftingProviderRefreshPending || !(this instanceof ICraftingProvider) || !active) {
            return;
        }

        craftingProviderRefreshPending = false;
        ICraftingProvider.requestUpdate(getMainNode());
    }

    @Override
    public void destroyMainNode() {
        if (mainNode != null) {
            mainNode.destroy();
            mainNode = null;
        }
        isOnline = false;
        wasCraftingProviderActive = false;
        craftingProviderRefreshPending = this instanceof ICraftingProvider;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("AllowExtraConnections", allowsExtraConnections);
        data.setInteger(REFRESH_RATE_TAG, refreshRate);
        data.setBoolean(WORKING_TAG, workingEnabled);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        allowsExtraConnections = data.getBoolean("AllowExtraConnections");
        if (data.hasKey(REFRESH_RATE_TAG)) {
            refreshRate = data.getInteger(REFRESH_RATE_TAG);
        }
        if (data.hasKey(WORKING_TAG)) {
            workingEnabled = data.getBoolean(WORKING_TAG);
        }
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
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }
        return super.getCapability(capability, side);
    }

    protected boolean shouldSyncME() {
        return getOffsetTimer() % getRefreshRate() == 0;
    }
}
