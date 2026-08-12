package applygray.mattermanipulator.server;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import applygray.ApplyGrayMod;
import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.building.BuildTransaction;
import applygray.mattermanipulator.building.BuildingAdapterRegistry;
import applygray.mattermanipulator.building.BuildingContext;
import applygray.mattermanipulator.building.BuildingException;
import applygray.mattermanipulator.building.VanillaBuildingAdapter;
import applygray.mattermanipulator.building.CableBuildRequest;
import applygray.mattermanipulator.building.CableBuildService;
import applygray.mattermanipulator.building.CopyBuildRequest;
import applygray.mattermanipulator.building.CopyBuildResult;
import applygray.mattermanipulator.building.CopyBuildService;
import applygray.mattermanipulator.building.ExchangeBuildRequest;
import applygray.mattermanipulator.building.ExchangeBuildResult;
import applygray.mattermanipulator.building.ExchangeBuildService;
import applygray.mattermanipulator.building.GeometryBuildRequest;
import applygray.mattermanipulator.building.GeometryBuildResult;
import applygray.mattermanipulator.building.GeometryBuildService;
import applygray.mattermanipulator.building.GeometryConfiguration;
import applygray.mattermanipulator.building.MoveBuildRequest;
import applygray.mattermanipulator.building.MoveBuildResult;
import applygray.mattermanipulator.building.MoveBuildService;
import applygray.mattermanipulator.building.PreparedBlockChange;
import applygray.mattermanipulator.inventory.ElectricItemPowerSource;
import applygray.mattermanipulator.inventory.InsufficientOutputCapacityException;
import applygray.mattermanipulator.inventory.InsufficientPowerException;
import applygray.mattermanipulator.inventory.InsufficientResourcesException;
import applygray.mattermanipulator.inventory.ItemHandlerMaterialSource;
import applygray.mattermanipulator.inventory.MaterialSource;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.integration.ae2.Ae2WirelessMaterialSource;
import applygray.mattermanipulator.integration.ae2.Ae2BuildingAdapter;
import applygray.mattermanipulator.integration.gregtech.GregTechBuildingAdapter;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.uplink.UplinkMaterialSource;
import applygray.mattermanipulator.uplink.MatterManipulatorUplinkRegistry;
import applygray.mattermanipulator.uplink.UplinkCraftingEndpoint;
import applygray.mattermanipulator.uplink.UplinkCraftingRequestResult;
import applygray.mattermanipulator.uplink.UplinkEndpoint;
import applygray.mattermanipulator.planning.BoundCopyPlan;
import applygray.mattermanipulator.planning.BoundCopyOperation;
import applygray.mattermanipulator.planning.BoundExchangePlan;
import applygray.mattermanipulator.planning.BoundExchangeOperation;
import applygray.mattermanipulator.planning.BoundGeometryPlan;
import applygray.mattermanipulator.planning.BoundGeometryOperation;
import applygray.mattermanipulator.planning.CopyPlan;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.items.wrapper.PlayerOffhandInvWrapper;

/** Server-only, mode-aware executor for bounded Matter Manipulator operations. */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID)
public final class MatterManipulatorBuildManager {

    private static final BuildingAdapterRegistry ADAPTERS = new BuildingAdapterRegistry(List.of(
            new GregTechBuildingAdapter(), new Ae2BuildingAdapter(), new VanillaBuildingAdapter()));
    private static final GeometryBuildService GEOMETRY_SERVICE = new GeometryBuildService(ADAPTERS);
    private static final CopyBuildService COPY_SERVICE = new CopyBuildService(ADAPTERS);
    private static final ExchangeBuildService EXCHANGE_SERVICE = new ExchangeBuildService(ADAPTERS);
    private static final CableBuildService CABLE_SERVICE = new CableBuildService(ADAPTERS);
    private static final MoveBuildService MOVE_SERVICE = new MoveBuildService(ADAPTERS);
    private static final Map<UUID, PendingBuild> ACTIVE_BUILDS = new LinkedHashMap<>();

    private MatterManipulatorBuildManager() {}

