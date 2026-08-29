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

import ae2.api.crafting.IPatternDetails;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.networking.GridFlags;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.CalculationStrategy;
import ae2.api.networking.crafting.ICraftingLink;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.crafting.ICraftingRequester;
import ae2.api.networking.crafting.ICraftingSubmitResult;
import ae2.api.networking.security.IActionSource;
import ae2.api.networking.ticking.IGridTickable;
import ae2.api.networking.ticking.TickRateModulation;
import ae2.api.networking.ticking.TickingRequest;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import ae2.api.storage.StorageHelper;
import ae2.api.util.AECableType;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.vec.Matrix4;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static gregtech.api.capability.GregtechDataCodes.UPDATE_ONLINE_STATUS;

/** The AE2-facing multiblock part used exclusively by a Quantum Uplink controller. */
public final class MetaTileEntityQuantumUplinkHatch extends MetaTileEntityMultiblockPart implements
                                                   IMultiblockAbilityPart<MetaTileEntityQuantumUplinkHatch>,
                                                   IAEManagedMetaTileEntity, ICraftingRequester, ICraftingProvider,
                                                   IGridTickable {

    private static final String EXTRA_CONNECTIONS_KEY = "ExtraConnections";
    private static final String CRAFTING_REQUESTS_KEY = "CraftingRequests";
    private static final String PENDING_DELIVERIES_KEY = "PendingDeliveries";
    private static final String NEXT_DISCRIMINATOR_KEY = "NextPlanDiscriminator";
    private static final int MAX_QUEUED_REQUESTS = 32;
    private static final int MAX_CRAFTING_TRANSITIONS_PER_TICK = 4;
    /** One pattern push can never hand over more than its own input slots; the rest is read-back sanity. */
    private static final int MAX_PENDING_DELIVERIES = 4 * UplinkPlanToken.MAX_PATTERN_INPUTS;
    private static final int MAX_REPORTED_MISSING_MATERIALS = 8;

    @Nullable
    private IManagedGridNode mainNode;
    private ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
    private boolean allowsExtraConnections;
    private final List<UplinkCraftingRequest> craftingRequests = new ArrayList<>();
    /** Materials handed over by a pattern push, waiting to be put back into network storage. */
    private final List<GenericStack> pendingDeliveries = new ArrayList<>();
    private List<IPatternDetails> advertisedPatterns = List.of();
    private boolean advertisedPatternsDirty = true;
    private long nextDiscriminator;

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
                    .addService(ICraftingProvider.class, this)
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
        data.setLong(NEXT_DISCRIMINATOR_KEY, nextDiscriminator);
        NBTTagList serializedRequests = new NBTTagList();
        for (UplinkCraftingRequest request : craftingRequests) {
            serializedRequests.appendTag(request.writeToNbt());
        }
        data.setTag(CRAFTING_REQUESTS_KEY, serializedRequests);
        data.setTag(PENDING_DELIVERIES_KEY, GenericStack.writeList(pendingDeliveries));
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        allowsExtraConnections = data.getBoolean(EXTRA_CONNECTIONS_KEY);
        nextDiscriminator = data.getLong(NEXT_DISCRIMINATOR_KEY);
        craftingRequests.clear();
        NBTTagList serializedRequests = data.getTagList(CRAFTING_REQUESTS_KEY,
                net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < serializedRequests.tagCount() && craftingRequests.size() < MAX_QUEUED_REQUESTS;
             index++) {
            UplinkCraftingRequest request = UplinkCraftingRequest.readFromNbt(serializedRequests.getCompoundTagAt(index),
                    this);
            if (request != null && !request.isTerminal()) craftingRequests.add(request);
        }

        pendingDeliveries.clear();
        for (GenericStack delivery : GenericStack.readList(data.getTagList(PENDING_DELIVERIES_KEY,
                net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND))) {
            if (delivery == null || delivery.amount() <= 0L) continue;
            if (pendingDeliveries.size() >= MAX_PENDING_DELIVERIES) break;
            pendingDeliveries.add(delivery);
        }
        invalidateAdvertisedPatterns();
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
            UplinkCraftingRequest request = UplinkCraftingRequest.create(requesterId, requestName, nextDiscriminator,
                    requirements);
            craftingRequests.add(request);
            nextDiscriminator++;
            invalidateAdvertisedPatterns();
            markDirty();
            wakeCraftingQueue();
            ApplyGrayMod.LOGGER.info("Advertised Matter Manipulator uplink plan '{}' as {} pattern(s) at {}",
                    request.requestName(), request.plans().size(), getPos());
            return UplinkCraftingRequestResult.accepted(requirements.entries().size() +
                    requirements.fluidEntries().size());
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
            invalidateAdvertisedPatterns();
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
            for (UplinkCraftingRequest.Plan plan : request.plans()) {
                if (plan.link() != null) links.add(plan.link());
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
            for (UplinkCraftingRequest.Plan plan : request.plans()) {
                if (!plan.matches(link)) continue;
                if (link.isDone() || plan.state() == UplinkCraftingRequest.PlanState.DELIVERED) {
                    plan.complete();
                } else if (link.isCanceled()) {
                    plan.awaitManual();
                    notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.awaiting_manual",
                            request.requestName());
                }
                changed = true;
            }
        }
        if (changed) {
            invalidateAdvertisedPatterns();
            markDirty();
            wakeCraftingQueue();
        }
    }

    @Override
    public @NotNull TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 20, isCraftingIdle());
    }

    @Override
    public @NotNull TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        World world = getWorld();
        if (world == null || world.isRemote || !getMainNode().isActive()) return TickRateModulation.SLEEP;
        if (isCraftingIdle()) return TickRateModulation.SLEEP;

        boolean changed = drainPendingDeliveries();
        changed |= advanceCraftingRequests(world);
        changed |= finishTerminalRequests();
        if (advertisedPatternsDirty) ICraftingProvider.requestUpdate(getMainNode());
        if (changed) markDirty();
        return isCraftingIdle() ? TickRateModulation.SLEEP
                : changed ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
    }

    private boolean isCraftingIdle() {
        return craftingRequests.isEmpty() && pendingDeliveries.isEmpty();
    }

    /**
     * Every plan that has not delivered its materials yet stays advertised, which is what makes it visible, searchable
     * and manually craftable from any ME terminal.
     */
    @Override
    public @NotNull List<IPatternDetails> getAvailablePatterns() {
        World world = getWorld();
        if (world == null || world.isRemote) return List.of();
        if (!advertisedPatternsDirty) return advertisedPatterns;

        List<IPatternDetails> patterns = new ArrayList<>();
        for (UplinkCraftingRequest request : craftingRequests) {
            for (UplinkCraftingRequest.Plan plan : request.plans()) {
                if (plan.state() == UplinkCraftingRequest.PlanState.COMPLETE) continue;
                IPatternDetails details = plan.details(world);
                if (details != null) {
                    patterns.add(details);
                } else {
                    ApplyGrayMod.LOGGER.warn("Could not decode Matter Manipulator uplink plan '{}' at {}",
                            request.requestName(), getPos());
                }
            }
        }
        advertisedPatterns = List.copyOf(patterns);
        advertisedPatternsDirty = false;
        return advertisedPatterns;
    }

    /**
     * A plan is a no-op recipe: AE2 gathers the planned materials, hands them over here, and they go straight back into
     * network storage where the manipulator can spend them. The order token itself is never produced.
     */
    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder, int multiplier) {
        World world = getWorld();
        if (world == null || world.isRemote || isBusy()) return false;

        UplinkCraftingRequest.Plan target = null;
        for (UplinkCraftingRequest request : craftingRequests) {
            UplinkCraftingRequest.Plan plan = request.findPlan(patternDetails);
            if (plan != null && plan.state() != UplinkCraftingRequest.PlanState.COMPLETE) {
                target = plan;
                break;
            }
        }
        if (target == null) return false;

        for (KeyCounter counter : inputHolder) {
            for (Object2LongMap.Entry<AEKey> input : counter) {
                if (input.getLongValue() > 0L) {
                    pendingDeliveries.add(new GenericStack(input.getKey(), input.getLongValue()));
                }
            }
        }
        // The link is wound down on the next tick instead of here, so AE2 is never re-entered mid-push.
        target.deliver();
        invalidateAdvertisedPatterns();
        markDirty();
        wakeCraftingQueue();
        return true;
    }

    /** Plans are pushed one at a time so a single push never hands over more than one pattern's worth of materials. */
    @Override
    public boolean canMergePatternPush(IPatternDetails patternDetails) {
        return false;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails patternDetails, int maxMultiplier) {
        return 1;
    }

    @Override
    public boolean isBusy() {
        return !pendingDeliveries.isEmpty();
    }

    @Override
    public @NotNull PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(AEItemKey.of(getStackForm()), new TextComponentTranslation(getMetaFullName()),
                List.of());
    }

    private void invalidateAdvertisedPatterns() {
        advertisedPatterns = List.of();
        advertisedPatternsDirty = true;
    }

    /** Puts pushed materials back into network storage; anything that does not fit is retried next tick. */
    private boolean drainPendingDeliveries() {
        if (pendingDeliveries.isEmpty()) return false;
        IGrid grid = getMainNode().getGrid();
        if (grid == null) return false;

        boolean changed = false;
        java.util.ListIterator<GenericStack> iterator = pendingDeliveries.listIterator();
        while (iterator.hasNext()) {
            GenericStack delivery = iterator.next();
            long inserted = StorageHelper.poweredInsert(grid.getEnergyService(),
                    grid.getStorageService().getInventory(), delivery.what(), delivery.amount(), getActionSource(),
                    ae2.api.config.Actionable.MODULATE);
            if (inserted <= 0L) continue;
            changed = true;
            if (inserted >= delivery.amount()) {
                iterator.remove();
            } else {
                iterator.set(new GenericStack(delivery.what(), delivery.amount() - inserted));
            }
        }
        return changed;
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
            for (UplinkCraftingRequest.Plan plan : request.plans()) {
                if (transitions >= MAX_CRAFTING_TRANSITIONS_PER_TICK) return changed;
                if (advanceCraftingPlan(grid, world, request, plan)) {
                    changed = true;
                    transitions++;
                }
            }
        }
        return changed;
    }

    private boolean advanceCraftingPlan(IGrid grid, World world, UplinkCraftingRequest request,
                                        UplinkCraftingRequest.Plan plan) {
        return switch (plan.state()) {
            case PENDING -> beginCraftingCalculation(grid, world, request, plan);
            case CALCULATING -> finishCraftingCalculation(grid, request, plan);
            case SUBMITTED -> updateSubmittedPlan(request, plan);
            case DELIVERED -> finishDeliveredPlan(request, plan);
            case AWAITING_MANUAL, COMPLETE -> false;
        };
    }

    /** Fires the single automatic attempt: request the plan's own order token so AE2 gathers its materials. */
    private boolean beginCraftingCalculation(IGrid grid, World world, UplinkCraftingRequest request,
                                             UplinkCraftingRequest.Plan plan) {
        AEItemKey token = AEItemKey.of(plan.token());
        if (token == null) {
            plan.awaitManual();
            ApplyGrayMod.LOGGER.warn("Matter Manipulator uplink plan '{}' has an invalid order token at {}",
                    request.requestName(), getPos());
            return true;
        }
        Future<ICraftingPlan> calculation = grid.getCraftingService().beginCraftingCalculation(world,
                this::getActionSource, token, 1L, CalculationStrategy.REPORT_MISSING_ITEMS);
        if (calculation == null) {
            plan.awaitManual();
            notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.awaiting_manual",
                    request.requestName());
            return true;
        }
        plan.beginCalculation(calculation);
        return true;
    }

    private boolean finishCraftingCalculation(IGrid grid, UplinkCraftingRequest request,
                                              UplinkCraftingRequest.Plan plan) {
        Future<ICraftingPlan> calculation = plan.calculation();
        if (calculation == null) {
            plan.awaitManual();
            return true;
        }
        if (!calculation.isDone()) return false;

        try {
            ICraftingPlan craftingPlan = calculation.get();
            if (craftingPlan == null) {
                reportUnavailableMaterials(request, plan, null);
                return true;
            }
            if (craftingPlan.simulation() || !craftingPlan.missingItems().isEmpty()) {
                reportUnavailableMaterials(request, plan, craftingPlan.missingItems());
                return true;
            }
            ICraftingSubmitResult result = grid.getCraftingService().submitJob(craftingPlan, this, null, false,
                    getActionSource());
            if (!result.successful() || result.link() == null) {
                reportUnavailableMaterials(request, plan, craftingPlan.missingItems());
                ApplyGrayMod.LOGGER.warn("AE2 rejected Matter Manipulator uplink plan '{}' at {}: {}",
                        request.requestName(), getPos(), result.errorCode());
                return true;
            }
            plan.submit(result.link());
            notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.submitted",
                    request.requestName());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plan.awaitManual();
            return true;
        } catch (CancellationException | ExecutionException exception) {
            plan.awaitManual();
            ApplyGrayMod.LOGGER.warn("AE2 calculation failed for Matter Manipulator uplink plan '{}' at {}",
                    request.requestName(), getPos(), exception);
            return true;
        }
    }

    /**
     * Ends the single automatic attempt and tells the player what is missing. The pattern stays advertised, so the plan
     * can be crafted by hand from an ME terminal once the network has the materials.
     */
    private void reportUnavailableMaterials(UplinkCraftingRequest request, UplinkCraftingRequest.Plan plan,
                                            @Nullable KeyCounter missingItems) {
        plan.awaitManual();
        invalidateAdvertisedPatterns();
        String missing = missingItems == null ? "" : describeMaterials(missingItems);
        if (missing.isEmpty()) {
            notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.awaiting_manual",
                    request.requestName());
        } else {
            notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.missing",
                    request.requestName(), missing);
        }
    }

    private static String describeMaterials(KeyCounter materials) {
        StringBuilder description = new StringBuilder();
        int described = 0;
        for (Object2LongMap.Entry<AEKey> material : materials) {
            if (material.getLongValue() <= 0L) continue;
            if (described >= MAX_REPORTED_MISSING_MATERIALS) {
                description.append(", ...");
                break;
            }
            if (described > 0) description.append(", ");
            description.append(material.getKey().getDisplayName().getUnformattedText()).append(" x")
                    .append(material.getKey().formatAmount(material.getLongValue(), AmountFormat.FULL));
            described++;
        }
        return description.toString();
    }

    private boolean updateSubmittedPlan(UplinkCraftingRequest request, UplinkCraftingRequest.Plan plan) {
        ICraftingLink link = plan.link();
        if (link == null || link.isCanceled()) {
            plan.awaitManual();
            invalidateAdvertisedPatterns();
            notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.awaiting_manual",
                    request.requestName());
            return true;
        }
        if (link.isDone()) {
            plan.complete();
            invalidateAdvertisedPatterns();
            return true;
        }
        return false;
    }

    /** Winds down the automatic job of a plan whose materials have already been handed over. */
    private boolean finishDeliveredPlan(UplinkCraftingRequest request, UplinkCraftingRequest.Plan plan) {
        plan.complete();
        invalidateAdvertisedPatterns();
        ApplyGrayMod.LOGGER.info("Matter Manipulator uplink plan '{}' at {} delivered its materials",
                request.requestName(), getPos());
        return true;
    }

    private boolean ownsCraftingLink(ICraftingLink link) {
        return craftingRequests.stream().anyMatch(request -> request.owns(link));
    }

    private boolean finishTerminalRequests() {
        boolean changed = false;
        Iterator<UplinkCraftingRequest> iterator = craftingRequests.iterator();
        while (iterator.hasNext()) {
            UplinkCraftingRequest request = iterator.next();
            if (!request.isTerminal()) continue;
            iterator.remove();
            changed = true;
            notifyPlayer(request.requesterId(), "applygray.matter_manipulator.uplink.crafting.complete",
                    request.requestName());
            ApplyGrayMod.LOGGER.info("Matter Manipulator uplink plan '{}' at {} completed", request.requestName(),
                    getPos());
        }
        if (changed) invalidateAdvertisedPatterns();
        return changed;
    }

    private void notifyPlayer(UUID requesterId, String translationKey, Object... arguments) {
        World world = getWorld();
        if (world == null || world.getMinecraftServer() == null) return;
        EntityPlayerMP player = world.getMinecraftServer().getPlayerList().getPlayerByUUID(requesterId);
        if (player != null) player.sendMessage(new TextComponentTranslation(translationKey, arguments));
    }

    private void cancelCraftingRequests() {
        for (UplinkCraftingRequest request : craftingRequests) {
            request.cancel();
        }
        craftingRequests.clear();
        invalidateAdvertisedPatterns();
    }

    private void wakeCraftingQueue() {
        IGrid grid = getMainNode().getGrid();
        IGridNode node = getMainNode().getNode();
        if (grid != null && node != null) grid.getTickManager().wakeDevice(node);
    }
}
