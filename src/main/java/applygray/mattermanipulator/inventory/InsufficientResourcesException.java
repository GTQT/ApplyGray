package applygray.mattermanipulator.inventory;

import applygray.mattermanipulator.building.BlockSpec;

/** A failed reservation changes no source and identifies the missing exact material. */
public final class InsufficientResourcesException extends IllegalStateException {

    private final BlockSpec specification;
    private final long missingAmount;
    private final FluidRequirement fluidRequirement;

    InsufficientResourcesException(BlockSpec specification, long missingAmount) {
        super("Missing " + missingAmount + " of " + specification);
        this.specification = specification;
        this.missingAmount = missingAmount;
        this.fluidRequirement = null;
    }

    InsufficientResourcesException(FluidRequirement requirement, long missingAmount) {
        super("Missing " + missingAmount + "mB of " + requirement.fluidName());
        this.specification = null;
        this.missingAmount = missingAmount;
        this.fluidRequirement = requirement;
    }

    public BlockSpec specification() {
        return specification;
    }

    public long missingAmount() {
        return missingAmount;
    }

    public FluidRequirement fluidRequirement() {
        return fluidRequirement;
    }
}
