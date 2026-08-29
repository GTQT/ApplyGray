package applygray.mattermanipulator.uplink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import applygray.ApplyGrayMod;
import applygray.api.IAEManagedMetaTileEntity;
import applygray.client.renderer.texture.ApplyGrayTextures;
import applygray.integration.ae2.ApplyGrayGridNodeSupport;
import applygray.mattermanipulator.inventory.ResourceRequirements;

import gregtech.api.GTValues;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.common.ConfigHolder;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockPart;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import ae2.api.networking.GridFlags;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.CalculationStrategy;
import ae2.api.networking.crafting.ICraftingLink;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingRequester;
import ae2.api.networking.crafting.ICraftingSubmitResult;
import ae2.api.networking.security.IActionSource;
import ae2.api.networking.ticking.IGridTickable;
import ae2.api.networking.ticking.TickRateModulation;
import ae2.api.networking.ticking.TickingRequest;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.storage.MEStorage;
import ae2.api.storage.StorageHelper;
import ae2.api.util.AECableType;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.vec.Matrix4;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static gregtech.api.capability.GregtechDataCodes.UPDATE_ONLINE_STATUS;

/** The AE2-facing multiblock part used exclusively by a Quantum Uplink controller. */
public final class MetaTileEntityQuantumUplinkHatch extends MetaTileEntityMultiblockPart implements
                                                   IMultiblockAbilityPart<MetaTileEntityQuantumUplinkHatch>,
                                                   IAEManagedMetaTileEntity, ICraftingRequester, IGridTickable {

    private static final String EXTRA_CONNECTIONS_KEY = "ExtraConnections";
    private static final String CRAFTING_REQUESTS_KEY = "CraftingRequests";
    private static final int MAX_QUEUED_REQUESTS = 32;
    private static final int MAX_CRAFTING_TRANSITIONS_PER_TICK = 4;

    @Nullable
    private IManagedGridNode mainNode;
    private ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
    private boolean allowsExtraConnections;
    private final List<UplinkCraftingRequest> craftingRequests = new ArrayList<>();

    public MetaTileEntityQuantumUplinkHatch(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, GTValues.ZPM);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityQuantumUplinkHatch(metaTileEntityId);
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, @NotNull List<String> tooltip,
                               boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format("applygray.machine.matter_manipulator.quantum_uplink_hatch.tooltip.1"));
        tooltip.add(I18n.format("applygray.machine.matter_manipulator.quantum_uplink_hatch.tooltip.2"));
        tooltip.add(I18n.format("applygray.machine.matter_manipulator.quantum_uplink_hatch.tooltip.3"));
        tooltip.add(I18n.format("applygray.machine.matter_manipulator.quantum_uplink_hatch.tooltip.4"));
    }

    @Override
    public @NotNull IManagedGridNode getMainNode() {
        if (mainNode == null) {
            mainNode = ApplyGrayGridNodeSupport.createMainNode(this)
                    .setTagName("matter_manipulator_uplink")
                    .setFlags(GridFlags.REQUIRE_CHANNEL)
                    .setIdlePowerUsage(ConfigHolder.compat.ae2.meHatchEnergyUsage)
                    .setExposedOnSides(getConnectableSides())
                    .setVisualRepresentation(getStackForm())
                    .addService(ICraftingRequester.class, this)
                    .addService(IGridTickable.class, this);
        }
        return mainNode;
    }

    @Override
    public void update() {
        super.update();
        updateConnectionStatus();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        updateConnectionStatus();
    }

    private void updateConnectionStatus() {
        World world = getWorld();
        if (world == null || world.isRemote) return;

        ConnectionStatus currentStatus = determineConnectionStatus();
        if (connectionStatus == currentStatus) return;

        connectionStatus = currentStatus;
        writeCustomData(UPDATE_ONLINE_STATUS, buffer -> buffer.writeVarInt(currentStatus.ordinal()));
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buffer) {
        super.writeInitialSyncData(buffer);
        buffer.writeVarInt(connectionStatus.ordinal());
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buffer) {
        super.receiveInitialSyncData(buffer);
        connectionStatus = ConnectionStatus.fromOrdinal(buffer.readVarInt());
    }

    @Override
    public void receiveCustomData(int dataId, @NotNull PacketBuffer buffer) {
        super.receiveCustomData(dataId, buffer);
        if (dataId == UPDATE_ONLINE_STATUS) {
            connectionStatus = ConnectionStatus.fromOrdinal(buffer.readVarInt());
            scheduleRenderUpdate();
        }
    }

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull EnumFacing side) {
        return allowsExtraConnections || side == getFrontFacing() ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    public void setFrontFacing(EnumFacing frontFacing) {
        super.setFrontFacing(frontFacing);
        updateConnectableSides();
    }

    @Override
    public boolean onWireCutterClick(EntityPlayer playerIn, EnumHand hand, EnumFacing facing,
                                     CuboidRayTraceResult hitResult) {
        allowsExtraConnections = !allowsExtraConnections;
        updateConnectableSides();
        if (getWorld() != null && !getWorld().isRemote) markDirty();
        return true;
    }

    @Override
    public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing,
                                CuboidRayTraceResult hitResult) {
        if (!player.getHeldItem(hand).isEmpty()) return super.onRightClick(player, hand, facing, hitResult);
        World world = getWorld();
        if (world != null && !world.isRemote) {
            connectionStatus = determineConnectionStatus();
            player.sendStatusMessage(connectionStatusText(), true);
        }
        return true;
    }

    @Override
    public void destroyMainNode() {
        if (mainNode != null) {
            mainNode.destroy();
            mainNode = null;
        }
    }

    @Override
    public void onRemoval() {
        cancelCraftingRequests();
        destroyMainNode();
        super.onRemoval();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean(EXTRA_CONNECTIONS_KEY, allowsExtraConnections);
        NBTTagList serializedRequests = new NBTTagList();
        for (UplinkCraftingRequest request : craftingRequests) {
            serializedRequests.appendTag(request.writeToNbt());
        }
        data.setTag(CRAFTING_REQUESTS_KEY, serializedRequests);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        allowsExtraConnections = data.getBoolean(EXTRA_CONNECTIONS_KEY);
        craftingRequests.clear();
        NBTTagList serializedRequests = data.getTagList(CRAFTING_REQUESTS_KEY,
                net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < serializedRequests.tagCount() && craftingRequests.size() < MAX_QUEUED_REQUESTS;
             index++) {
            UplinkCraftingRequest request = UplinkCraftingRequest.readFromNbt(serializedRequests.getCompoundTagAt(index),
                    this);
            if (request != null && !request.isTerminal()) craftingRequests.add(request);
        }
    }

    @Override
    public @NotNull List<MultiblockAbility<?>> getAbilities() {
        return Collections.singletonList(MetaTileEntityQuantumUplink.UPLINK_CONNECTOR);
    }

    @Override
    public void registerAbilities(@NotNull AbilityInstances abilityInstances) {
        if (abilityInstances.isKey(MetaTileEntityQuantumUplink.UPLINK_CONNECTOR)) {
            abilityInstances.add(this);
        }
    }

    public boolean isOnline() {
        return connectionStatus() == ConnectionStatus.ONLINE;
    }

    ITextComponent connectionStatusText() {
        return switch (connectionStatus()) {
            case ONLINE -> new TextComponentTranslation(
                    "applygray.machine.matter_manipulator.quantum_uplink_hatch.status.online");
            case BOOTING -> new TextComponentTranslation(
                    "applygray.machine.matter_manipulator.quantum_uplink_hatch.status.booting");
            case MISSING_CHANNEL -> new TextComponentTranslation(
                    "applygray.machine.matter_manipulator.quantum_uplink_hatch.status.missing_channel");
            case UNPOWERED -> new TextComponentTranslation(
                    "applygray.machine.matter_manipulator.quantum_uplink_hatch.status.unpowered");
            case DISCONNECTED -> new TextComponentTranslation(
                    "applygray.machine.matter_manipulator.quantum_uplink_hatch.status.disconnected");
        };
    }

    private ConnectionStatus connectionStatus() {
        World world = getWorld();
        return world != null && !world.isRemote ? determineConnectionStatus() : connectionStatus;
    }

    private ConnectionStatus determineConnectionStatus() {
        IGridNode node = getMainNode().getNode();
        if (node == null || node.getConnections().isEmpty()) return ConnectionStatus.DISCONNECTED;
        if (!node.isPowered()) return ConnectionStatus.UNPOWERED;
        if (!node.meetsChannelRequirements()) return ConnectionStatus.MISSING_CHANNEL;
        if (!node.hasGridBooted()) return ConnectionStatus.BOOTING;
        return ConnectionStatus.ONLINE;
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        if (!shouldRenderOverlay()) return;

        (isOnline() ? ApplyGrayTextures.ME_INPUT_HATCH_ACTIVE : ApplyGrayTextures.ME_INPUT_HATCH)
                .renderSided(getFrontFacing(), renderState, translation, pipeline);
    }

    private enum ConnectionStatus {

        DISCONNECTED,
        UNPOWERED,
        MISSING_CHANNEL,
        BOOTING,
        ONLINE;

        private static ConnectionStatus fromOrdinal(int ordinal) {
            ConnectionStatus[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DISCONNECTED;
        }
    }

    @Nullable
    public MEStorage getNetworkStorage() {
        IGrid grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    @NotNull
    public IActionSource getActionSource() {
        return IActionSource.ofMachine(this);
    }

    @Override
    public @Nullable IGridNode getActionableNode() {
        return getMainNode().getNode();
    }

    UplinkCraftingRequestResult requestCrafting(UUID requesterId, String requestName,
                                                ResourceRequirements requirements) {
        if (getWorld() == null || getWorld().isRemote || !getMainNode().isActive()) {
            return UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.AE_OFFLINE);
        }
        if (craftingRequests.size() >= MAX_QUEUED_REQUESTS) {
            return UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.QUEUE_FULL);
        }

        try {
            UplinkCraftingRequest request = UplinkCraftingRequest.create(requesterId, requestName, requirements);
            craftingRequests.add(request);
            markDirty();
            wakeCraftingQueue();
            ApplyGrayMod.LOGGER.info("Queued Matter Manipulator uplink crafting request with {} item type(s) at {}",
                    request.jobs().size(), getPos());
            return UplinkCraftingRequestResult.accepted(request.jobs().size());
        } catch (IllegalArgumentException exception) {
            return UplinkCraftingRequestResult.rejected(requirements.isEmpty()
                    ? UplinkCraftingRequestResult.Status.EMPTY
                    : UplinkCraftingRequestResult.Status.INVALID_REQUIREMENTS);
        }
    }

    int cancelCraftingRequests(UUID requesterId) {
        int canceled = 0;
        Iterator<UplinkCraftingRequest> iterator = craftingRequests.iterator();
        while (iterator.hasNext()) {
            UplinkCraftingRequest request = iterator.next();
            if (!request.requesterId().equals(requesterId)) continue;
            request.cancel();
            iterator.remove();
            canceled++;
        }
        if (canceled > 0) {
            markDirty();
            wakeCraftingQueue();
        }
        return canceled;
    }

    void cancelAllCraftingRequests() {
        cancelCraftingRequests();
        markDirty();
    }

    /** Returns whether the controller should present its active crafting state. */
    boolean hasCraftingRequests() {
        return !craftingRequests.isEmpty();
    }

    @Override
    public @NotNull com.google.common.collect.ImmutableSet<ICraftingLink> getRequestedJobs() {
        com.google.common.collect.ImmutableSet.Builder<ICraftingLink> links =
                com.google.common.collect.ImmutableSet.builder();
        for (UplinkCraftingRequest request : craftingRequests) {
            for (UplinkCraftingRequest.Job job : request.jobs()) {
                if (job.state() == UplinkCraftingRequest.JobState.SUBMITTED && job.link() != null) {
                    links.add(job.link());
                }
            }
        }
        return links.build();
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, ae2.api.config.Actionable mode) {
        if (link == null || what == null || amount <= 0L || !ownsCraftingLink(link) || !getMainNode().isActive()) {
            return 0L;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid == null) return 0L;
        return StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), what, amount,
                getActionSource(), mode);
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        boolean changed = false;
        for (UplinkCraftingRequest request : craftingRequests) {
            for (UplinkCraftingRequest.Job job : request.jobs()) {
                if (!job.matches(link)) continue;
                if (link.isCanceled()) {
                    job.fail();
                } else if (link.isDone()) {
                    job.complete();
                }
                changed = true;
            }
        }
        if (changed) {
            finishTerminalRequests();
            markDirty();
            wakeCraftingQueue();
        }
    }

    @Override
    public @NotNull TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 20, craftingRequests.isEmpty());
    }

    @Override
    public @NotNull TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        World world = getWorld();
        if (world == null || world.isRemote || !getMainNode().isActive()) return TickRateModulation.SLEEP;
        if (craftingRequests.isEmpty()) return TickRateModulation.SLEEP;

        boolean changed = advanceCraftingRequests(world);
        finishTerminalRequests();
        if (changed) markDirty();
        return craftingRequests.isEmpty() ? TickRateModulation.SLEEP
                : changed ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
    }

    private EnumSet<EnumFacing> getConnectableSides() {
        return allowsExtraConnections ? EnumSet.allOf(EnumFacing.class) : EnumSet.of(getFrontFacing());
    }

    private void updateConnectableSides() {
        if (mainNode != null) mainNode.setExposedOnSides(getConnectableSides());
    }

    private boolean advanceCraftingRequests(World world) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) return false;

        boolean changed = false;
        int transitions = 0;
        for (UplinkCraftingRequest request : craftingRequests) {
            for (UplinkCraftingRequest.Job job : request.jobs()) {
                if (transitions >= MAX_CRAFTING_TRANSITIONS_PER_TICK) return changed;
                if (advanceCraftingJob(grid, world, request, job)) {
                    changed = true;
                    transitions++;
                }
            }
        }
        return changed;
    }

    private boolean advanceCraftingJob(IGrid grid, World world, UplinkCraftingRequest request,
                                       UplinkCraftingRequest.Job job) {
        return switch (job.state()) {
            case QUEUED -> beginCraftingCalculation(grid, world, request, job);
            case CALCULATING -> finishCraftingCalculation(grid, request, job);
            case SUBMITTED -> updateSubmittedCraftingJob(job);
            case COMPLETE, FAILED -> false;
        };
    }

    private boolean beginCraftingCalculation(IGrid grid, World world, UplinkCraftingRequest request,
                                             UplinkCraftingRequest.Job job) {
        AEItemKey key = AEItemKey.of(job.specification().toStack());
        if (key == null) {
            job.fail();
            ApplyGrayMod.LOGGER.warn("Rejected invalid Matter Manipulator crafting item for request {} at {}",
                    request.requestName(), getPos());
            return true;
        }
        Future<ICraftingPlan> calculation = grid.getCraftingService().beginCraftingCalculation(world,
                this::getActionSource, key, job.amount(), CalculationStrategy.REPORT_MISSING_ITEMS);
        if (calculation == null) {
            job.fail();
            ApplyGrayMod.LOGGER.warn("AE2 did not start Matter Manipulator crafting request {} at {}",
                    request.requestName(), getPos());
            return true;
        }
        job.beginCalculation(calculation);
        return true;
    }

    private boolean finishCraftingCalculation(IGrid grid, UplinkCraftingRequest request,
                                              UplinkCraftingRequest.Job job) {
        Future<ICraftingPlan> calculation = job.calculation();
        if (calculation == null) {
            job.fail();
            return true;
        }
        if (!calculation.isDone()) return false;

        try {
            ICraftingPlan plan = calculation.get();
            if (plan == null) {
                job.fail();
                return true;
            }
            ICraftingSubmitResult result = grid.getCraftingService().submitJob(plan, this, null, false,
                    getActionSource());
            if (!result.successful() || result.link() == null) {
                job.fail();
                ApplyGrayMod.LOGGER.warn("AE2 rejected Matter Manipulator crafting request {} at {}: {}",
                        request.requestName(), getPos(), result.errorCode());
                return true;
            }
            job.submit(result.link());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.fail();
            return true;
        } catch (CancellationException | ExecutionException exception) {
            job.fail();
            ApplyGrayMod.LOGGER.warn("AE2 calculation failed for Matter Manipulator request {} at {}",
                    request.requestName(), getPos(), exception);
            return true;
        }
    }

    private static boolean updateSubmittedCraftingJob(UplinkCraftingRequest.Job job) {
        ICraftingLink link = job.link();
        if (link == null || link.isCanceled()) {
            job.fail();
            return true;
        }
        if (link.isDone()) {
            job.complete();
            return true;
        }
        return false;
    }

    private boolean ownsCraftingLink(ICraftingLink link) {
        return craftingRequests.stream().anyMatch(request -> request.owns(link));
    }

    private void finishTerminalRequests() {
        Iterator<UplinkCraftingRequest> iterator = craftingRequests.iterator();
        while (iterator.hasNext()) {
            UplinkCraftingRequest request = iterator.next();
            if (!request.isTerminal()) continue;
            iterator.remove();
            notifyRequester(request);
            ApplyGrayMod.LOGGER.info("Matter Manipulator uplink crafting request {} at {} {}", request.requestName(),
                    getPos(), request.completedSuccessfully() ? "completed" : "failed");
        }
    }

    private void notifyRequester(UplinkCraftingRequest request) {
        World world = getWorld();
        if (world == null || world.getMinecraftServer() == null) return;
        EntityPlayerMP player = world.getMinecraftServer().getPlayerList().getPlayerByUUID(request.requesterId());
        if (player == null) return;
        player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(
                request.completedSuccessfully() ? "applygray.matter_manipulator.uplink.crafting.complete"
                        : "applygray.matter_manipulator.uplink.crafting.failed"), true);
    }

    private void cancelCraftingRequests() {
        for (UplinkCraftingRequest request : craftingRequests) {
            request.cancel();
        }
        craftingRequests.clear();
    }

    private void wakeCraftingQueue() {
        IGrid grid = getMainNode().getGrid();
        IGridNode node = getMainNode().getNode();
        if (grid != null && node != null) grid.getTickManager().wakeDevice(node);
    }
}
