package gregtech.common.items.tool.rotation;

import gregtech.api.cover.CoverRayTracer;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.EnumFacing.AxisDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.IOrientableBlock;
import ae2.tile.AEBaseTile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AECustomBlockRotations {

    public static void init() {
        ICustomRotationBehavior aeOrientableBehavior = new ICustomRotationBehavior() {

            @Override
            public boolean doesApply(@NotNull IBlockState state, @NotNull World world, @NotNull BlockPos pos) {
                if (!state.isFullBlock()) return false; // try to exclude some weird stuff
                return getOrientable(world, pos) != null;
            }

            @Override
            public boolean customRotate(IBlockState state, World world, BlockPos pos, RayTraceResult hitResult) {
                IOrientableBlock orientable = getOrientable(world, pos);
                if (orientable == null) return false;

                EnumFacing gridSide = CoverRayTracer.determineGridSideHit(hitResult);
                if (gridSide == null) return false;

                BlockOrientation orientation = orientable.getOrientation(world.getBlockState(pos));
                BlockOrientation updated = gridSide == orientation.getSide(ae2.api.orientation.RelativeSide.FRONT)
                        ? orientation.rotateClockwiseAround(gridSide)
                        : BlockOrientation.get(gridSide,
                                simulateAxisRotation(gridSide,
                                        orientation.getSide(ae2.api.orientation.RelativeSide.FRONT),
                                        orientation.getSide(ae2.api.orientation.RelativeSide.TOP)));
                updated.setOn(world, pos);
                return true;
            }

            @Override
            public boolean allowSpin() {
                return true;
            }

            @Override
            public @Nullable EnumFacing getSpinFrontFacing(IBlockState state, World world, BlockPos pos) {
                IOrientableBlock orientable = getOrientable(world, pos);
                if (orientable == null) return null;
                return orientable.getOrientation(world.getBlockState(pos))
                        .getSide(ae2.api.orientation.RelativeSide.FRONT);
            }

            private IOrientableBlock getOrientable(World world, BlockPos pos) {
                if (!(world.getBlockState(pos).getBlock() instanceof IOrientableBlock orientable)) {
                    return null;
                }
                return world.getTileEntity(pos) instanceof AEBaseTile tile && tile.canBeRotated() ? orientable : null;
            }
        };

        CustomBlockRotations.registerCustomRotation(aeOrientableBehavior);
    }

    /* Similar to the one in RelativeDirection, but AE stores their upwards facing as absolute instead of relative */
    private static EnumFacing simulateAxisRotation(EnumFacing newFrontFacing, EnumFacing oldFrontFacing,
                                                   EnumFacing upwardsFacing) {
        if (newFrontFacing == oldFrontFacing) return upwardsFacing;

        Axis newAxis = newFrontFacing.getAxis();
        Axis oldAxis = oldFrontFacing.getAxis();

        if (newAxis != Axis.Y && oldAxis != Axis.Y) {
            // was on horizontal axis and still is
            EnumFacing newUpwardsFacing = upwardsFacing;
            if (oldFrontFacing.rotateY() == upwardsFacing) {
                // upwards facing is left
                newUpwardsFacing = newFrontFacing.rotateY();
            } else if (oldFrontFacing.rotateYCCW() == upwardsFacing) {
                // upwards facing is right
                newUpwardsFacing = newFrontFacing.rotateYCCW();
            }
            return newUpwardsFacing;
        } else if (newAxis == Axis.Y && oldAxis != Axis.Y) {
            // going from horizontal to vertical axis
            EnumFacing newUpwardsFacing = upwardsFacing;
            if (upwardsFacing == EnumFacing.UP) {
                newUpwardsFacing = oldFrontFacing.getOpposite();
            } else if (upwardsFacing == EnumFacing.DOWN) {
                newUpwardsFacing = oldFrontFacing;
            }
            return newUpwardsFacing;
        } else if (newAxis != Axis.Y) {
            // going from vertical to horizontal axis
            EnumFacing newUpwardsFacing = upwardsFacing;
            if (newFrontFacing == upwardsFacing) {
                newUpwardsFacing = EnumFacing.DOWN;
            } else if (newFrontFacing == upwardsFacing.getOpposite()) {
                newUpwardsFacing = EnumFacing.UP;
            }
            return newUpwardsFacing;
        } else {
            // was on vertical axis and still is
            return upwardsFacing.getOpposite();
        }
    }
}
