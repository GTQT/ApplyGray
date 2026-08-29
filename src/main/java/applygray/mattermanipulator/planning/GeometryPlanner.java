package applygray.mattermanipulator.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorShape;

import net.minecraft.util.math.BlockPos;

/**
 * Deterministic target-native geometry planner.
 *
 * <p>The voxel shape and role classification preserve Matter Manipulator's established behavior, while the output
 * is an immutable 1.12.2 plan rather than mutable 1.7.10 pending blocks.</p>
 */
public final class GeometryPlanner {

    public static final int DEFAULT_MAX_OPERATION_COUNT = 1_000_000;

    private GeometryPlanner() {}

    public static GeometryPlan plan(GeometrySelection selection) {
        return plan(selection, DEFAULT_MAX_OPERATION_COUNT);
    }

    public static GeometryPlan plan(GeometrySelection selection, int maximumOperations) {
        if (maximumOperations <= 0) {
            throw new IllegalArgumentException("maximumOperations must be positive");
        }
        validateSelection(selection);

        PlanBuilder builder = new PlanBuilder(selection.a().dimension(), maximumOperations);
        BlockPos a = selection.a().position();
        BlockPos b = selection.b().position();

        switch (selection.shape()) {
            case LINE -> planLine(builder, a, b);
            case CUBE -> planCube(builder, a, b);
            case SPHERE -> planSphere(builder, a, b);
            case CYLINDER -> planCylinder(builder, a, b, selection.c().position());
        }

        return new GeometryPlan(selection, builder.operations);
    }

    public static BlockPos pinToPlanes(BlockPos origin, BlockPos point) {
        int deltaX = Math.abs(point.getX() - origin.getX());
        int deltaY = Math.abs(point.getY() - origin.getY());
        int deltaZ = Math.abs(point.getZ() - origin.getZ());
        int shortest = Math.min(deltaX, Math.min(deltaY, deltaZ));

        if (shortest == deltaX) return new BlockPos(origin.getX(), point.getY(), point.getZ());
        if (shortest == deltaY) return new BlockPos(point.getX(), origin.getY(), point.getZ());
        return new BlockPos(point.getX(), point.getY(), origin.getZ());
    }

    public static BlockPos pinToLine(BlockPos origin, BlockPos planePoint, BlockPos point) {
        return switch (smallestAbsoluteComponent(
                planePoint.getX() - origin.getX(),
                planePoint.getY() - origin.getY(),
                planePoint.getZ() - origin.getZ())) {
            case 0 -> new BlockPos(point.getX(), origin.getY(), origin.getZ());
            case 1 -> new BlockPos(origin.getX(), point.getY(), origin.getZ());
            case 2 -> new BlockPos(origin.getX(), origin.getY(), point.getZ());
            default -> throw new AssertionError();
        };
    }

    private static void validateSelection(GeometrySelection selection) {
        if (selection == null || selection.a() == null || selection.b() == null ||
                selection.shape().requiresThirdPoint() && selection.c() == null) {
            throw new GeometryPlanException(GeometryPlanException.Reason.MISSING_SELECTION,
                    "The selected shape does not have all required coordinates");
        }
        if (selection.a().dimension() != selection.b().dimension() ||
                selection.shape().requiresThirdPoint() && selection.a().dimension() != selection.c().dimension()) {
            throw new GeometryPlanException(GeometryPlanException.Reason.CROSS_DIMENSION_SELECTION,
                    "A geometry operation cannot span dimensions");
        }
    }

