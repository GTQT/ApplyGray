package applygray.mattermanipulator.planning;

import java.util.List;
import java.util.Objects;

/** Immutable, server-validatable output of a geometry request. */
public final class GeometryPlan {

    private final GeometrySelection selection;
    private final List<ManipulatorOperation> operations;

    GeometryPlan(GeometrySelection selection, List<ManipulatorOperation> operations) {
        this.selection = Objects.requireNonNull(selection, "selection");
        this.operations = List.copyOf(operations);
    }

    public GeometrySelection selection() {
        return selection;
    }

    public List<ManipulatorOperation> operations() {
        return operations;
    }

    public int operationCount() {
        return operations.size();
    }

    public long count(VoxelRole role) {
        return operations.stream().filter(operation -> operation.role() == role).count();
    }
}
