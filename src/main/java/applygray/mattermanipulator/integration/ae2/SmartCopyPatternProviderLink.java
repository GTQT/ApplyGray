package applygray.mattermanipulator.integration.ae2;

import java.util.Objects;

import ae2.api.parts.IPart;
import ae2.api.parts.IPartHost;
import ae2.parts.crafting.PatternProviderPart;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

/** Immutable persisted address of a Pattern Provider source endpoint. */
public final class SmartCopyPatternProviderLink {

    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_X = "X";
    private static final String KEY_Y = "Y";
    private static final String KEY_Z = "Z";
    private static final String KEY_SIDE = "Side";

    private final int sourceDimension;
    private final BlockPos sourcePosition;
    private final EnumFacing sourceSide;

    public SmartCopyPatternProviderLink(int sourceDimension, BlockPos sourcePosition, EnumFacing sourceSide) {
        this.sourceDimension = sourceDimension;
        this.sourcePosition = Objects.requireNonNull(sourcePosition, "sourcePosition").toImmutable();
        this.sourceSide = Objects.requireNonNull(sourceSide, "sourceSide");
    }

    public static SmartCopyPatternProviderLink forSource(World world, BlockPos position, EnumFacing side) {
        Objects.requireNonNull(world, "world");
        return new SmartCopyPatternProviderLink(world.provider.getDimension(), position, side);
    }

    public int sourceDimension() {
        return sourceDimension;
    }

    public BlockPos sourcePosition() {
        return sourcePosition;
    }

    public EnumFacing sourceSide() {
        return sourceSide;
    }

    public void writeToNbt(NBTTagCompound data) {
        data.setInteger(KEY_DIMENSION, sourceDimension);
        data.setInteger(KEY_X, sourcePosition.getX());
        data.setInteger(KEY_Y, sourcePosition.getY());
        data.setInteger(KEY_Z, sourcePosition.getZ());
        data.setByte(KEY_SIDE, (byte) sourceSide.getIndex());
    }

    public static SmartCopyPatternProviderLink readFromNbt(NBTTagCompound data) {
        if (data == null) return null;
        int side = data.getByte(KEY_SIDE);
        if (side < 0 || side >= EnumFacing.VALUES.length) return null;
        return new SmartCopyPatternProviderLink(data.getInteger(KEY_DIMENSION),
                new BlockPos(data.getInteger(KEY_X), data.getInteger(KEY_Y), data.getInteger(KEY_Z)),
                EnumFacing.VALUES[side]);
    }

    public PatternProviderPart resolve() {
        World world = DimensionManager.getWorld(sourceDimension);
        if (world == null || !world.isBlockLoaded(sourcePosition)) return null;

        TileEntity tile = world.getTileEntity(sourcePosition);
        if (!(tile instanceof IPartHost host)) return null;
        IPart part = host.getPart(sourceSide);
        return part instanceof PatternProviderPart provider ? provider : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SmartCopyPatternProviderLink link)) return false;
        return sourceDimension == link.sourceDimension && sourcePosition.equals(link.sourcePosition) &&
                sourceSide == link.sourceSide;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceDimension, sourcePosition, sourceSide);
    }

    @Override
    public String toString() {
        return "SmartCopyPatternProviderLink{" + sourceDimension + ":" + sourcePosition + "," + sourceSide + '}';
    }
}
