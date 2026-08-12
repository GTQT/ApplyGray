package applygray.mattermanipulator.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.planning.BoundGeometryOperation;
import applygray.mattermanipulator.planning.BoundGeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlanBinder;
import applygray.mattermanipulator.planning.GeometryPlanner;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.util.math.BlockPos;

/**
 * Converts persistent item state into a bounded, server-side build transaction.
 *
 * <p>The caller owns queue persistence and executes one batch per scheduled interval. Re-planning for every batch is
 * deterministic, so a task can persist only its operation index rather than a mutable block list.</p>
 */
public final class GeometryBuildService {

    private final BuildingAdapterRegistry adapters;

    public GeometryBuildService(BuildingAdapterRegistry adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public GeometryBuildResult executeNextBatch(GeometryBuildRequest request, int startIndex) {
        Objects.requireNonNull(request, "request");
        BoundGeometryPlan plan = createPlan(request);
        return executeNextBatch(request, plan, startIndex);
    }

    /** Creates a deterministic plan once, before a queued build starts. */
    public BoundGeometryPlan createPlan(GeometryBuildRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        GeometryPlan geometry = GeometryPlanner.plan(request.state().geometrySelection());
        BoundGeometryPlan plan = GeometryPlanBinder.bind(geometry, request.state().geometryConfiguration());
        validateRange(request, plan);
        return plan;
    }

    /** Executes one tier-bounded batch from a previously validated immutable plan. */
    public GeometryBuildResult executeNextBatch(GeometryBuildRequest request, BoundGeometryPlan plan, int startIndex) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        if (request.player().world.isRemote) {
            throw new IllegalArgumentException("Geometry builds must execute on the server");
        }
        if (startIndex < 0 || startIndex > plan.operations().size()) {
            throw new IllegalArgumentException("startIndex is outside the geometry plan");
        }

        int batchEnd = Math.min(plan.operations().size(), startIndex + request.tier().blocksPerBatch());
        BuildingContext context = new BuildingContext(request.player().world, request.player(), request.manipulatorStack(),
                request.hand(), request.state().removalMode(),
                request.state().hasUpgrade(ManipulatorUpgrade.POWER_EFFICIENCY),
                request.tier().hasCapability(ManipulatorCapability.REMOVAL) ||
                        request.state().hasUpgrade(ManipulatorUpgrade.MINING));
        List<PreparedBlockChange> changes = new ArrayList<>(batchEnd - startIndex);
        for (int index = startIndex; index < batchEnd; index++) {
            BoundGeometryOperation operation = plan.operations().get(index);
            changes.add(adapters.prepareApply(context, operation.operation().location().position(), operation.block()));
        }

        BuildTransaction.PreparedBatch batch = BuildTransaction.prepareLargestPrefix(changes, request.materialSources(),
                request.powerSource());
        int nextOperationIndex = startIndex + batch.changeCount();
        return new GeometryBuildResult(plan, startIndex, nextOperationIndex, batch.transaction().execute());
    }

    private static void validateRequest(GeometryBuildRequest request) {
        if (request.player().world.isRemote) {
            throw new IllegalArgumentException("Geometry builds must execute on the server");
        }
        if (!request.tier().hasCapability(ManipulatorCapability.GEOMETRY)) {
            throw new IllegalArgumentException("The selected Matter Manipulator tier cannot build geometry");
        }
        ManipulatorLocation anchor = request.state().selectionA();
        if (anchor == null || anchor.dimension() != request.player().world.provider.getDimension()) {
            throw new IllegalArgumentException("The geometry selection is not in the player's current dimension");
        }
    }

    private static void validateRange(GeometryBuildRequest request, BoundGeometryPlan plan) {
        int maximumRange = request.tier().maximumRange();
        if (maximumRange < 0) return;

        long maximumDistanceSquared = (long) maximumRange * maximumRange;
        for (BoundGeometryOperation operation : plan.operations()) {
            BlockPos position = operation.operation().location().position();
            double distanceSquared = position.distanceSq(request.player().posX, request.player().posY,
                    request.player().posZ);
            if (distanceSquared > maximumDistanceSquared) {
                throw new IllegalArgumentException("A geometry operation is outside the manipulator's range");
            }
        }
    }
}
