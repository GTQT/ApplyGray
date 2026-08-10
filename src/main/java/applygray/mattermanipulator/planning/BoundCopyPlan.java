package applygray.mattermanipulator.planning;

import java.util.List;
import java.util.Objects;

/** Immutable server-captured copy plan. */
public final class BoundCopyPlan {

    private final CopyPlan positions;
    private final List<BoundCopyOperation> operations;

    public BoundCopyPlan(CopyPlan positions, List<BoundCopyOperation> operations) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.operations = List.copyOf(operations);
        if (this.operations.size() != positions.operations().size()) {
            throw new IllegalArgumentException("Every copy position needs exactly one captured block");
        }
    }

    public CopyPlan positions() {
        return positions;
    }

    public List<BoundCopyOperation> operations() {
        return operations;
    }
}
