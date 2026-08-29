package applygray.mattermanipulator.state;

import java.math.BigInteger;

import applygray.mattermanipulator.planning.GeometryPlanner;

import net.minecraft.util.math.BlockPos;

/** Exact dimensions for the currently displayed selection region without scanning world blocks. */
public record ManipulatorSelectionDimensions(long x, long y, long z) {

    public static ManipulatorSelectionDimensions from(ManipulatorState state, int dimension) {
        ManipulatorLocation a = state.selectionA();
        ManipulatorLocation b = state.selectionB();
        if (!sameDimension(dimension, a, b)) return null;

        BlockPos first = a.position();
        BlockPos second = b.position();
        BlockPos third = null;
        switch (state.placeMode()) {
            case GEOMETRY -> {
                if (state.shape() == ManipulatorShape.CYLINDER) {
                    if (!sameDimension(dimension, state.selectionC())) return null;
                    second = GeometryPlanner.pinToPlanes(first, second);
                    third = GeometryPlanner.pinToLine(first, second, state.selectionC().position());
                }
            }
            case COPYING -> {
                long sourceX = span(first.getX(), second.getX());
                long sourceY = span(first.getY(), second.getY());
                long sourceZ = span(first.getZ(), second.getZ());
                if (!sameDimension(dimension, state.selectionC())) {
                    return new ManipulatorSelectionDimensions(sourceX, sourceY, sourceZ);
                }
                return transformed(sourceX, sourceY, sourceZ, state);
            }
            case MOVING -> {
                return new ManipulatorSelectionDimensions(span(first.getX(), second.getX()),
                        span(first.getY(), second.getY()), span(first.getZ(), second.getZ()));
            }
            case EXCHANGING, CABLES -> {
                // Exchange and Manhattan cable paths use the untransformed A/B bounds.
            }
        }

        long sizeX = span(first.getX(), second.getX());
        long sizeY = span(first.getY(), second.getY());
        long sizeZ = span(first.getZ(), second.getZ());
        if (third != null) {
            sizeX = unionSpan(first.getX(), second.getX(), third.getX());
            sizeY = unionSpan(first.getY(), second.getY(), third.getY());
            sizeZ = unionSpan(first.getZ(), second.getZ(), third.getZ());
        }
        return new ManipulatorSelectionDimensions(sizeX, sizeY, sizeZ);
    }

    public BigInteger volume() {
        return BigInteger.valueOf(x).multiply(BigInteger.valueOf(y)).multiply(BigInteger.valueOf(z));
    }

    private static ManipulatorSelectionDimensions transformed(long x, long y, long z, ManipulatorState state) {
        BlockPos axisX = state.copyTransform().apply(new BlockPos(1, 0, 0));
        BlockPos axisY = state.copyTransform().apply(new BlockPos(0, 1, 0));
        BlockPos axisZ = state.copyTransform().apply(new BlockPos(0, 0, 1));
        long transformedX = component(axisX.getX(), x, axisY.getX(), y, axisZ.getX(), z);
        long transformedY = component(axisX.getY(), x, axisY.getY(), y, axisZ.getY(), z);
        long transformedZ = component(axisX.getZ(), x, axisY.getZ(), y, axisZ.getZ(), z);
        return new ManipulatorSelectionDimensions(
                Math.multiplyExact(transformedX, Math.abs((long) state.copyRepeatX())),
                Math.multiplyExact(transformedY, Math.abs((long) state.copyRepeatY())),
                Math.multiplyExact(transformedZ, Math.abs((long) state.copyRepeatZ())));
    }

    private static long component(int xAxis, long x, int yAxis, long y, int zAxis, long z) {
        return Math.abs((long) xAxis) * x + Math.abs((long) yAxis) * y + Math.abs((long) zAxis) * z;
    }

    private static boolean sameDimension(int dimension, ManipulatorLocation... locations) {
        for (ManipulatorLocation location : locations) {
            if (location == null || location.dimension() != dimension) return false;
        }
        return true;
    }

    private static long span(int first, int second) {
        return Math.abs((long) first - second) + 1L;
    }

    private static long unionSpan(int first, int second, int third) {
        long minimum = Math.min(first, Math.min(second, third));
        long maximum = Math.max(first, Math.max(second, third));
        return maximum - minimum + 1L;
    }
}
