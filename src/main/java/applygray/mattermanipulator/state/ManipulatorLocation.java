package applygray.mattermanipulator.state;

import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

/** Immutable dimension-qualified block position used by manipulator state and plans. */
public final class ManipulatorLocation {

    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_POSITION = "Position";

    private final int dimension;
    private final BlockPos position;

    public ManipulatorLocation(int dimension, BlockPos position) {
        this.dimension = dimension;
        Objects.requireNonNull(position, "position");
        this.position = new BlockPos(position.getX(), position.getY(), position.getZ());
    }

    public static ManipulatorLocation fromWorld(World world, BlockPos position) {
        Objects.requireNonNull(world, "world");
        return new ManipulatorLocation(world.provider.getDimension(), position);
    }

    public int dimension() {
        return dimension;
    }

    public BlockPos position() {
        return position;
    }

    public ManipulatorLocation offset(EnumFacing facing) {
        return new ManipulatorLocation(dimension, position.offset(facing));
    }

    public long distanceSquared(ManipulatorLocation other) {
        Objects.requireNonNull(other, "other");
        if (dimension != other.dimension) return Long.MAX_VALUE;

        long deltaX = (long) position.getX() - other.position.getX();
        long deltaY = (long) position.getY() - other.position.getY();
        long deltaZ = (long) position.getZ() - other.position.getZ();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    public void writeTo(NBTTagCompound parent, String key) {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger(KEY_DIMENSION, dimension);
        data.setLong(KEY_POSITION, position.toLong());
        parent.setTag(key, data);
    }

    public static ManipulatorLocation readFrom(NBTTagCompound parent, String key) {
        if (parent == null || !parent.hasKey(key, Constants.NBT.TAG_COMPOUND)) return null;

        NBTTagCompound data = parent.getCompoundTag(key);
        if (!data.hasKey(KEY_DIMENSION, Constants.NBT.TAG_INT) ||
                !data.hasKey(KEY_POSITION, Constants.NBT.TAG_LONG)) {
            return null;
        }

        return new ManipulatorLocation(data.getInteger(KEY_DIMENSION), BlockPos.fromLong(data.getLong(KEY_POSITION)));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ManipulatorLocation location)) return false;
        return dimension == location.dimension && position.equals(location.position);
    }

    @Override
    public int hashCode() {
        return 31 * dimension + position.hashCode();
    }

    @Override
    public String toString() {
        return "ManipulatorLocation[dimension=" + dimension + ", position=" + position + ']';
    }
}
