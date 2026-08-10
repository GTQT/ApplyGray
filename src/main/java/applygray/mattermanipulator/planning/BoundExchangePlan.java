package applygray.mattermanipulator.planning;

import java.util.List;
import java.util.Objects;

/** Immutable, server-captured list of exchangeable blocks. */
public final class BoundExchangePlan {

    private final List<BoundExchangeOperation> operations;

    public BoundExchangePlan(List<BoundExchangeOperation> operations) {
        this.operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
    }

    public List<BoundExchangeOperation> operations() {
        return operations;
    }
}
