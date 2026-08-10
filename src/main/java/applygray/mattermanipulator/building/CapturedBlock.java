package applygray.mattermanipulator.building;

import java.util.Objects;

import net.minecraft.util.math.BlockPos;

/**
 * Immutable, adapter-owned capture of one source block.
 *
 * <p>The block specification is deliberately item/state-only. A specialised adapter can attach a target-native
 * configuration payload, but no raw TileEntity NBT is accepted by this common contract.</p>
 */
public record CapturedBlock(BlockPos source, BlockSpec specification, String adapterId, CapturedBlockData data) {

    public CapturedBlock {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(data, "data");
    }

    public CapturedBlock(BlockPos source, BlockSpec specification) {
        this(source, specification, "", EmptyCapturedBlockData.INSTANCE);
    }

    public CapturedBlock withAdapterId(String adapterId) {
        return new CapturedBlock(source, specification, adapterId, data);
    }

    public CapturedBlock withSpecification(BlockSpec specification) {
        return new CapturedBlock(source, specification, adapterId, data);
    }
}
