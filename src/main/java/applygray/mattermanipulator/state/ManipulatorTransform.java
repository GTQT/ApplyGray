package applygray.mattermanipulator.state;

import java.util.Arrays;
import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/** Immutable three-dimensional orthogonal transform used by copy positions and adapter-owned directions. */
public final class ManipulatorTransform {

    private static final String KEY_MATRIX = "Matrix";
    private static final ManipulatorTransform IDENTITY = new ManipulatorTransform(
            1, 0, 0,
            0, 1, 0,
            0, 0, 1);

    private final int[] matrix;

    private ManipulatorTransform(int... matrix) {
        if (matrix.length != 9) throw new IllegalArgumentException("A transform matrix must have nine elements");
        this.matrix = matrix.clone();
        validate();
    }

    public static ManipulatorTransform identity() {
        return IDENTITY;
    }

    public ManipulatorTransform rotate(EnumFacing.Axis axis, boolean positive) {
        Objects.requireNonNull(axis, "axis");
        int direction = positive ? 1 : -1;
        ManipulatorTransform rotation = switch (axis) {
            case X -> new ManipulatorTransform(1, 0, 0, 0, 0, -direction, 0, direction, 0);
            case Y -> new ManipulatorTransform(0, 0, direction, 0, 1, 0, -direction, 0, 0);
            case Z -> new ManipulatorTransform(0, -direction, 0, direction, 0, 0, 0, 0, 1);
        };
        return rotation.multiply(this);
    }

    public ManipulatorTransform flip(EnumFacing.Axis axis) {
        Objects.requireNonNull(axis, "axis");
        int[] result = matrix.clone();
        int column = switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        for (int row = 0; row < 3; row++) result[row * 3 + column] *= -1;
        return new ManipulatorTransform(result);
    }

    public BlockPos apply(BlockPos vector) {
        Objects.requireNonNull(vector, "vector");
        return new BlockPos(component(0, vector.getX(), vector.getY(), vector.getZ()),
                component(1, vector.getX(), vector.getY(), vector.getZ()),
                component(2, vector.getX(), vector.getY(), vector.getZ()));
    }

    /** Applies the inverse orthogonal transform (the transpose of this matrix). */
    public BlockPos inverseApply(BlockPos vector) {
        Objects.requireNonNull(vector, "vector");
        return new BlockPos(inverseComponent(0, vector.getX(), vector.getY(), vector.getZ()),
                inverseComponent(1, vector.getX(), vector.getY(), vector.getZ()),
                inverseComponent(2, vector.getX(), vector.getY(), vector.getZ()));
    }

    public EnumFacing apply(EnumFacing facing) {
        Objects.requireNonNull(facing, "facing");
        BlockPos transformed = apply(new BlockPos(facing.getXOffset(), facing.getYOffset(), facing.getZOffset()));
        return EnumFacing.getFacingFromVector(transformed.getX(), transformed.getY(), transformed.getZ());
    }

    public EnumFacing.Axis apply(EnumFacing.Axis axis) {
        EnumFacing positive = switch (axis) {
            case X -> EnumFacing.EAST;
            case Y -> EnumFacing.UP;
            case Z -> EnumFacing.SOUTH;
        };
        return apply(positive).getAxis();
    }

    public int applyFacingMask(int mask) {
        int transformed = 0;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if ((mask & 1 << facing.getIndex()) != 0) transformed |= 1 << apply(facing).getIndex();
        }
        return transformed;
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        data.setIntArray(KEY_MATRIX, matrix);
        return data;
    }

    public static ManipulatorTransform readFromNbt(NBTTagCompound data) {
        if (data == null) return identity();
        int[] matrix = data.getIntArray(KEY_MATRIX);
        try {
            return matrix.length == 9 ? new ManipulatorTransform(matrix) : identity();
        } catch (IllegalArgumentException exception) {
            return identity();
        }
    }

    public String axisSummary() {
        return "+X " + apply(EnumFacing.EAST).getName() + ", +Y " + apply(EnumFacing.UP).getName() + ", +Z " +
                apply(EnumFacing.SOUTH).getName();
    }

    private ManipulatorTransform multiply(ManipulatorTransform other) {
        int[] result = new int[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int index = 0; index < 3; index++) {
                    result[row * 3 + column] += matrix[row * 3 + index] * other.matrix[index * 3 + column];
                }
            }
        }
        return new ManipulatorTransform(result);
    }

    private int component(int row, int x, int y, int z) {
        return matrix[row * 3] * x + matrix[row * 3 + 1] * y + matrix[row * 3 + 2] * z;
    }

    private int inverseComponent(int column, int x, int y, int z) {
        return matrix[column] * x + matrix[3 + column] * y + matrix[6 + column] * z;
    }

    private void validate() {
        for (int value : matrix) {
            if (value < -1 || value > 1) throw new IllegalArgumentException("Transform values must be -1, 0, or 1");
        }
        for (int row = 0; row < 3; row++) {
            int rowLength = 0;
            int columnLength = 0;
            for (int index = 0; index < 3; index++) {
                rowLength += matrix[row * 3 + index] * matrix[row * 3 + index];
                columnLength += matrix[index * 3 + row] * matrix[index * 3 + row];
            }
            if (rowLength != 1 || columnLength != 1) {
                throw new IllegalArgumentException("Transform matrix must be orthogonal");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ManipulatorTransform transform &&
                Arrays.equals(matrix, transform.matrix);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(matrix);
    }
}
