package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorTransform;

import net.minecraft.util.math.BlockPos;

/** Computes the signed array span used by the source Mark action. */
public final class CopyArraySpan {

    private CopyArraySpan() {}

    public static BlockPos calculate(ManipulatorLocation sourceA, ManipulatorLocation sourceB,
                                     ManipulatorLocation destination, BlockPos lookingAt,
                                     ManipulatorTransform transform) {
        Objects.requireNonNull(sourceA, "sourceA");
        Objects.requireNonNull(sourceB, "sourceB");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(lookingAt, "lookingAt");
        Objects.requireNonNull(transform, "transform");
        if (sourceA.dimension() != sourceB.dimension() || sourceA.dimension() != destination.dimension()) {
            return BlockPos.ORIGIN.add(1, 1, 1);
        }

        BlockPos arrayOffset = lookingAt.subtract(destination.position());
        BlockPos localOffset = transform.inverseApply(arrayOffset);
        BlockPos delta = sourceB.position().subtract(sourceA.position());
        return new BlockPos(span(localOffset.getX(), delta.getX()), span(localOffset.getY(), delta.getY()),
                span(localOffset.getZ(), delta.getZ()));
    }

    private static int span(int offset, int delta) {
        if (delta == 0) return clamp(offset == 0 ? 1 : offset);
        int denominator = delta + (delta < 0 ? -1 : 1);
        return clamp(Math.floorDiv(offset, denominator));
    }

    private static int clamp(int value) {
        if (value == 0) return 1;
        return Math.max(-64, Math.min(64, value));
    }
}
