package applygray.mattermanipulator.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.planning.BoundCopyOperation;
import applygray.mattermanipulator.planning.BoundCopyPlan;
import applygray.mattermanipulator.planning.CopyPlan;
import applygray.mattermanipulator.planning.CopyPlanner;
import applygray.mattermanipulator.planning.CopyPositionOperation;
import applygray.mattermanipulator.planning.CopyTransform;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.planning.GeometryPlanner;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;

import net.minecraft.util.math.BlockPos;

/** Captures a safe source region once, then copies it through bounded server-side transactions. */
public final class CopyBuildService {

    private final BuildingAdapterRegistry adapters;

    public CopyBuildService(BuildingAdapterRegistry adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public BoundCopyPlan createPlan(CopyBuildRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        CopyTransform transform = new CopyTransform(request.state().copyRotation(), request.state().copyMirror(),
                request.state().copyRepeatX(), request.state().copyRepeatY(), request.state().copyRepeatZ());
        CopyPlan positions = CopyPlanner.plan(request.state().selectionA(), request.state().selectionB(),
                request.state().selectionC(), transform, GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT);
        validateRange(request, positions);

        BuildingContext context = context(request);
        List<BoundCopyOperation> operations = new ArrayList<>(positions.operations().size());
        for (CopyPositionOperation operation : positions.operations()) {
            CapturedBlock captured = adapters.capture(context, operation.source());
            CapturedBlock transformed = adapters.transformCapture(captured, request.state().copyMirror().minecraftMirror(),
                    request.state().copyRotation().minecraftRotation());
            operations.add(new BoundCopyOperation(operation.source(), operation.target(), transformed));
        }
        return new BoundCopyPlan(positions, operations);
    }

    public CopyBuildResult executeNextBatch(CopyBuildRequest request, BoundCopyPlan plan, int startIndex) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        if (request.player().world.isRemote) throw new IllegalArgumentException("Copy builds must execute on the server");
        if (startIndex < 0 || startIndex > plan.operations().size()) {
            throw new IllegalArgumentException("startIndex is outside the copy plan");
        }

        int nextOperationIndex = Math.min(plan.operations().size(), startIndex + request.tier().blocksPerBatch());
        BuildingContext context = context(request);
        List<PreparedBlockChange> changes = new ArrayList<>(nextOperationIndex - startIndex);
        for (int index = startIndex; index < nextOperationIndex; index++) {
            BoundCopyOperation operation = plan.operations().get(index);
            changes.add(adapters.prepareApply(context, operation.target(), operation.captured()));
        }
        BuildTransaction transaction = BuildTransaction.prepare(changes, request.materialSources(), request.powerSource());
        return new CopyBuildResult(plan, startIndex, nextOperationIndex, transaction.execute());
    }

    private static BuildingContext context(CopyBuildRequest request) {
        return new BuildingContext(request.player().world, request.player(), request.manipulatorStack(), request.hand(),
                request.state().removalMode(), request.state().hasUpgrade(
                        applygray.mattermanipulator.state.ManipulatorUpgrade.POWER_EFFICIENCY),
                request.tier().hasCapability(ManipulatorCapability.REMOVAL) || request.state().hasUpgrade(
                        applygray.mattermanipulator.state.ManipulatorUpgrade.MINING),
                request.tier().hasCapability(ManipulatorCapability.SMART_COPY) && request.state().smartCopy());
    }

    private static void validateRequest(CopyBuildRequest request) {
        if (request.player().world.isRemote) throw new IllegalArgumentException("Copy builds must execute on the server");
        if (!request.tier().hasCapability(ManipulatorCapability.COPYING)) {
            throw new IllegalArgumentException("The selected Matter Manipulator tier cannot copy blocks");
        }
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        ManipulatorLocation c = request.state().selectionC();
        if (a == null || b == null || c == null) {
            throw new GeometryPlanException(GeometryPlanException.Reason.MISSING_SELECTION,
                    "Copying needs source A, source B, and a destination");
        }
    }

    private static void validateRange(CopyBuildRequest request, CopyPlan plan) {
        int maximumRange = request.tier().maximumRange();
        if (maximumRange < 0) return;

        long maximumDistanceSquared = (long) maximumRange * maximumRange;
        BlockPos player = new BlockPos(request.player());
        for (CopyPositionOperation operation : plan.operations()) {
            if (player.distanceSq(operation.source()) > maximumDistanceSquared ||
                    player.distanceSq(operation.target()) > maximumDistanceSquared) {
                throw new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                        "The copy operation exceeds the manipulator range");
            }
        }
    }
}
