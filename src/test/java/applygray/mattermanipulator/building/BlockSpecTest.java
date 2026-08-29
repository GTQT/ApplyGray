package applygray.mattermanipulator.building;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.block.BlockColored;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.EnumDyeColor;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockSpecTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void matchesTheSameBlockAndMetadata() {
        BlockSpec whiteWool = BlockSpec.fromState(Blocks.WOOL.getDefaultState());

        assertTrue(whiteWool.matchesWorldState(Blocks.WOOL.getDefaultState()));
        assertFalse(whiteWool.matchesWorldState(
                Blocks.WOOL.getDefaultState().withProperty(BlockColored.COLOR, EnumDyeColor.ORANGE)));
    }

    @Test
    void matchesAirOnlyWithAir() {
        assertTrue(BlockSpec.air().matchesWorldState(Blocks.AIR.getDefaultState()));
        assertFalse(BlockSpec.air().matchesWorldState(Blocks.STONE.getDefaultState()));
    }

    @Test
    void ignoresItemNbtWhichIsNotRepresentedByBlockState() {
        ItemStack stack = new ItemStack(Blocks.STONE);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("configuration", "preview-only");
        stack.setTagCompound(tag);

        assertTrue(BlockSpec.of(stack).matchesWorldState(Blocks.STONE.getDefaultState()));
    }

    @Test
    void matchesFluidsByTypeInsteadOfAmountOrLevel() {
        Fluid water = FluidRegistry.lookupFluidForBlock(Blocks.WATER);
        BlockSpec expected = BlockSpec.ofFluid(new FluidStack(water, 250));

        assertTrue(expected.matchesWorldState(Blocks.WATER.getStateFromMeta(0)));
        assertTrue(expected.matchesWorldState(Blocks.FLOWING_WATER.getStateFromMeta(7)));
        assertFalse(expected.matchesWorldState(Blocks.LAVA.getDefaultState()));
    }
}
