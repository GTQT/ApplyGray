package applygray.mattermanipulator.inventory;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

/** A positive exact-material requirement for one operation batch. */
public record ResourceRequirement(BlockSpec specification, long amount) {

    public ResourceRequirement {
        Objects.requireNonNull(specification, "specification");
        if (specification.isAir()) throw new IllegalArgumentException("air does not require a resource reservation");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    }
}
