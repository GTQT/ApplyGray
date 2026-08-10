package applygray.mattermanipulator.planning;

import java.util.Objects;

import net.minecraft.util.math.BlockPos;

/** One source-to-target position pair before adapter capture binds its portable block state. */
public record CopyPositionOperation(BlockPos source, BlockPos target) {

    public CopyPositionOperation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
