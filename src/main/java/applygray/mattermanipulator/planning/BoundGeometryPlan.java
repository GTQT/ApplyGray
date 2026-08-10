package applygray.mattermanipulator.planning;

import java.util.List;
import java.util.Objects;

/** Immutable material-bound geometry output for a future resource transaction. */
public final class BoundGeometryPlan {

    private final GeometryPlan geometry;
    private final List<BoundGeometryOperation> operations;

    BoundGeometryPlan(GeometryPlan geometry, List<BoundGeometryOperation> operations) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.operations = List.copyOf(operations);
    }

    public GeometryPlan geometry() {
        return geometry;
    }

    public List<BoundGeometryOperation> operations() {
        return operations;
    }
}
