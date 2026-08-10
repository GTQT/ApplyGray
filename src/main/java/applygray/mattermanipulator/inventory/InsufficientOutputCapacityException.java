package applygray.mattermanipulator.inventory;

import applygray.mattermanipulator.building.BlockSpec;

/** Raised before a destructive operation when its recovered items have no safe destination. */
public final class InsufficientOutputCapacityException extends RuntimeException {

    private final BlockSpec specification;
    private final long missingAmount;

    public InsufficientOutputCapacityException(BlockSpec specification, long missingAmount) {
        super("No output capacity for " + missingAmount + "x " + specification.sortKey());
        this.specification = specification;
        this.missingAmount = missingAmount;
    }

    public BlockSpec specification() {
        return specification;
    }

    public long missingAmount() {
        return missingAmount;
    }
}
