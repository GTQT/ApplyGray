package applygray.integration.ae2.rules;

import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.NormalizedRecipe;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSetOutputCapacityTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void acceptsTargetFluidWhenRecipeHasAnAdditionalFluidByproduct() {
        AEFluidKey target = fluidKey("target_fluid");
        AEFluidKey byproduct = fluidKey("byproduct_fluid");
        NormalizedRecipe recipe = createRecipe(List.of(
                new GenericStack(target, 1000),
                new GenericStack(byproduct, 3000)));
        RuleContext context = new RuleContext(recipe, createMachine(0, 1), target,
                PlanningMode.SAFE_FIRST, Set.of(), Map.of(), 1, 0);

        RuleDecision decision = RuleSet.empty().evaluate(context, List.of());

        assertTrue(decision.isAllowed());
    }

    @Test
    void rejectsFluidTargetWhenMachineHasNoFluidOutputTank() {
        AEFluidKey target = fluidKey("target_without_tank");
        RuleContext context = new RuleContext(createRecipe(List.of(new GenericStack(target, 1000))),
                createMachine(1, 0), target, PlanningMode.SAFE_FIRST, Set.of(), Map.of(), 0, 0);

        RuleDecision decision = RuleSet.empty().evaluate(context, List.of());

        assertEquals("OUTPUT_CAPACITY_UNPROVEN", decision.getDenialCode());
    }

    private static AEFluidKey fluidKey(String name) {
        ResourceLocation texture = new ResourceLocation("applygray", name);
        Fluid fluid = new Fluid(name, texture, texture);
        if (!FluidRegistry.registerFluid(fluid)) fluid = FluidRegistry.getFluid(name);
        AEFluidKey key = AEFluidKey.of(fluid);
        if (key == null) throw new AssertionError("Could not create fluid key " + name);
        return key;
    }

    private static NormalizedRecipe createRecipe(List<GenericStack> outputs) {
        try {
            Constructor<NormalizedRecipe> constructor = NormalizedRecipe.class.getDeclaredConstructor(String.class,
                    String.class, String.class, int.class, List.class, List.class, List.class, List.class,
                    List.class, List.class, Map.class, Map.class, Set.class, Set.class, Set.class, String.class,
                    String.class, long.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance("test_output_map", "test-fingerprint", "test-content-version", 0,
                    List.of(), List.of(), outputs, List.of(), List.of(), List.of(), Map.of(), Map.of(), Set.of(),
                    Set.of(), Set.of(), null, "", 0L, 1);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not construct normalized recipe test fixture", exception);
        }
    }

    private static MachineCapabilityProfile createMachine(int itemOutputSlots, int fluidOutputTanks) {
        try {
            Constructor<MachineCapabilityProfile> constructor = MachineCapabilityProfile.class.getDeclaredConstructor(
                    String.class, String.class, boolean.class, List.class, long.class, int.class, int.class,
                    int.class, int.class, int.class, Set.class, Set.class, Map.class);
            constructor.setAccessible(true);
            return constructor.newInstance("test-provider", "test-controller", true, List.of("test_output_map"),
                    0L, 0, itemOutputSlots, fluidOutputTanks, 0, Integer.MIN_VALUE,
                    Set.of(), Set.of("structure"), Map.of());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not construct machine capability test fixture", exception);
        }
    }
}
