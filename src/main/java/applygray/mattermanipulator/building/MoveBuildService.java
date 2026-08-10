package applygray.mattermanipulator.building;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import applygray.mattermanipulator.planning.CopyPlan;
import applygray.mattermanipulator.planning.CopyPlanner;
import applygray.mattermanipulator.planning.CopyPositionOperation;
import applygray.mattermanipulator.planning.CopyTransform;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.util.math.BlockPos;

/**
 * Prevalidates and commits a move as one transaction so a failed destination never leaves part of the source behind.
 *
 * <p>The cap bounds the rollback journal kept on the server thread. Copy remains batched because it is not an atomic
 * source mutation.</p>
 */
public final class MoveBuildService {

    public static final int MAX_ATOMIC_MOVE_OPERATIONS = 16_384;

    private final BuildingAdapterRegistry adapters;

    public MoveBuildService(BuildingAdapterRegistry adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public CopyPlan createPlan(MoveBuildRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        CopyPlan plan = CopyPlanner.plan(request.state().selectionA(), request.state().selectionB(),
                request.state().selectionC(), CopyTransform.identity(), MAX_ATOMIC_MOVE_OPERATIONS);
        validateNonOverlapping(plan);
        validateRange(request, plan);

        BuildingContext context = context(request);
        for (CopyPositionOperation operation : plan.operations()) {
            adapters.prepareMove(context, operation.source(), operation.target());
        }
        return plan;
    }

    public MoveBuildResult execute(MoveBuildRequest request, CopyPlan plan) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        if (request.player().world.isRemote) throw new IllegalArgumentException("Moves must execute on the server");

        BuildingContext context = context(request);
        List<PreparedBlockChange> changes = new ArrayList<>(plan.operations().size());
        for (CopyPositionOperation operation : plan.operations()) {
            changes.add(adapters.prepareMove(context, operation.source(), operation.target()));
        }
        BuildTransaction transaction = BuildTransaction.prepare(changes, request.materialSources(), request.powerSource());
        return new MoveBuildResult(plan, transaction.execute());
    }

    private static BuildingContext context(MoveBuildRequest request) {
        return new BuildingContext(request.player().world, request.player(), request.manipulatorStack(), request.hand(),
                request.state().removalMode(), request.state().hasUpgrade(ManipulatorUpgrade.POWER_EFFICIENCY),
                request.tier().hasCapability(ManipulatorCapability.REMOVAL) || request.state().hasUpgrade(
                        ManipulatorUpgrade.MINING));
    }

    private static void validateRequest(MoveBuildRequest request) {
        if (request.player().world.isRemote) throw new IllegalArgumentException("Moves must execute on the server");
        if (!request.tier().hasCapability(ManipulatorCapability.MOVING)) {
            throw new IllegalArgumentException("The selected Matter Manipulator tier cannot move blocks");
        }
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        ManipulatorLocation c = request.state().selectionC();
        if (a == null || b == null || c == null) {
            throw new GeometryPlanException(GeometryPlanException.Reason.MISSING_SELECTION,
                    "Moving needs source A, source B, and a destination");
        }
    }

    private static void validateNonOverlapping(CopyPlan plan) {
        Set<BlockPos> sources = new HashSet<>();
        Set<BlockPos> targets = new HashSet<>();
        for (CopyPositionOperation operation : plan.operations()) {
            sources.add(operation.source());
            targets.add(operation.target());
        }
        sources.retainAll(targets);
        if (!sources.isEmpty()) {
            throw new GeometryPlanException(GeometryPlanException.Reason.OVERLAPPING_MOVE,
                    "A move source and destination cannot overlap");
        }
    }

    private static void validateRange(MoveBuildRequest request, CopyPlan plan) {
        int maximumRange = request.tier().maximumRange();
        if (maximumRange < 0) return;

        long maximumDistanceSquared = (long) maximumRange * maximumRange;
        BlockPos player = new BlockPos(request.player());
        for (CopyPositionOperation operation : plan.operations()) {
            if (player.distanceSq(operation.source()) > maximumDistanceSquared ||
                    player.distanceSq(operation.target()) > maximumDistanceSquared) {
                throw new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                        "The move operation exceeds the manipulator range");
            }
        }
    }
}
