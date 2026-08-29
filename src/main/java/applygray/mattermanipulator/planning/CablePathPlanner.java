package applygray.mattermanipulator.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorShape;

import net.minecraft.util.math.BlockPos;

/** Plans a deterministic, axis-aligned Manhattan path which includes both selected endpoints. */
public final class CablePathPlanner {

    private CablePathPlanner() {}

    public static GeometryPlan plan(ManipulatorLocation start, ManipulatorLocation end) {
        return plan(start, end, GeometryPlanner.DEFAULT_MAX_OPERATION_COUNT);
    }

    public static GeometryPlan plan(ManipulatorLocation start, ManipulatorLocation end, int maximumOperations) {
        validate(start, end, maximumOperations);
        long operationCount = operationCount(start.position(), end.position());
        if (operationCount > maximumOperations) throw limitExceeded(maximumOperations);

        List<ManipulatorOperation> operations = new ArrayList<>((int) operationCount);
        int dimension = start.dimension();
        BlockPos target = end.position();
        BlockPos current = start.position();
        operations.add(operation(dimension, current));

        for (Axis axis : orderedAxes(current, target)) {
            while (axis.coordinate(current) != axis.coordinate(target)) {
                current = axis.stepTowards(current, target);
                operations.add(operation(dimension, current));
            }
        }

        GeometrySelection selection = new GeometrySelection(ManipulatorShape.LINE, start, end, null);
        return new GeometryPlan(selection, operations);
    }

    /** Returns A, each non-empty turn, and B without enumerating every cable position. */
    public static List<BlockPos> waypoints(BlockPos start, BlockPos end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        List<BlockPos> points = new ArrayList<>(4);
        points.add(start);
        BlockPos current = start;
        for (Axis axis : orderedAxes(start, end)) {
            BlockPos next = axis.withCoordinate(current, axis.coordinate(end));
            if (!next.equals(current)) {
                points.add(next);
                current = next;
            }
        }
        return List.copyOf(points);
    }

    public static long operationCount(BlockPos start, BlockPos end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        return Math.abs((long) end.getX() - start.getX()) +
                Math.abs((long) end.getY() - start.getY()) +
                Math.abs((long) end.getZ() - start.getZ()) + 1L;
    }

    private static List<Axis> orderedAxes(BlockPos start, BlockPos end) {
        return java.util.Arrays.stream(Axis.values())
                .sorted(Comparator.comparingLong((Axis axis) -> axis.distance(start, end)).reversed()
                        .thenComparingInt(Enum::ordinal))
                .toList();
    }

    private static ManipulatorOperation operation(int dimension, BlockPos position) {
        return new ManipulatorOperation(ManipulatorOperation.Type.PLACE,
                new ManipulatorLocation(dimension, position), VoxelRole.EDGE, 0, 0);
    }

    private static void validate(ManipulatorLocation start, ManipulatorLocation end, int maximumOperations) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (maximumOperations <= 0) throw new IllegalArgumentException("maximumOperations must be positive");
        if (start.dimension() != end.dimension()) {
            throw new GeometryPlanException(GeometryPlanException.Reason.CROSS_DIMENSION_SELECTION,
                    "A cable path cannot span dimensions");
        }
    }

    private static GeometryPlanException limitExceeded(int maximumOperations) {
        return new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                "The cable path exceeds its maximum of " + maximumOperations + " blocks");
    }

    private enum Axis {
        X {
            @Override int coordinate(BlockPos point) { return point.getX(); }
            @Override BlockPos withCoordinate(BlockPos point, int value) {
                return new BlockPos(value, point.getY(), point.getZ());
            }
        },
        Y {
            @Override int coordinate(BlockPos point) { return point.getY(); }
            @Override BlockPos withCoordinate(BlockPos point, int value) {
                return new BlockPos(point.getX(), value, point.getZ());
            }
        },
        Z {
            @Override int coordinate(BlockPos point) { return point.getZ(); }
            @Override BlockPos withCoordinate(BlockPos point, int value) {
                return new BlockPos(point.getX(), point.getY(), value);
            }
        };

        abstract int coordinate(BlockPos point);

        abstract BlockPos withCoordinate(BlockPos point, int value);

        long distance(BlockPos start, BlockPos end) {
            return Math.abs((long) coordinate(end) - coordinate(start));
        }

        BlockPos stepTowards(BlockPos current, BlockPos target) {
            int value = coordinate(current) + Integer.compare(coordinate(target), coordinate(current));
            return withCoordinate(current, value);
        }
    }
}