    /** Starts the selected operation immediately; subsequent server ticks execute bounded batches. */
    public static void start(EntityPlayerMP player, EnumHand hand) {
        UUID playerId = player.getUniqueID();
        if (ACTIVE_BUILDS.containsKey(playerId)) return;

        ItemStack stack = player.getHeldItem(hand);
        if (!(stack.getItem() instanceof ItemMatterManipulator manipulator)) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_tool"), true);
            return;
        }

        try {
            ManipulatorState state = copyState(manipulator.state(stack));
            PendingBuild pending = createPendingBuild(player, hand, stack, manipulator, state);
            ACTIVE_BUILDS.put(playerId, pending);
            if (pending.kind == BuildKind.GEOMETRY || pending.kind == BuildKind.CABLE) {
                int air = 0;
                for (BoundGeometryOperation operation : (pending.kind == BuildKind.GEOMETRY
                        ? ((BoundGeometryPlan) pending.plan).operations()
                        : ((BoundGeometryPlan) pending.plan).operations())) {
                    if (operation.block().isAir()) air++;
                }
                if (pending.kind == BuildKind.GEOMETRY) {
                    var geometry = ((BoundGeometryPlan) pending.plan).geometry();
                    GeometryConfiguration configuration = pending.state.geometryConfiguration();
                    ApplyGrayMod.LOGGER.info("Matter Manipulator GEOMETRY selection for {}: shape={}, A={}, B={}, C={}, "
                                    + "materialSlots corner={} edge={} face={} volume={}, plannedRoles corner={} edge={} face={} volume={}, operations={}, air={}",
                            player.getName(), pending.state.shape(), pending.state.selectionA(), pending.state.selectionB(),
                            pending.state.selectionC(), configuration.corners().entries().size(),
                            configuration.edges().entries().size(), configuration.faces().entries().size(),
                            configuration.volumes().entries().size(), geometry.count(applygray.mattermanipulator.planning.VoxelRole.CORNER),
                            geometry.count(applygray.mattermanipulator.planning.VoxelRole.EDGE),
                            geometry.count(applygray.mattermanipulator.planning.VoxelRole.FACE),
                            geometry.count(applygray.mattermanipulator.planning.VoxelRole.VOLUME), pending.operationCount, air);
                } else {
                    ApplyGrayMod.LOGGER.info("Matter Manipulator {} plan for {}: {} operation(s), {} air operation(s)",
                            pending.kind, player.getName(), pending.operationCount, air);
                }
            }
            try {
                if (runNextStep(player, stack, pending)) {
                    ACTIVE_BUILDS.remove(playerId);
                    int changed = pending.lastWorldChanges;
                    ApplyGrayMod.LOGGER.info("Matter Manipulator {} operation completed immediately for {}: {} world change(s)",
                            pending.kind, player.getName(), changed);
                    player.sendStatusMessage(new TextComponentTranslation(changed == 0
                            ? "applygray.matter_manipulator.build.no_changes"
                            : "applygray.matter_manipulator.build.complete", changed == 0 ? 0 : pending.operationCount), true);
                    return;
                }
                pending.ticksUntilNextBatch = batchInterval(pending) - 1;
            } catch (RuntimeException exception) {
                ACTIVE_BUILDS.remove(playerId);
                reportStartFailure(player, exception);
                return;
            }
            ApplyGrayMod.LOGGER.info("Matter Manipulator {} operation started for {} with {} block(s)", pending.kind,
                    player.getName(), pending.operationCount);
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.started",
                    pending.operationCount), true);
        } catch (RuntimeException exception) {
            reportStartFailure(player, exception);
        }
    }

    /** Stops the operation when the player releases the use key. */
    public static void stop(EntityPlayerMP player, EnumHand hand) {
        PendingBuild current = ACTIVE_BUILDS.remove(player.getUniqueID());
        if (current == null || current.hand != hand) return;
        ApplyGrayMod.LOGGER.info("Matter Manipulator {} operation canceled for {}", current.kind, player.getName());
        player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.canceled"), true);
    }

    /**
     * Computes the selected operation on the server and queues either its full or its still-missing input set at the
     * bound Quantum Uplink. The client only chooses the mode; it never supplies materials, positions, or quantities.
     */
    public static void requestUplinkCrafting(EntityPlayerMP player, EnumHand hand, boolean includeAvailableMaterials) {
        ItemStack stack = player.getHeldItem(hand);
        if (!(stack.getItem() instanceof ItemMatterManipulator manipulator)) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_tool"), true);
            return;
        }

        ManipulatorState state = copyState(manipulator.state(stack));
        if (!manipulator.hasCapability(stack, ManipulatorCapability.UPLINK) || state.uplinkAddress() == null) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.uplink.not_bound"), true);
            return;
        }

        UplinkEndpoint endpoint = MatterManipulatorUplinkRegistry.find(state.uplinkAddress());
        if (!(endpoint instanceof UplinkCraftingEndpoint craftingEndpoint)) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.uplink.offline"), true);
            return;
        }

        try {
            ResourceRequirements requirements = currentOperationRequirements(player, hand, stack, manipulator, state);
            if (requirements.isEmpty()) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "applygray.matter_manipulator.uplink.crafting.no_requirements"), true);
                return;
            }

            if (!includeAvailableMaterials) {
                requirements = missingRequirements(requirements, resources(player, stack, manipulator).materialSources);
                if (requirements.isEmpty()) {
                    player.sendStatusMessage(new TextComponentTranslation(
                            "applygray.matter_manipulator.uplink.crafting.nothing_missing"), true);
                    return;
                }
            }

            UplinkCraftingRequestResult result = craftingEndpoint.requestCrafting(player,
                    state.placeMode().name().toLowerCase(), requirements);
            if (result.accepted()) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "applygray.matter_manipulator.uplink.crafting.queued", result.jobCount()), true);
            } else {
                reportCraftingRequestFailure(player, result.status());
            }
        } catch (RuntimeException exception) {
            reportStartFailure(player, exception);
        }
    }

    /** Cancels this player's direct requests only; other users bound to the same Uplink remain untouched. */
    public static void cancelUplinkCrafting(EntityPlayerMP player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!(stack.getItem() instanceof ItemMatterManipulator manipulator)) return;
        ManipulatorState state = manipulator.state(stack);
        if (!manipulator.hasCapability(stack, ManipulatorCapability.UPLINK) || state.uplinkAddress() == null) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.uplink.not_bound"), true);
            return;
        }

        UplinkEndpoint endpoint = MatterManipulatorUplinkRegistry.find(state.uplinkAddress());
        int canceled = endpoint instanceof UplinkCraftingEndpoint craftingEndpoint
                ? craftingEndpoint.cancelCraftingRequests(player.getUniqueID()) : 0;
        player.sendStatusMessage(new TextComponentTranslation(canceled == 0
                ? "applygray.matter_manipulator.uplink.crafting.none"
                : "applygray.matter_manipulator.uplink.crafting.canceled", canceled), true);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_BUILDS.isEmpty()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        Iterator<PendingBuild> iterator = ACTIVE_BUILDS.values().iterator();
        while (iterator.hasNext()) {
            PendingBuild pending = iterator.next();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(pending.playerId);
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (pending.ticksUntilNextBatch-- > 0) continue;

            ItemStack stack = player.getHeldItem(pending.hand);
            if (stack.getItem() != pending.manipulator) {
                iterator.remove();
                player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.tool_changed"),
                        true);
                continue;
            }

            try {
                if (runNextStep(player, stack, pending)) {
                    iterator.remove();
                    int changed = pending.lastWorldChanges;
                    ApplyGrayMod.LOGGER.info("Matter Manipulator {} operation completed for {} with {} block(s), {} world change(s)",
                            pending.kind, player.getName(), pending.operationCount, changed);
                    player.sendStatusMessage(new TextComponentTranslation(changed == 0
                            ? "applygray.matter_manipulator.build.no_changes"
                            : "applygray.matter_manipulator.build.complete", changed == 0 ? 0 : pending.operationCount), true);
                } else {
                    pending.ticksUntilNextBatch = batchInterval(pending) - 1;
                }
            } catch (RuntimeException exception) {
                iterator.remove();
                reportStartFailure(player, exception);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE_BUILDS.remove(event.player.getUniqueID());
    }

    private static PendingBuild createPendingBuild(EntityPlayerMP player, EnumHand hand, ItemStack stack,
                                                   ItemMatterManipulator manipulator, ManipulatorState state) {
        ResourceAccess access = resources(player, stack, manipulator);
        return switch (state.placeMode()) {
            case GEOMETRY -> {
                GeometryBuildRequest request = new GeometryBuildRequest(player, stack, hand, manipulator.tier(), state,
                        access.materialSources, access.powerSource);
                BoundGeometryPlan plan = GEOMETRY_SERVICE.createPlan(request);
                yield new PendingBuild(player.getUniqueID(), hand, manipulator, state, BuildKind.GEOMETRY, plan,
                        plan.operations().size());
            }
            case COPYING -> {
                CopyBuildRequest request = new CopyBuildRequest(player, stack, hand, manipulator.tier(), state,
                        access.materialSources, access.powerSource);
                BoundCopyPlan plan = COPY_SERVICE.createPlan(request);
                yield new PendingBuild(player.getUniqueID(), hand, manipulator, state, BuildKind.COPY, plan,
                        plan.operations().size());
            }
            case MOVING -> {
                MoveBuildRequest request = new MoveBuildRequest(player, stack, hand, manipulator.tier(), state,
                        access.materialSources, access.powerSource);
                CopyPlan plan = MOVE_SERVICE.createPlan(request);
                yield new PendingBuild(player.getUniqueID(), hand, manipulator, state, BuildKind.MOVE, plan,
                        plan.operations().size());
            }
            case EXCHANGING -> {
                ExchangeBuildRequest request = new ExchangeBuildRequest(player, stack, hand, manipulator.tier(), state,
                        access.materialSources, access.powerSource);
                BoundExchangePlan plan = EXCHANGE_SERVICE.createPlan(request);
                yield new PendingBuild(player.getUniqueID(), hand, manipulator, state, BuildKind.EXCHANGE, plan,
                        plan.operations().size());
            }
            case CABLES -> {
                CableBuildRequest request = new CableBuildRequest(player, stack, hand, manipulator.tier(), state,
                        access.materialSources, access.powerSource);
                BoundGeometryPlan plan = CABLE_SERVICE.createPlan(request);
                yield new PendingBuild(player.getUniqueID(), hand, manipulator, state, BuildKind.CABLE, plan,
                        plan.operations().size());
            }
        };
    }

    /** Returns true once the current operation is complete; transaction failures are reported and terminate it. */
    private static boolean runNextStep(EntityPlayerMP player, ItemStack stack, PendingBuild pending) {
        ResourceAccess access = resources(player, stack, pending.manipulator);
        return switch (pending.kind) {
            case GEOMETRY -> {
                GeometryBuildRequest request = new GeometryBuildRequest(player, stack, pending.hand,
                        pending.manipulator.tier(), pending.state, access.materialSources, access.powerSource);
                GeometryBuildResult result = GEOMETRY_SERVICE.executeNextBatch(request,
                        (BoundGeometryPlan) pending.plan, pending.nextOperationIndex);
                logBatchResult(player, pending, result.transaction());
                if (!result.transaction().committed()) throw new TransactionFailedException(result.transaction());
                pending.nextOperationIndex = result.nextOperationIndex();
                yield result.complete();
            }
            case COPY -> {
                CopyBuildRequest request = new CopyBuildRequest(player, stack, pending.hand, pending.manipulator.tier(),
                        pending.state, access.materialSources, access.powerSource);
                CopyBuildResult result = COPY_SERVICE.executeNextBatch(request, (BoundCopyPlan) pending.plan,
                        pending.nextOperationIndex);
                logBatchResult(player, pending, result.transaction());
                if (!result.transaction().committed()) throw new TransactionFailedException(result.transaction());
                pending.nextOperationIndex = result.nextOperationIndex();
                yield result.complete();
            }
            case MOVE -> {
                MoveBuildRequest request = new MoveBuildRequest(player, stack, pending.hand, pending.manipulator.tier(),
                        pending.state, access.materialSources, access.powerSource);
                MoveBuildResult result = MOVE_SERVICE.execute(request, (CopyPlan) pending.plan);
                logBatchResult(player, pending, result.transaction());
                if (!result.transaction().committed()) throw new TransactionFailedException(result.transaction());
                yield true;
            }
            case EXCHANGE -> {
                ExchangeBuildRequest request = new ExchangeBuildRequest(player, stack, pending.hand,
                        pending.manipulator.tier(), pending.state, access.materialSources, access.powerSource);
                ExchangeBuildResult result = EXCHANGE_SERVICE.executeNextBatch(request, (BoundExchangePlan) pending.plan,
                        pending.nextOperationIndex);
                logBatchResult(player, pending, result.transaction());
                if (!result.transaction().committed()) throw new TransactionFailedException(result.transaction());
                pending.nextOperationIndex = result.nextOperationIndex();
                yield result.complete();
            }
            case CABLE -> {
                CableBuildRequest request = new CableBuildRequest(player, stack, pending.hand, pending.manipulator.tier(),
                        pending.state, access.materialSources, access.powerSource);
                GeometryBuildResult result = CABLE_SERVICE.executeNextBatch(request, (BoundGeometryPlan) pending.plan,
                        pending.nextOperationIndex);
                logBatchResult(player, pending, result.transaction());
                if (!result.transaction().committed()) throw new TransactionFailedException(result.transaction());
                pending.nextOperationIndex = result.nextOperationIndex();
                yield result.complete();
            }
        };
    }

    private static void logBatchResult(EntityPlayerMP player, PendingBuild pending, BuildTransaction.Result result) {
        pending.lastWorldChanges = result.worldChanges();
        if (!result.committed()) {
            ApplyGrayMod.LOGGER.warn("Matter Manipulator {} batch failed for {}: state={}, source={}, failure={}",
                    pending.kind, player.getName(), result.state(), result.failedSource(), result.failure());
        }
    }

    private static int batchInterval(PendingBuild pending) {
        int interval = pending.manipulator.tier().batchIntervalTicks();
        return pending.state.hasUpgrade(ManipulatorUpgrade.SPEED) ? Math.max(1, interval / 2) : interval;
    }

    private static ResourceRequirements currentOperationRequirements(EntityPlayerMP player, EnumHand hand,
                                                                      ItemStack stack, ItemMatterManipulator manipulator,
                                                                      ManipulatorState state) {
        BuildingContext context = buildingContext(player, hand, stack, manipulator, state);
        ResourceAccess access = resources(player, stack, manipulator);
        List<PreparedBlockChange> changes = switch (state.placeMode()) {
            case GEOMETRY -> geometryRequirements(context, new GeometryBuildRequest(player, stack, hand,
                    manipulator.tier(), state, access.materialSources, access.powerSource));
            case COPYING -> copyRequirements(context, new CopyBuildRequest(player, stack, hand, manipulator.tier(),
                    state, access.materialSources, access.powerSource));
            case MOVING -> moveRequirements(context, new MoveBuildRequest(player, stack, hand, manipulator.tier(),
                    state, access.materialSources, access.powerSource));
            case EXCHANGING -> exchangeRequirements(context, new ExchangeBuildRequest(player, stack, hand,
                    manipulator.tier(), state, access.materialSources, access.powerSource));
            case CABLES -> cableRequirements(context, new CableBuildRequest(player, stack, hand, manipulator.tier(),
                    state, access.materialSources, access.powerSource));
        };
        List<ResourceRequirement> requirements = new ArrayList<>();
        for (PreparedBlockChange change : changes) {
            if (change.changesWorld()) requirements.addAll(change.requiredResources().entries());
        }
        return ResourceRequirements.of(requirements.toArray(ResourceRequirement[]::new));
    }

    private static List<PreparedBlockChange> geometryRequirements(BuildingContext context, GeometryBuildRequest request) {
        BoundGeometryPlan plan = GEOMETRY_SERVICE.createPlan(request);
        List<PreparedBlockChange> changes = new ArrayList<>(plan.operations().size());
        for (BoundGeometryOperation operation : plan.operations()) {
            changes.add(ADAPTERS.prepareApply(context, operation.operation().location().position(), operation.block()));
        }
        return changes;
    }

    private static List<PreparedBlockChange> copyRequirements(BuildingContext context, CopyBuildRequest request) {
        BoundCopyPlan plan = COPY_SERVICE.createPlan(request);
        List<PreparedBlockChange> changes = new ArrayList<>(plan.operations().size());
        for (BoundCopyOperation operation : plan.operations()) {
            changes.add(ADAPTERS.prepareApply(context, operation.target(), operation.captured()));
        }
        return changes;
    }

    private static List<PreparedBlockChange> moveRequirements(BuildingContext context, MoveBuildRequest request) {
        CopyPlan plan = MOVE_SERVICE.createPlan(request);
        List<PreparedBlockChange> changes = new ArrayList<>(plan.operations().size());
        for (applygray.mattermanipulator.planning.CopyPositionOperation operation : plan.operations()) {
            changes.add(ADAPTERS.prepareMove(context, operation.source(), operation.target()));
        }
        return changes;
    }

    private static List<PreparedBlockChange> exchangeRequirements(BuildingContext context, ExchangeBuildRequest request) {
        BoundExchangePlan plan = EXCHANGE_SERVICE.createPlan(request);
        List<PreparedBlockChange> changes = new ArrayList<>(plan.operations().size());
        for (BoundExchangeOperation operation : plan.operations()) {
            changes.add(ADAPTERS.prepareApply(context, operation.position(), operation.replacement()));
        }
        return changes;
    }

    private static List<PreparedBlockChange> cableRequirements(BuildingContext context, CableBuildRequest request) {
        BoundGeometryPlan plan = CABLE_SERVICE.createPlan(request);
        List<PreparedBlockChange> changes = new ArrayList<>(plan.operations().size());
        for (BoundGeometryOperation operation : plan.operations()) {
            changes.add(ADAPTERS.prepareApply(context, operation.operation().location().position(), operation.block()));
        }
        return changes;
    }

    private static BuildingContext buildingContext(EntityPlayerMP player, EnumHand hand, ItemStack stack,
                                                   ItemMatterManipulator manipulator, ManipulatorState state) {
        return new BuildingContext(player.world, player, stack, hand, state.removalMode(),
                state.hasUpgrade(ManipulatorUpgrade.POWER_EFFICIENCY),
                manipulator.tier().hasCapability(ManipulatorCapability.REMOVAL) || state.hasUpgrade(
                        ManipulatorUpgrade.MINING));
    }

    private static ResourceRequirements missingRequirements(ResourceRequirements requirements,
                                                            List<? extends MaterialSource> sources) {
        List<ResourceRequirement> missing = new ArrayList<>();
        for (ResourceRequirement requirement : requirements.entries()) {
            long remaining = requirement.amount();
            for (MaterialSource source : sources) {
                if (remaining == 0L) break;
                long supplied = source.extract(requirement.specification(), remaining, true);
                if (supplied < 0L || supplied > remaining) {
                    throw new IllegalStateException("Material source " + source.id() +
                            " returned an invalid simulated extraction");
                }
                remaining -= supplied;
            }
            if (remaining > 0L) missing.add(new ResourceRequirement(requirement.specification(), remaining));
        }
        return ResourceRequirements.of(missing.toArray(ResourceRequirement[]::new));
    }

    private static ResourceAccess resources(EntityPlayerMP player, ItemStack stack, ItemMatterManipulator manipulator) {
        CombinedInvWrapper inventory = new CombinedInvWrapper(new PlayerMainInvWrapper(player.inventory),
                new PlayerOffhandInvWrapper(player.inventory));
        List<MaterialSource> sources = new ArrayList<>();
        if (manipulator.hasCapability(stack, ManipulatorCapability.AE_NETWORK)) {
            sources.add(new Ae2WirelessMaterialSource(player));
        }
        sources.add(new ItemHandlerMaterialSource("player", inventory));
        ManipulatorState state = manipulator.state(stack);
        if (manipulator.hasCapability(stack, ManipulatorCapability.UPLINK) && state.uplinkAddress() != null) {
            sources.add(new UplinkMaterialSource(state.uplinkAddress()));
        }
        return new ResourceAccess(sources,
                new ElectricItemPowerSource("manipulator", stack, manipulator.tier(), player));
    }

    private static void reportCraftingRequestFailure(EntityPlayerMP player, UplinkCraftingRequestResult.Status status) {
        String translationKey = switch (status) {
            case EMPTY -> "applygray.matter_manipulator.uplink.crafting.no_requirements";
            case OFFLINE, AE_OFFLINE, NO_PLASMA -> "applygray.matter_manipulator.uplink.offline";
            case QUEUE_FULL -> "applygray.matter_manipulator.uplink.crafting.queue_full";
            case INVALID_REQUIREMENTS -> "applygray.matter_manipulator.uplink.crafting.invalid";
            case ACCEPTED -> throw new IllegalArgumentException("Accepted crafting requests do not fail");
        };
        player.sendStatusMessage(new TextComponentTranslation(translationKey), true);
    }

    private static ManipulatorState copyState(ManipulatorState state) {
        return ManipulatorState.readFromNbt(state.writeToNbt());
    }

    private static void reportStartFailure(EntityPlayerMP player, RuntimeException exception) {
        if (exception instanceof TransactionFailedException failed) {
            reportBatchFailure(player, failed.result);
            return;
        }
        if (exception instanceof BuildingException building) {
            ApplyGrayMod.LOGGER.warn("Matter Manipulator operation rejected for {} at {}: reason={}, message={}",
                    player.getName(), building.position(), building.reason(), building.getMessage());
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.rejected",
                    building.position().getX(), building.position().getY(), building.position().getZ(),
                    building.reason().name()), true);
            return;
        }
        if (exception instanceof InsufficientResourcesException) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_material"), true);
            return;
        }
        if (exception instanceof InsufficientOutputCapacityException) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_output"), true);
            return;
        }
        if (exception instanceof InsufficientPowerException) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_power"), true);
            return;
        }
        if (exception instanceof GeometryPlanException) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.invalid_selection"),
                    true);
            return;
        }

        ApplyGrayMod.LOGGER.warn("Matter Manipulator operation failed for {}", player.getName(), exception);
        player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.failed"), true);
    }

    private static void reportBatchFailure(EntityPlayerMP player, BuildTransaction.Result result) {
        if (result.failure() instanceof BuildingException building) {
            ApplyGrayMod.LOGGER.warn("Matter Manipulator batch rejected for {} at {}: reason={}, message={}",
                    player.getName(), building.position(), building.reason(), building.getMessage());
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.rejected",
                    building.position().getX(), building.position().getY(), building.position().getZ(),
                    building.reason().name()), true);
        } else if (result.state() == BuildTransaction.State.OUTPUT_FAILURE) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_output"), true);
        } else if (result.state() == BuildTransaction.State.ENERGY_FAILURE ||
                "manipulator".equals(result.failedSource())) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_power"), true);
        } else if (result.state() == BuildTransaction.State.RESOURCE_FAILURE || !result.failedSource().isEmpty()) {
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.no_material"), true);
        } else {
            ApplyGrayMod.LOGGER.warn("Matter Manipulator transaction did not commit for {}", player.getName(),
                    result.failure());
            player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.build.failed"), true);
        }
    }

    private enum BuildKind {
        GEOMETRY,
        COPY,
        MOVE,
        EXCHANGE,
        CABLE
    }

    private record ResourceAccess(List<MaterialSource> materialSources, ElectricItemPowerSource powerSource) {}

    private static final class PendingBuild {

        private final UUID playerId;
        private final EnumHand hand;
        private final ItemMatterManipulator manipulator;
        private final ManipulatorState state;
        private final BuildKind kind;
        private final Object plan;
        private final int operationCount;
        private int nextOperationIndex;
        private int ticksUntilNextBatch;
        private int lastWorldChanges;

        private PendingBuild(UUID playerId, EnumHand hand, ItemMatterManipulator manipulator, ManipulatorState state,
                             BuildKind kind, Object plan, int operationCount) {
            this.playerId = playerId;
            this.hand = hand;
            this.manipulator = manipulator;
            this.state = state;
            this.kind = kind;
            this.plan = plan;
            this.operationCount = operationCount;
        }
    }

    private static final class TransactionFailedException extends RuntimeException {

        private final BuildTransaction.Result result;

        private TransactionFailedException(BuildTransaction.Result result) {
            this.result = result;
        }
    }
}
