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

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import ae2.api.networking.GridFlags;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
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
import codechicken.lib.raytracer.CuboidRayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        return getMainNode().isActive();
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
