package applygray.common.quantum;

import applygray.common.blocks.BlockQuantumStorageUnit;
import applygray.common.blocks.QuantumStorageUnit;

import gregtech.api.util.RelativeDirection;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.math.BigInteger;

/**
 * Aggregates the stats of every {@link QuantumStorageUnit} block found inside a
 * formed quantum storage multiblock.
 * <p>
 * The storage controllers share one fixed 5×5×8 box geometry: the controller
 * sits centered in the front cap, followed by six 3×3 unit-core layers and a
 * solid back cap. All core positions are scanned at structure-form time and the
 * per-tier stats of the units actually placed are summed up (empty core cells
 * simply contribute nothing).
 */
public final class QuantumStorageUnitScanner {

    private QuantumStorageUnitScanner() {}

    public static final class Counts {
        public long distinctSlots;
        public BigInteger totalCapacity = BigInteger.ZERO;
        public int unitBlocks;
    }

    /**
     * Scans the unit core of the structure whose controller is at
     * {@code controllerPos}.
     *
     * @param frontFacing the front side of the controller (machine's facing)
     * @param upFacing    the upwards facing of the structure
     * @param flipped     whether the structure check matched a mirrored layout
     */
    public static Counts scan(World world, BlockPos controllerPos,
                              EnumFacing frontFacing, EnumFacing upFacing, boolean flipped) {
        Counts counts = new Counts();
        // Core layers run from one block behind the controller up to the back cap.
        for (int layer = 1; layer <= 6; layer++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos offset = RelativeDirection.setActualRelativeOffset(dx, dy, layer, frontFacing,
                            upFacing, flipped, new RelativeDirection[]{
                                    RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK});
                    IBlockState state = world.getBlockState(controllerPos.add(offset));
                    if (!(state.getBlock() instanceof BlockQuantumStorageUnit unitBlock)) {
                        continue;
                    }
                    QuantumStorageUnit unit = unitBlock.getState(state);
                    counts.unitBlocks++;
                    counts.distinctSlots += unit.distinctSlots();
                    counts.totalCapacity = counts.totalCapacity.add(unit.totalCapacity());
                }
            }
        }
        return counts;
    }
}
