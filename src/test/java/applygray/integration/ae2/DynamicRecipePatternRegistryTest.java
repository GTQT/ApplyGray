package applygray.integration.ae2;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.chance.output.ChancedOutputList;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtech.api.recipes.ingredients.GTRecipeFluidInput;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.ingredients.GTRecipeItemInput;
import gregtech.api.recipes.properties.RecipePropertyStorageImpl;
import gregtech.api.GregTechAPI;
import gregtech.api.modules.IModuleManager;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRecipePatternRegistryTest {

    private static final int HIGH_GRADE_SOLDER_AMOUNT = 20 * 144;
    private static final int POLYBENZIMIDAZOLE_AMOUNT = 8 * 144;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
        GregTechAPI.moduleManager = (IModuleManager) Proxy.newProxyInstance(
                DynamicRecipePatternRegistryTest.class.getClassLoader(), new Class[]{IModuleManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }

    @Test
    void encodesEveryInputOfTheUhvWetwareMainframeRecipe() throws ReflectiveOperationException {
        Recipe recipe = createUhvWetwareMainframeRecipe();

        Object encoded = encode(recipe);
        assertNotNull(encoded, "UHV wetware mainframe should be representable as a virtual pattern");

        @SuppressWarnings("unchecked")
        List<GenericStack> inputs = (List<GenericStack>) readField(encoded, "inputs");

        assertEquals(13, inputs.size(), "11 item inputs plus 2 fluid inputs must reach AE2");
        assertTrue(inputs.stream().anyMatch(stack -> isFluidAmount(stack, HIGH_GRADE_SOLDER_AMOUNT)),
                "high-grade soldering alloy must be included in the pattern");
        assertTrue(inputs.stream().anyMatch(stack -> isFluidAmount(stack, POLYBENZIMIDAZOLE_AMOUNT)),
                "polybenzimidazole must be included in the pattern");
    }

    private static Recipe createUhvWetwareMainframeRecipe() {
        int[] itemAmounts = {2, 2, 32, 32, 32, 32, 32, 64, 32, 16, 8};
        List<GTRecipeInput> itemInputs = new ArrayList<>(itemAmounts.length);
        for (int index = 0; index < itemAmounts.length; index++) {
            Item item = new Item().setRegistryName("applygray_test", "uhv_wetware_input_" + index);
            itemInputs.add(new GTRecipeItemInput(new ItemStack(item), itemAmounts[index]));
        }

        List<GTRecipeInput> fluidInputs = List.of(
                new GTRecipeFluidInput(new FluidStack(FluidRegistry.WATER, HIGH_GRADE_SOLDER_AMOUNT)),
                new GTRecipeFluidInput(new FluidStack(FluidRegistry.LAVA, POLYBENZIMIDAZOLE_AMOUNT)));
        Item outputItem = new Item().setRegistryName("applygray_test", "uhv_wetware_mainframe");
        Item dustItem = new Item().setRegistryName("applygray_test", "simulation_muffler_dust");

        return new Recipe(itemInputs, List.of(new ItemStack(outputItem)), ChancedOutputList.<ItemStack,
                ChancedItemOutput>empty(), fluidInputs, List.of(), ChancedOutputList.<FluidStack,
                ChancedFluidOutput>empty(), List.of(new ItemStack(dustItem)), 2000, 300000, false, false,
                new RecipePropertyStorageImpl(), null);
    }

    private static Object encode(Recipe recipe) throws ReflectiveOperationException {
        Method method = DynamicRecipePatternRegistry.class.getDeclaredMethod("encodeRecipe",
                DynamicRecipePatternRegistry.ProviderSnapshot.class, Recipe.class);
        method.setAccessible(true);
        return method.invoke(null, null, recipe);
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean isFluidAmount(GenericStack stack, int amount) {
        return stack.what() instanceof AEFluidKey && stack.amount() == amount;
    }
}
