package applygray.mattermanipulator.building;

import applygray.mattermanipulator.state.ManipulatorTransform;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;

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

    /**
     * Returns whether this adapter can account for whatever currently occupies the target position.
     *
     * <p>An adapter that understands the existing contents absorbs them into its own placement — an AE2 cable bus
     * gains a part instead of being cleared, and a GregTech machine surrenders its contents through the same
     * transaction. Otherwise the registry has the adapter that owns the target remove it first, so a cable or a
     * machine can replace a foreign tile-backed block exactly like an ordinary one can.</p>
     */
    default boolean absorbsTargetContents(BuildingContext context, BlockPos position) {
        return !hasTileEntity(context, position);
    }

    /**
     * Prepares a placement whose target the surrounding transaction removes first.
     *
     * <p>The position is guaranteed to be air by the time the returned change is applied, so the implementation must
     * neither read the current contents as its own outputs nor demand removal permission a second time.</p>
     */
    PreparedBlockChange prepareApplyAfterTargetRemoval(BuildingContext context, BlockPos position,
                                                       BlockSpec specification);

    /** Reports whether a position holds tile-backed state that only a dedicated adapter can account for. */
    static boolean hasTileEntity(BuildingContext context, BlockPos position) {
        IBlockState state = context.world().getBlockState(position);
        return context.world().getTileEntity(position) != null || state.getBlock().hasTileEntity(state);
    }

    /** Returns whether this adapter can safely inspect and reproduce the source position. */
    boolean supportsCapture(BuildingContext context, BlockPos position);

    /** Captures the portable state required for a later copy or move operation. */
    CapturedBlock capture(BuildingContext context, BlockPos position);

    /** Applies a target transform to the adapter-owned capture before it is placed at a copy destination. */
    default CapturedBlock transformCapture(CapturedBlock captured, ManipulatorTransform transform) {
        return captured.withSpecification(captured.specification().transformed(transform));
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

    /** Prepares a move whose target will be removed by the surrounding transaction before this change is applied. */
    default PreparedBlockChange prepareMoveAfterTargetRemoval(BuildingContext context, BlockPos source,
                                                              BlockPos target) {
        return prepareMove(context, source, target);
    }
}
