package applygray.mattermanipulator.building;

import net.minecraft.util.math.BlockPos;

import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;

/** A fully validated one-block change that can be applied or rolled back by a build transaction. */
public interface PreparedBlockChange {

    BlockPos position();

    /** The exact material consumed only when this change is applied. */
    BlockSpec materialCost();

    /** Every exact input material required by this change. */
    default ResourceRequirements requiredResources() {
        BlockSpec material = materialCost();
        return material.isAir() ? ResourceRequirements.empty()
                : ResourceRequirements.of(new ResourceRequirement(material, 1L));
    }

    /** Items recovered from the replaced or removed block, delivered only after capacity has been reserved. */
    default ResourceRequirements producedResources() {
        return ResourceRequirements.empty();
    }

    /** The source-equivalent EU cost for this block, before the transaction mutates the world. */
    long energyCost();

    boolean changesWorld();

    void apply();

    void rollback();
}