    private static void planLine(PlanBuilder builder, BlockPos start, BlockPos end) {
        long length = Math.max(Math.abs((long) start.getX() - end.getX()),
                Math.max(Math.abs((long) start.getY() - end.getY()), Math.abs((long) start.getZ() - end.getZ()))) + 1L;
        builder.checkPotentialCount(length);

        int x1 = start.getX();
        int y1 = start.getY();
        int z1 = start.getZ();
        int x2 = end.getX();
        int y2 = end.getY();
        int z2 = end.getZ();

        int deltaX = Math.abs(x1 - x2);
        int deltaY = Math.abs(y1 - y2);
        int deltaZ = Math.abs(z1 - z2);
        int stepX = x1 < x2 ? 1 : -1;
        int stepY = y1 < y2 ? 1 : -1;
        int stepZ = z1 < z2 ? 1 : -1;

        builder.add(new BlockPos(x1, y1, z1), VoxelRole.EDGE, 0, 0);
        if (deltaX >= deltaY && deltaX >= deltaZ) {
            int firstError = 2 * deltaY - deltaX;
            int secondError = 2 * deltaZ - deltaX;
            while (x1 != x2) {
                x1 += stepX;
                if (firstError >= 0) {
                    y1 += stepY;
                    firstError -= 2 * deltaX;
                }
                if (secondError >= 0) {
                    z1 += stepZ;
                    secondError -= 2 * deltaX;
                }
                firstError += 2 * deltaY;
                secondError += 2 * deltaZ;
                builder.add(new BlockPos(x1, y1, z1), VoxelRole.EDGE, 0, 0);
            }
        } else if (deltaY >= deltaX && deltaY >= deltaZ) {
            int firstError = 2 * deltaX - deltaY;
            int secondError = 2 * deltaZ - deltaY;
            while (y1 != y2) {
                y1 += stepY;
                if (firstError >= 0) {
                    x1 += stepX;
                    firstError -= 2 * deltaY;
                }
                if (secondError >= 0) {
                    z1 += stepZ;
                    secondError -= 2 * deltaY;
                }
                firstError += 2 * deltaX;
                secondError += 2 * deltaZ;
                builder.add(new BlockPos(x1, y1, z1), VoxelRole.EDGE, 0, 0);
            }
        } else {
            int firstError = 2 * deltaY - deltaZ;
            int secondError = 2 * deltaX - deltaZ;
            while (z1 != z2) {
                z1 += stepZ;
                if (firstError >= 0) {
                    y1 += stepY;
                    firstError -= 2 * deltaZ;
                }
                if (secondError >= 0) {
                    x1 += stepX;
                    secondError -= 2 * deltaZ;
                }
                firstError += 2 * deltaY;
                secondError += 2 * deltaX;
                builder.add(new BlockPos(x1, y1, z1), VoxelRole.EDGE, 0, 0);
            }
        }
    }

    private static void planCube(PlanBuilder builder, BlockPos first, BlockPos second) {
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());
        builder.checkPotentialCount(span(minX, maxX), span(minY, maxY), span(minZ, maxZ));

