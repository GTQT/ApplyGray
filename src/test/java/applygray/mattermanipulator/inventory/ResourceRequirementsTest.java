package applygray.mattermanipulator.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ResourceRequirementsTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void keepsDifferentFluidTagsAsSeparateExactRequirements() {
        Fluid fluid = fluid("resource_requirement_tagged");
        NBTTagCompound firstTag = new NBTTagCompound();
        firstTag.setString("grade", "first");
        NBTTagCompound secondTag = new NBTTagCompound();
        secondTag.setString("grade", "second");
        FluidStack first = new FluidStack(fluid, 1, firstTag);
        FluidStack second = new FluidStack(fluid, 1, secondTag);

        ResourceRequirements requirements = ResourceRequirements.fluids(
                new FluidRequirement(first, 100), new FluidRequirement(second, 200),
                new FluidRequirement(first, 50));

        assertEquals(2, requirements.fluidEntries().size());
        assertEquals(150L, requirements.fluidEntries().stream()
                .filter(entry -> firstTag.equals(entry.tag())).findFirst().orElseThrow().amount());
        assertEquals(200L, requirements.fluidEntries().stream()
                .filter(entry -> secondTag.equals(entry.tag())).findFirst().orElseThrow().amount());
    }

    private static Fluid fluid(String name) {
        ResourceLocation texture = new ResourceLocation("applygray", name);
        Fluid fluid = new Fluid(name, texture, texture);
        if (!FluidRegistry.registerFluid(fluid)) fluid = FluidRegistry.getFluid(name);
        return fluid;
    }
}
