package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;

import net.minecraft.util.math.BlockPos;

/** One source-matched exchange target with its deterministic replacement material. */
public record BoundExchangeOperation(BlockPos position, BlockSpec replacement) {

    public BoundExchangeOperation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(replacement, "replacement");
    }
}
