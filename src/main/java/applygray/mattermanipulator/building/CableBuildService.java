package applygray.mattermanipulator.building;

import java.util.Objects;

import applygray.mattermanipulator.planning.BoundGeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlanBinder;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.planning.GeometryPlanner;
import applygray.mattermanipulator.planning.GeometrySelection;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;

import net.minecraft.util.math.BlockPos;

/** Builds one target-native, cardinal cable line using the normal safe placement transaction. */
public final class CableBuildService {

    private final GeometryBuildService geometry;

    public CableBuildService(BuildingAdapterRegistry adapters) {
        geometry = new GeometryBuildService(Objects.requireNonNull(adapters, "adapters"));
    }

    public BoundGeometryPlan createPlan(CableBuildRequest request) {
        validateRequest(request);
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        BlockPos pinnedTarget = GeometryPlanner.pinToAxes(a.position(), b.position());
        GeometryPlan plan = GeometryPlanner.plan(new GeometrySelection(
                applygray.mattermanipulator.state.ManipulatorShape.LINE, a,
                new ManipulatorLocation(a.dimension(), pinnedTarget), null));
        validateRange(request, plan);

        GeometryConfiguration configuration = new GeometryConfiguration();
        configuration.edges().setSingle(request.state().cableMaterial());
        return GeometryPlanBinder.bind(plan, configuration);
    }

    public GeometryBuildResult executeNextBatch(CableBuildRequest request, BoundGeometryPlan plan, int startIndex) {
        GeometryBuildRequest geometryRequest = new GeometryBuildRequest(request.player(), request.manipulatorStack(),
                request.hand(), request.tier(), request.state(), request.materialSources(), request.powerSource());
        return geometry.executeNextBatch(geometryRequest, plan, startIndex);
    }

    private static void validateRequest(CableBuildRequest request) {
        if (request.player().world.isRemote) throw new IllegalArgumentException("Cable builds must execute on the server");
        if (!request.tier().hasCapability(ManipulatorCapability.CABLES)) {
            throw new IllegalArgumentException("The selected Matter Manipulator tier cannot place cables");
        }
        if (request.state().cableMaterial().isAir()) {
            throw new IllegalArgumentException("No cable material is configured; select a cable before building");
        }
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        if (a == null || b == null) {
            throw new GeometryPlanException(GeometryPlanException.Reason.MISSING_SELECTION,
                    "Cable placement needs two endpoints");
        }
        if (a.dimension() != b.dimension() || a.dimension() != request.player().world.provider.getDimension()) {
            throw new GeometryPlanException(GeometryPlanException.Reason.CROSS_DIMENSION_SELECTION,
                    "The cable line must be in the player's current dimension");
        }
    }

    private static void validateRange(CableBuildRequest request, GeometryPlan plan) {
        int maximumRange = request.tier().maximumRange();
        if (maximumRange < 0) return;
        long maximumDistanceSquared = (long) maximumRange * maximumRange;
        BlockPos player = new BlockPos(request.player());
        boolean outside = plan.operations().stream()
                .anyMatch(operation -> player.distanceSq(operation.location().position()) > maximumDistanceSquared);
        if (outside) {
            throw new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                    "The cable operation exceeds the manipulator range");
        }
    }
}
