package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.CapturedBlock;

import net.minecraft.util.math.BlockPos;

/** A copy position pair with a captured, transformed portable block state. */
public record BoundCopyOperation(BlockPos source, BlockPos target, CapturedBlock captured) {

    public BoundCopyOperation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(captured, "captured");
    }

    public BlockSpec specification() {
        return captured.specification();
    }
}
