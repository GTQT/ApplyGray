package applygray.mattermanipulator.building;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;

/**
 * Target-specific boundary for safely preparing a block change.
 *
 * <p>Adapters own the meaning of a block's state and configuration. They must reject unsupported blocks rather than
 * falling back to raw TileEntity NBT.</p>
 */
public interface BuildingAdapter {

    String id();

    boolean supports(BuildingContext context, BlockPos position, BlockSpec specification);

    PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification);

    /** Returns whether this adapter can safely inspect and reproduce the source position. */
    boolean supportsCapture(BuildingContext context, BlockPos position);

    /** Captures the portable state required for a later copy or move operation. */
    CapturedBlock capture(BuildingContext context, BlockPos position);

    /** Applies a target transform to the adapter-owned capture before it is placed at a copy destination. */
    default CapturedBlock transformCapture(CapturedBlock captured, Mirror mirror, Rotation rotation) {
        return captured.withSpecification(captured.specification().transformed(mirror, rotation));
    }

    /** Prepares a placement from an earlier adapter-owned capture. */
    default PreparedBlockChange prepareApplyCaptured(BuildingContext context, BlockPos position, CapturedBlock captured) {
        return prepareApply(context, position, captured.specification());
    }

    /** Prepares a safe removal that returns every represented output through the transaction layer. */
    PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position);

    /** Returns whether this adapter can relocate the source state into the target without loss. */
    boolean supportsMove(BuildingContext context, BlockPos source, BlockPos target);

    /** Prepares an atomic source/target move operation. */
    PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target);
}
