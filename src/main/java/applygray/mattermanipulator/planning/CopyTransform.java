package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorTransform;

import net.minecraft.util.math.BlockPos;

/** Immutable placement transform for a captured copy region. */
public record CopyTransform(ManipulatorTransform transform, int repeatX, int repeatY,
                            int repeatZ) {

    public CopyTransform {
        Objects.requireNonNull(transform, "transform");
        validateRepeat(repeatX);
        validateRepeat(repeatY);
        validateRepeat(repeatZ);
    }

    public static CopyTransform identity() {
        return new CopyTransform(ManipulatorTransform.identity(), 1, 1, 1);
    }

    /** Applies the configured three-dimensional orthogonal transform. */
    public BlockPos apply(BlockPos localOffset) {
        Objects.requireNonNull(localOffset, "localOffset");
        return transform.apply(localOffset);
    }

    private static void validateRepeat(int repeat) {
        if (repeat == 0 || repeat < -64 || repeat > 64) throw new IllegalArgumentException("copy spans must be between -64 and 64, excluding zero");
    }
}
