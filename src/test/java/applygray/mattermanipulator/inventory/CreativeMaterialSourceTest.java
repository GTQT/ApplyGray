package applygray.mattermanipulator.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import applygray.mattermanipulator.building.BlockSpec;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreativeMaterialSourceTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void suppliesAndAcceptsSolidMaterialsWithoutStateChanges() {
        BlockSpec stone = BlockSpec.of(new ItemStack(Blocks.STONE));

        assertEquals(37L, CreativeMaterialSource.INSTANCE.extract(stone, 37L, false));
        assertEquals(37L, CreativeMaterialSource.INSTANCE.insert(stone, 37L, false));
    }

    @Test
    void suppliesAndAcceptsFluidsWithoutStateChanges() {
        FluidStack fluid = new FluidStack(net.minecraftforge.fluids.FluidRegistry.WATER, 1000);

        assertEquals(1000L, CreativeMaterialSource.INSTANCE.extract(fluid, 1000L, false));
        assertEquals(1000L, CreativeMaterialSource.INSTANCE.insert(fluid, 1000L, false));
    }

    @Test
    void rejectsNegativeTransfers() {
        BlockSpec stone = BlockSpec.of(new ItemStack(Blocks.STONE));

        assertThrows(IllegalArgumentException.class,
                () -> CreativeMaterialSource.INSTANCE.extract(stone, -1L, false));
    }
}