        for (long x = minX; x <= maxX; x++) {
            for (long y = minY; y <= maxY; y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    int insideAxes = 0;
                    if (x > minX && x < maxX) insideAxes++;
                    if (y > minY && y < maxY) insideAxes++;
                    if (z > minZ && z < maxZ) insideAxes++;
                    VoxelRole role = switch (insideAxes) {
                        case 0 -> VoxelRole.CORNER;
                        case 1 -> VoxelRole.EDGE;
                        case 2 -> VoxelRole.FACE;
                        case 3 -> VoxelRole.VOLUME;
                        default -> throw new AssertionError();
                    };
                    builder.add(new BlockPos((int) x, (int) y, (int) z), role, insideAxes, insideAxes);
                }
            }
        }
    }

    private static void planSphere(PlanBuilder builder, BlockPos first, BlockPos second) {
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());
        int sizeX = Math.toIntExact(span(minX, maxX));
        int sizeY = Math.toIntExact(span(minY, maxY));
        int sizeZ = Math.toIntExact(span(minZ, maxZ));
        builder.checkPotentialCount(sizeX, sizeY, sizeZ);

        double radiusX = sizeX / 2.0D;
        double radiusY = sizeY / 2.0D;
        double radiusZ = sizeZ / 2.0D;
        List<BlockPos> voxels = new ArrayList<>();
        Set<BlockPos> present = new HashSet<>();

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    double distance = squaredDistanceToCenter(x, radiusX, radiusX > 1.0D) +
                            squaredDistanceToCenter(y, radiusY, radiusY > 1.0D) +
                            squaredDistanceToCenter(z, radiusZ, radiusZ > 1.0D);
                    if (distance <= 1.0D) {
                        BlockPos position = new BlockPos(x + minX, y + minY, z + minZ);
                        voxels.add(position);
                        present.add(position);
                    }
                }
            }
        }

        int[][] surfaceDirections = activeDirections(radiusX > 1.0D, radiusY > 1.0D, radiusZ > 1.0D);
        for (BlockPos position : voxels) {
            boolean surface = false;
            for (int[] direction : surfaceDirections) {
                if (!present.contains(position.add(direction[0], direction[1], direction[2]))) {
                    surface = true;
                    break;
                }
            }
            builder.add(position, surface ? VoxelRole.FACE : VoxelRole.VOLUME, surface ? 0 : 1, surface ? 0 : 1);
        }
    }

    private static void planCylinder(PlanBuilder builder, BlockPos origin, BlockPos pointB, BlockPos pointC) {
        BlockPos pinnedB = pinToPlanes(origin, pointB);
        BlockPos pinnedC = pinToLine(origin, pinnedB, pointC);
        int heightX = pinnedC.getX() - origin.getX();
        int heightY = pinnedC.getY() - origin.getY();
        int heightZ = pinnedC.getZ() - origin.getZ();
        int deltaX = pinnedB.getX() - origin.getX();
        int deltaY = pinnedB.getY() - origin.getY();
        int deltaZ = pinnedB.getZ() - origin.getZ();
        deltaX += Integer.signum(deltaX);
        deltaY += Integer.signum(deltaY);
        deltaZ += Integer.signum(deltaZ);

        int firstDelta;
        int secondDelta;
        int heightDelta;
        int[] firstVector;
        int[] secondVector;
        int[] heightVector;
        switch (smallestAbsoluteComponent(deltaX, deltaY, deltaZ)) {
            case 0 -> {
                firstDelta = deltaY;
                secondDelta = deltaZ;
                heightDelta = heightX;
                firstVector = new int[] { 0, Integer.signum(deltaY), 0 };
                secondVector = new int[] { 0, 0, Integer.signum(deltaZ) };
                heightVector = new int[] { Integer.signum(heightX), 0, 0 };
            }
            case 1 -> {
                firstDelta = deltaX;
                secondDelta = deltaZ;
                heightDelta = heightY;
                firstVector = new int[] { Integer.signum(deltaX), 0, 0 };
                secondVector = new int[] { 0, 0, Integer.signum(deltaZ) };
                heightVector = new int[] { 0, Integer.signum(heightY), 0 };
            }
            case 2 -> {
                firstDelta = deltaX;
                secondDelta = deltaY;
                heightDelta = heightZ;
                firstVector = new int[] { Integer.signum(deltaX), 0, 0 };
                secondVector = new int[] { 0, Integer.signum(deltaY), 0 };
                heightVector = new int[] { 0, 0, Integer.signum(heightZ) };
            }
            default -> throw new AssertionError();
        }

        int firstSize = Math.abs(firstDelta);
        int secondSize = Math.abs(secondDelta);
        int heightSize = Math.abs(heightDelta) + 1;
        if (firstSize == 0 || secondSize == 0) {
            throw new GeometryPlanException(GeometryPlanException.Reason.INVALID_CYLINDER,
                    "A cylinder needs non-zero width on both cross-section axes");
        }
        builder.checkPotentialCount(firstSize, secondSize, heightSize);

        double firstRadius = firstSize / 2.0D;
        double secondRadius = secondSize / 2.0D;
        List<LocalCylinderVoxel> voxels = new ArrayList<>();
        Set<LocalCylinderVoxel> present = new HashSet<>();
        for (int first = 0; first < firstSize; first++) {
            for (int second = 0; second < secondSize; second++) {
                double distance = squaredDistanceToCenter(first, firstRadius, true) +
                        squaredDistanceToCenter(second, secondRadius, true);
                if (distance <= 1.0D) {
                    for (int height = 0; height < heightSize; height++) {
                        LocalCylinderVoxel voxel = new LocalCylinderVoxel(first, height, second);
                        voxels.add(voxel);
                        present.add(voxel);
                    }
                }
            }
        }

        for (LocalCylinderVoxel voxel : voxels) {
            boolean horizontalComplete = contains(present, voxel, -1, 0, 0) &&
                    contains(present, voxel, 1, 0, 0) &&
                    contains(present, voxel, 0, 0, -1) &&
                    contains(present, voxel, 0, 0, 1);
            boolean allNeighbors = horizontalComplete && contains(present, voxel, 0, -1, 0) &&
                    contains(present, voxel, 0, 1, 0);
            VoxelRole role = allNeighbors ? VoxelRole.VOLUME : horizontalComplete ? VoxelRole.FACE : VoxelRole.EDGE;
            int renderOrder = role == VoxelRole.EDGE ? 1 : 2;
            int buildOrder = role == VoxelRole.EDGE ? 1 : 0;
            BlockPos position = transformCylinderPosition(origin, voxel, firstVector, secondVector, heightVector);
            builder.add(position, role, renderOrder, buildOrder);
        }
    }

    private static BlockPos transformCylinderPosition(BlockPos origin, LocalCylinderVoxel voxel, int[] firstVector,
                                                      int[] secondVector, int[] heightVector) {
        int x = voxel.first * firstVector[0] + voxel.second * secondVector[0] + voxel.height * heightVector[0] +
                origin.getX();
        int y = voxel.first * firstVector[1] + voxel.second * secondVector[1] + voxel.height * heightVector[1] +
                origin.getY();
        int z = voxel.first * firstVector[2] + voxel.second * secondVector[2] + voxel.height * heightVector[2] +
                origin.getZ();
        return new BlockPos(x, y, z);
    }

    private static boolean contains(Set<LocalCylinderVoxel> present, LocalCylinderVoxel voxel, int firstDelta,
                                    int heightDelta, int secondDelta) {
        return present.contains(new LocalCylinderVoxel(voxel.first + firstDelta, voxel.height + heightDelta,
                voxel.second + secondDelta));
    }

    private static double squaredDistanceToCenter(int coordinate, double radius, boolean active) {
        if (!active) return 0.0D;
        double normalized = (coordinate - radius + 0.5D) / radius;
        return normalized * normalized;
    }

    private static int[][] activeDirections(boolean x, boolean y, boolean z) {
        List<int[]> directions = new ArrayList<>(6);
        if (x) {
            directions.add(new int[] { -1, 0, 0 });
            directions.add(new int[] { 1, 0, 0 });
        }
        if (y) {
            directions.add(new int[] { 0, -1, 0 });
            directions.add(new int[] { 0, 1, 0 });
        }
        if (z) {
            directions.add(new int[] { 0, 0, -1 });
            directions.add(new int[] { 0, 0, 1 });
        }
        return directions.toArray(new int[0][]);
    }

    private static int smallestAbsoluteComponent(int x, int y, int z) {
        long absoluteX = Math.abs((long) x);
        long absoluteY = Math.abs((long) y);
        long absoluteZ = Math.abs((long) z);
        if (absoluteX <= absoluteY && absoluteX <= absoluteZ) return 0;
        return absoluteY <= absoluteZ ? 1 : 2;
    }

    private static long span(int minimum, int maximum) {
        return (long) maximum - minimum + 1L;
    }

    private record LocalCylinderVoxel(int first, int height, int second) {}

    private static final class PlanBuilder {

        private final int dimension;
        private final int maximumOperations;
        private final List<ManipulatorOperation> operations = new ArrayList<>();

        private PlanBuilder(int dimension, int maximumOperations) {
            this.dimension = dimension;
            this.maximumOperations = maximumOperations;
        }

        private void add(BlockPos position, VoxelRole role, int renderOrder, int buildOrder) {
            if (operations.size() >= maximumOperations) {
                throw limitExceeded(maximumOperations);
            }
            operations.add(new ManipulatorOperation(ManipulatorOperation.Type.PLACE,
                    new ManipulatorLocation(dimension, position), role, renderOrder, buildOrder));
        }

        private void checkPotentialCount(long... factors) {
            long total = 1L;
            for (long factor : factors) {
                if (factor <= 0 || factor > maximumOperations || total > maximumOperations / factor) {
                    throw limitExceeded(maximumOperations);
                }
                total *= factor;
            }
        }
    }

    private static GeometryPlanException limitExceeded(int maximumOperations) {
        return new GeometryPlanException(GeometryPlanException.Reason.OPERATION_LIMIT_EXCEEDED,
                "The geometry operation exceeds its maximum of " + maximumOperations + " blocks");
    }
}
