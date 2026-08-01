package applygray.integration.ae2;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicRecipePatternRegistryOutputTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void exposesOnlyTheRequestedOutputAndOmitsByproducts() {
        AEFluidKey target = fluidKey("pattern_target");
        AEFluidKey byproduct = fluidKey("pattern_byproduct");

        List<GenericStack> outputs = DynamicRecipePatternRegistry.selectRequestedPatternOutputs(target, List.of(
                new GenericStack(byproduct, 3000),
                new GenericStack(target, 600),
                new GenericStack(target, 400)));

        assertEquals(List.of(new GenericStack(target, 1000)), outputs);
    }

    private static AEFluidKey fluidKey(String name) {
        ResourceLocation texture = new ResourceLocation("applygray", name);
        Fluid fluid = new Fluid(name, texture, texture);
        if (!FluidRegistry.registerFluid(fluid)) fluid = FluidRegistry.getFluid(name);
        AEFluidKey key = AEFluidKey.of(fluid);
        if (key == null) throw new AssertionError("Could not create fluid key " + name);
        return key;
    }
}
