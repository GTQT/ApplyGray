package applygray.mattermanipulator.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import applygray.mattermanipulator.state.ManipulatorLocation;

import net.minecraft.util.math.BlockPos;

/** Deterministic position planner for target-native copy and move operations. */
public final class CopyPlanner {

    private CopyPlanner() {}

    public static CopyPlan plan(ManipulatorLocation sourceA, ManipulatorLocation sourceB, ManipulatorLocation destination,
                                CopyTransform transform, int maximumOperations) {
        Objects.requireNonNull(sourceA, "sourceA");
        Objects.requireNonNull(sourceB, "sourceB");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(transform, "transform");
        if (maximumOperations <= 0) throw new IllegalArgumentException("maximumOperations must be positive");
        if (sourceA.dimension() != sourceB.dimension() || sourceA.dimension() != destination.dimension()) {
            throw new GeometryPlanException(GeometryPlanException.Reason.CROSS_DIMENSION_SELECTION,
                    "A copy or move operation cannot span dimensions");
        }

        BlockPos anchor = sourceA.position();
        BlockPos other = sourceB.position();
        int minX = Math.min(anchor.getX(), other.getX());
        int minY = Math.min(anchor.getY(), other.getY());
        int minZ = Math.min(anchor.getZ(), other.getZ());
        int maxX = Math.max(anchor.getX(), other.getX());
        int maxY = Math.max(anchor.getY(), other.getY());
        int maxZ = Math.max(anchor.getZ(), other.getZ());
        long baseCount = checkedCount(maximumOperations, span(minX, maxX), span(minY, maxY), span(minZ, maxZ));
        checkedCount(maximumOperations, baseCount, transform.repeatX(), transform.repeatY(), transform.repeatZ());

        List<BlockPos> transformedOffsets = new ArrayList<>(Math.toIntExact(baseCount));
        int transformedMinX = Integer.MAX_VALUE;
        int transformedMinY = Integer.MAX_VALUE;
        int transformedMinZ = Integer.MAX_VALUE;
        int transformedMaxX = Integer.MIN_VALUE;
        int transformedMaxY = Integer.MIN_VALUE;
        int transformedMaxZ = Integer.MIN_VALUE;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos transformed = transform.apply(new BlockPos(x - anchor.getX(), y - anchor.getY(),
                            z - anchor.getZ()));
                    transformedOffsets.add(transformed);
                    transformedMinX = Math.min(transformedMinX, transformed.getX());
                    transformedMinY = Math.min(transformedMinY, transformed.getY());
                    transformedMinZ = Math.min(transformedMinZ, transformed.getZ());
                    transformedMaxX = Math.max(transformedMaxX, transformed.getX());
                    transformedMaxY = Math.max(transformedMaxY, transformed.getY());
                    transformedMaxZ = Math.max(transformedMaxZ, transformed.getZ());
                }
            }
        }

        int strideX = Math.toIntExact(span(transformedMinX, transformedMaxX));
        int strideY = Math.toIntExact(span(transformedMinY, transformedMaxY));
        int strideZ = Math.toIntExact(span(transformedMinZ, transformedMaxZ));
        List<CopyPositionOperation> operations = new ArrayList<>();
        Set<BlockPos> targets = new HashSet<>();
        int sourceIndex;
        for (int repeatY = 0; repeatY < transform.repeatY(); repeatY++) {
            for (int repeatX = 0; repeatX < transform.repeatX(); repeatX++) {
                for (int repeatZ = 0; repeatZ < transform.repeatZ(); repeatZ++) {
                    sourceIndex = 0;
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                BlockPos transformed = transformedOffsets.get(sourceIndex++);
                                BlockPos target = destination.position().add(transformed.getX() + repeatX * strideX,
                                        transformed.getY() + repeatY * strideY,
                                        transformed.getZ() + repeatZ * strideZ);
                                if (!targets.add(target)) {
                                    throw new GeometryPlanException(GeometryPlanException.Reason.INVALID_COPY_TRANSFORM,
                                            "The copy transform writes one target position more than once");
                                }
                                operations.add(new CopyPositionOperation(new BlockPos(x, y, z), target));
                            }
                        }
                    }
                }
            }
        }
        return new CopyPlan(sourceA, sourceB, destination, transform, operations);
    }

    private static long checkedCount(int maximumOperations, long... factors) {
        long count = 1L;
        for (long factor : factors) {
            if (factor <= 0 || factor > maximumOperations || count > maximumOperations / factor) {
                throw new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                        "The copy or move operation exceeds its maximum of " + maximumOperations + " blocks");
            }
            count *= factor;
        }
        return count;
    }

    private static long span(int minimum, int maximum) {
        return (long) maximum - minimum + 1L;
    }
}
