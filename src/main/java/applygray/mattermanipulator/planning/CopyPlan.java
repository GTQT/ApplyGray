package applygray.mattermanipulator.planning;

import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorLocation;

/** Immutable source/destination geometry for copy or move operations. */
public final class CopyPlan {

    private final ManipulatorLocation sourceA;
    private final ManipulatorLocation sourceB;
    private final ManipulatorLocation destination;
    private final CopyTransform transform;
    private final List<CopyPositionOperation> operations;

    CopyPlan(ManipulatorLocation sourceA, ManipulatorLocation sourceB, ManipulatorLocation destination,
             CopyTransform transform, List<CopyPositionOperation> operations) {
        this.sourceA = Objects.requireNonNull(sourceA, "sourceA");
        this.sourceB = Objects.requireNonNull(sourceB, "sourceB");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.transform = Objects.requireNonNull(transform, "transform");
        this.operations = List.copyOf(operations);
    }

    public ManipulatorLocation sourceA() {
        return sourceA;
    }

    public ManipulatorLocation sourceB() {
        return sourceB;
    }

    public ManipulatorLocation destination() {
        return destination;
    }

    public CopyTransform transform() {
        return transform;
    }

    public List<CopyPositionOperation> operations() {
        return operations;
    }
}
