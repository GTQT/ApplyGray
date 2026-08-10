package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

/** A geometry operation after its role has been deterministically bound to a concrete material. */
public record BoundGeometryOperation(ManipulatorOperation operation, BlockSpec block) {

    public BoundGeometryOperation {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(block, "block");
    }
}
