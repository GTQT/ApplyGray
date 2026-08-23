package applygray.mattermanipulator.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import applygray.mattermanipulator.util.SourceCompatibleRandom;

import applygray.mattermanipulator.building.GeometryConfiguration;

/** Binds role-only geometry output to the tool's weighted material slots. */
public final class GeometryPlanBinder {

    private GeometryPlanBinder() {}

    public static BoundGeometryPlan bind(GeometryPlan plan, GeometryConfiguration configuration) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(configuration, "configuration");
        long seed = 31L * plan.selection().hashCode() + configuration.hashCode();
        return bind(plan, configuration, seed);
    }

    public static BoundGeometryPlan bind(GeometryPlan plan, GeometryConfiguration configuration, long seed) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(configuration, "configuration");
        SourceCompatibleRandom random = new SourceCompatibleRandom(seed);
        List<BoundGeometryOperation> operations = new ArrayList<>(plan.operationCount());
        for (ManipulatorOperation operation : plan.operations()) {
            operations.add(new BoundGeometryOperation(operation, configuration.select(operation.role(), random)));
        }
        return new BoundGeometryPlan(plan, operations);
    }
}
