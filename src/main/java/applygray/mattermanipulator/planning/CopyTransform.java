package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorMirror;
import applygray.mattermanipulator.state.ManipulatorRotation;

import net.minecraft.util.math.BlockPos;

/** Immutable placement transform for a captured copy region. */
public record CopyTransform(ManipulatorRotation rotation, ManipulatorMirror mirror, int repeatX, int repeatY,
                            int repeatZ) {

    public CopyTransform {
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(mirror, "mirror");
        validateRepeat(repeatX);
        validateRepeat(repeatY);
        validateRepeat(repeatZ);
    }

    public static CopyTransform identity() {
        return new CopyTransform(ManipulatorRotation.NONE, ManipulatorMirror.NONE, 1, 1, 1);
    }

    /** Applies the configured mirror first, then the vertical-axis rotation. */
    public BlockPos apply(BlockPos localOffset) {
        Objects.requireNonNull(localOffset, "localOffset");
        int x = localOffset.getX();
        int z = localOffset.getZ();
        switch (mirror) {
            case NONE -> {}
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
        }
        return switch (rotation) {
            case NONE -> new BlockPos(x, localOffset.getY(), z);
            case CLOCKWISE_90 -> new BlockPos(-z, localOffset.getY(), x);
            case CLOCKWISE_180 -> new BlockPos(-x, localOffset.getY(), -z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, localOffset.getY(), -x);
        };
    }

    private static void validateRepeat(int repeat) {
        if (repeat < 1 || repeat > 64) throw new IllegalArgumentException("copy repeats must be between 1 and 64");
    }
}
