package applygray.common.blocks;

import gregtech.api.block.VariantBlock;

import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import org.jetbrains.annotations.NotNull;

/**
 * Quantum storage unit casing: a pure structural tiered block whose stats are
 * aggregated by the storage multiblock controllers. It holds no inventory of
 * its own; every piece of content lives on the controller.
 */
public class BlockQuantumStorageUnit extends VariantBlock<QuantumStorageUnit> {

    public BlockQuantumStorageUnit() {
        super(Material.IRON);
        setTranslationKey("quantum_storage_unit");
        setHardness(6.0f);
        setResistance(12.0f);
        setSoundType(SoundType.METAL);
        setDefaultState(getState(QuantumStorageUnit.T1));
    }

    @Override
    public boolean canCreatureSpawn(@NotNull IBlockState state, @NotNull IBlockAccess world, @NotNull BlockPos pos,
                                    @NotNull EntityLiving.SpawnPlacementType type) {
        return false;
    }
}
