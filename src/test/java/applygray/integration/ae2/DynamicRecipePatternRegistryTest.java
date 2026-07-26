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

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void prefersGeneralCircuitBoardsForPrimaryPatternInputs() throws ReflectiveOperationException {
        Item normalCircuit = new Item().setTranslationKey("metaitem.circuit.basic")
                .setRegistryName("applygray_test", "normal_circuit");
        Item generalCircuitBoard = new Item().setTranslationKey("metaitem.general_circuit.lv")
                .setRegistryName("applygray_test", "general_circuit_board");

        ItemStack normalCircuitStack = new ItemStack(normalCircuit);
        ItemStack generalCircuitBoardStack = new ItemStack(generalCircuitBoard);
        @SuppressWarnings("unchecked")
        List<ItemStack> orderedChoices = (List<ItemStack>) prioritize(new ItemStack[]{
                normalCircuitStack, generalCircuitBoardStack
        });
        assertSame(generalCircuitBoardStack, orderedChoices.get(0));
        assertSame(normalCircuitStack, orderedChoices.get(1));
    }

    @Test
    void keepsGeneralCircuitBoardsAheadOfStoredAlternativeMaterials() throws ReflectiveOperationException {
        Item generalCircuitBoard = new Item().setTranslationKey("metaitem.general_circuit.lv")
                .setRegistryName("applygray_test", "stored_priority_general_circuit_board");
        Item storedAlternative = testItem("stored_priority_alternative");
        Item missingAlternative = testItem("missing_priority_alternative");
        ItemStack generalCircuitBoardStack = new ItemStack(generalCircuitBoard);
        ItemStack storedAlternativeStack = new ItemStack(storedAlternative);
        ItemStack missingAlternativeStack = new ItemStack(missingAlternative);
        KeyCounter storedItems = new KeyCounter();
        storedItems.add(key(storedAlternative), 1);

        @SuppressWarnings("unchecked")
        List<ItemStack> orderedChoices = (List<ItemStack>) prioritize(new ItemStack[]{
                missingAlternativeStack, storedAlternativeStack, generalCircuitBoardStack
        }, storedItems);

        assertSame(generalCircuitBoardStack, orderedChoices.get(0));
        assertSame(storedAlternativeStack, orderedChoices.get(1));
        assertSame(missingAlternativeStack, orderedChoices.get(2));
    }

    @Test
    void doesNotEncodeCircuitToGeneralCircuitBoardRecipes() throws ReflectiveOperationException {
        Item circuit = new Item().setTranslationKey("metaitem.circuit.lv")
                .setRegistryName("applygray_test", "circuit_to_general_input");
        Item generalCircuitBoard = new Item().setTranslationKey("metaitem.general_circuit.lv")
                .setRegistryName("applygray_test", "circuit_to_general_output");
        Item mufflerDust = testItem("circuit_to_general_muffler_dust");
        Recipe recipe = new Recipe(List.of(new GTRecipeItemInput(new ItemStack(circuit))),
                List.of(new ItemStack(generalCircuitBoard)), ChancedOutputList.<ItemStack,
                ChancedItemOutput>empty(), List.of(), List.of(), ChancedOutputList.<FluidStack,
                ChancedFluidOutput>empty(), List.of(new ItemStack(mufflerDust)), 20, 1, false, false,
                new RecipePropertyStorageImpl(), null);

        assertNull(encode(recipe));
    }

    @Test
    void prefersLowerInputPerNetOutputWhenRankingDynamicPatterns() {
        assertTrue(DynamicRecipePatternRegistry.compareInputOutputEfficiency(3, 3, 2, 1) < 0,
                "three inputs for three outputs should beat two inputs for one output");
        assertTrue(DynamicRecipePatternRegistry.compareInputOutputEfficiency(2, 2, 2, 1) < 0,
                "equal input counts should prefer more output");
        assertTrue(DynamicRecipePatternRegistry.compareInputOutputEfficiency(1, 1, 2, 2) < 0,
                "equal efficiency should prefer fewer total inputs");
    }

    @Test
    void rejectsARequestedOutputThatThePatternAlsoConsumes() {
        Item itemA = testItem("net_output_consumed_a");
        Item itemB = testItem("net_output_consumed_b");
        Item itemC = testItem("net_output_consumed_c");
        List<GenericStack> inputs = List.of(stack(itemA, 1), stack(itemB, 1));
        List<List<GenericStack>> alternatives = List.of(List.of(stack(itemA, 1)), List.of(stack(itemB, 1)));
        List<GenericStack> outputs = List.of(stack(itemA, 1), stack(itemC, 1));

        assertFalse(DynamicRecipePatternDetails.hasNetOutput(key(itemA), inputs, alternatives, outputs));
        assertTrue(DynamicRecipePatternDetails.hasNetOutput(key(itemC), inputs, alternatives, outputs));
    }

    @Test
    void acceptsARequestedOutputWithPositiveNetProduction() {
        Item itemA = testItem("net_output_positive_a");
        Item itemB = testItem("net_output_positive_b");
        List<GenericStack> inputs = List.of(stack(itemA, 1), stack(itemB, 1));
        List<List<GenericStack>> alternatives = List.of(List.of(stack(itemA, 1)), List.of(stack(itemB, 1)));
        List<GenericStack> outputs = List.of(stack(itemA, 2));

        assertTrue(DynamicRecipePatternDetails.hasNetOutput(key(itemA), inputs, alternatives, outputs));
        assertEquals(1, DynamicRecipePatternDetails.getNetOutputAmount(key(itemA), inputs, alternatives,
                outputs));
    }

    @Test
    void treatsAnyMatchingInputAlternativeAsConsumption() {
        Item itemA = testItem("net_output_alternative_a");
        Item itemB = testItem("net_output_alternative_b");
        List<GenericStack> inputs = List.of(stack(itemA, 1));
        List<List<GenericStack>> alternatives = List.of(List.of(stack(itemA, 1), stack(itemB, 1)));
        List<GenericStack> outputs = List.of(stack(itemA, 1));

        assertFalse(DynamicRecipePatternDetails.hasNetOutput(key(itemA), inputs, alternatives, outputs));
    }

    @Test
    void identifiesOreBackedDustsAsRecursiveCycleBoundaries() {
        assertTrue(DynamicRecipePatternRegistry.isOreBackedDust("dust", true));
        assertTrue(DynamicRecipePatternRegistry.isOreBackedDust("dustPure", true));
        assertFalse(DynamicRecipePatternRegistry.isOreBackedDust("ingot", true));
        assertFalse(DynamicRecipePatternRegistry.isOreBackedDust("dust", false));
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

    private static Item testItem(String name) {
        return new Item().setRegistryName("applygray_test", name);
    }

    private static GenericStack stack(Item item, int amount) {
        GenericStack stack = GenericStack.fromItemStack(new ItemStack(item, amount));
        assertNotNull(stack);
        return stack;
    }

    private static AEItemKey key(Item item) {
        AEItemKey key = AEItemKey.of(new ItemStack(item));
        assertNotNull(key);
        return key;
    }

    private static Object encode(Recipe recipe) throws ReflectiveOperationException {
        Method method = DynamicRecipePatternRegistry.class.getDeclaredMethod("encodeRecipe",
                DynamicRecipePatternRegistry.ProviderSnapshot.class, Recipe.class);
        method.setAccessible(true);
        return method.invoke(null, null, recipe);
    }

    private static Object prioritize(ItemStack[] choices) throws ReflectiveOperationException {
        Method method = DynamicRecipePatternRegistry.class.getDeclaredMethod("prioritizeGeneralCircuitBoards",
                ItemStack[].class);
        method.setAccessible(true);
        return method.invoke(null, (Object) choices);
    }

    private static Object prioritize(ItemStack[] choices, KeyCounter storedItems) throws ReflectiveOperationException {
        Method method = DynamicRecipePatternRegistry.class.getDeclaredMethod("prioritizeItemChoices",
                ItemStack[].class, KeyCounter.class);
        method.setAccessible(true);
        return method.invoke(null, choices, storedItems);
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
