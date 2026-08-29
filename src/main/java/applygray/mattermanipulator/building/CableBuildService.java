package applygray.mattermanipulator.building;

import java.util.Objects;

import applygray.mattermanipulator.planning.BoundGeometryPlan;
import applygray.mattermanipulator.planning.CablePathPlanner;
import applygray.mattermanipulator.planning.GeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlanBinder;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;

/** Builds one target-native, axis-aligned cable path using the normal safe placement transaction. */
public final class CableBuildService {

    private final GeometryBuildService geometry;

    public CableBuildService(BuildingAdapterRegistry adapters) {
        geometry = new GeometryBuildService(Objects.requireNonNull(adapters, "adapters"));
    }

    public BoundGeometryPlan createPlan(CableBuildRequest request) {
        validateRequest(request);
        ManipulatorLocation a = request.state().selectionA();
        ManipulatorLocation b = request.state().selectionB();
        GeometryPlan plan = CablePathPlanner.plan(a, b);
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
                    "The cable path must be in the player's current dimension");
        }
    }

}
