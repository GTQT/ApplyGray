package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import gregtech.api.capability.impl.FluidTankList;

import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PatternBufferFluidRestoreTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void restoresPersistedFluidWithoutReapplyingTheRuntimeInputFilter() {
        Fluid accepted = fluid("pattern_buffer_restore_accepted");
        Fluid persisted = fluid("pattern_buffer_restore_persisted");
        FluidTank filteredTank = new FluidTank(16_000) {
            @Override
            public boolean canFillFluidType(FluidStack stack) {
                return stack != null && stack.getFluid() == accepted;
            }
        };
        FluidTankList tanks = new FluidTankList(false, filteredTank);
        NBTTagCompound persistedTag = new FluidStack(persisted, 1_000).writeToNBT(new NBTTagCompound());

        assertEquals(0, filteredTank.fill(new FluidStack(persisted, 1_000), true));

        MetaTileEntityMEPatternProvider.PatternBuffer.restorePersistedFluid(tanks, 0, persistedTag);

        FluidStack restored = tanks.getTankAt(0).getFluid();
        assertNotNull(restored);
        assertEquals(persisted, restored.getFluid());
        assertEquals(1_000, restored.amount);
    }

    private static Fluid fluid(String name) {
        ResourceLocation texture = new ResourceLocation("applygray", name);
        Fluid fluid = new Fluid(name, texture, texture);
        if (!FluidRegistry.registerFluid(fluid)) fluid = FluidRegistry.getFluid(name);
        return fluid;
    }
}
