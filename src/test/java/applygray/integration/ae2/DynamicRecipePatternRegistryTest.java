package applygray.integration.ae2;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.chance.output.ChancedOutputList;
import gregtech.api.recipes.chance.output.ChancedOutputLogic;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtech.api.recipes.ingredients.GTRecipeFluidInput;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.ingredients.GTRecipeItemInput;
import gregtech.api.recipes.properties.RecipePropertyStorageImpl;
import gregtech.api.GTValues;
import gregtech.api.GregTechAPI;
import gregtech.api.modules.IModuleManager;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
    void ranksDustAndFluidInputsBeforeIngotsAndRecycling() {
        assertTrue(DynamicRecipePatternRegistry.compareCandidateRoutePriority(
                        DynamicRecipePatternRegistry.CandidateRoutePriority.DUST_OR_FLUID_INPUT,
                        DynamicRecipePatternRegistry.CandidateRoutePriority.INGOT_INPUT) < 0,
                "dust and fluid routes must rank before ingot routes");
        assertTrue(DynamicRecipePatternRegistry.compareCandidateRoutePriority(
                        DynamicRecipePatternRegistry.CandidateRoutePriority.INGOT_INPUT,
                        DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL) < 0,
                "ingot routes must rank before general routes");
        assertTrue(DynamicRecipePatternRegistry.compareCandidateRoutePriority(
                        DynamicRecipePatternRegistry.CandidateRoutePriority.MATERIAL_FORM_CHANGE,
                        DynamicRecipePatternRegistry.CandidateRoutePriority.RECYCLING) < 0,
                "recycling must remain the final fallback");
    }

    @Test
    void recognizesDustIngotAndRecyclingRouteMarkers() {
        assertTrue(DynamicRecipePatternRegistry.isDustPrefix("dust"));
        assertTrue(DynamicRecipePatternRegistry.isDustPrefix("dustTiny"));
        assertTrue(DynamicRecipePatternRegistry.isIngotPrefix("ingot"));
        assertTrue(DynamicRecipePatternRegistry.isIngotPrefix("ingotHot"));
        assertFalse(DynamicRecipePatternRegistry.isIngotPrefix("screw"));
        assertTrue(DynamicRecipePatternRegistry.isRecyclingRecipeCategoryName("extractor_recycling"));
        assertFalse(DynamicRecipePatternRegistry.isRecyclingRecipeCategoryName("alloy_blast"));
    }

    @Test
    void measuresUnifiedItemsByContainedMaterialInsteadOfStackCount() {
        assertEquals(GTValues.M * 9,
                DynamicRecipePatternRegistry.estimateItemRawMaterialCost(GTValues.M, 9),
                "nine dusts must contain the same material amount as one dense plate");
        assertEquals(GTValues.M * 9,
                DynamicRecipePatternRegistry.estimateItemRawMaterialCost(GTValues.M * 9, 1),
                "a dense plate must contribute all nine material units to its recipe cost");
    }

    @Test
    void rebuildInvalidationClearsRecipeOutputIndexes() throws ReflectiveOperationException {
        Class<?> gridState = Class.forName("applygray.integration.ae2.DynamicRecipePatternRegistry$GridState");
        var constructor = gridState.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();

        @SuppressWarnings("unchecked")
        Map<Object, Object> outputIndexes = (Map<Object, Object>) readField(state, "recipeOutputIndexes");
        @SuppressWarnings("unchecked")
        Map<Object, Object> targetPatterns = (Map<Object, Object>) readField(state, "patternsByTarget");
        @SuppressWarnings("unchecked")
        Map<Object, Object> registeredPatterns = (Map<Object, Object>) readField(state, "patternsByRecipe");
        outputIndexes.put("test", "test");
        targetPatterns.put("unselected_target", List.of("unselected_pattern"));
        registeredPatterns.put("unselected_pattern", "unselected_pattern");

        Method invalidate = gridState.getDeclaredMethod("invalidatePlanPatternsAndRecipeOutputIndexes",
                Collection.class);
        invalidate.setAccessible(true);
        assertEquals(0, (int) invalidate.invoke(state, List.of()));
        assertTrue(outputIndexes.isEmpty());
        assertEquals(1, targetPatterns.size(), "rebuild must not remove an unselected dynamic target cache");
        assertEquals(1, registeredPatterns.size(), "rebuild must not remove an unselected dynamic pattern");
        assertTrue(((Number) readField(state, "pendingFullRecipeOutputIndexEpoch")).longValue() > 0,
                "the next calculation must eagerly rebuild every active RecipeMap output index");

        Method ensureFullRebuild = gridState.getDeclaredMethod("ensureFullRecipeOutputIndexRebuild");
        ensureFullRebuild.setAccessible(true);
        assertFalse((boolean) ensureFullRebuild.invoke(state),
                "a non-calculation thread must not consume ApplyGray's pending optimal rebuild");
        assertTrue(((Number) readField(state, "pendingFullRecipeOutputIndexEpoch")).longValue() > 0,
                "the pending optimal rebuild must remain for the calculation started by ApplyGray");
    }

    @Test
    void keepsOptimalRebuildSessionUntilTheCraftingTaskEnds() throws ReflectiveOperationException {
        Field activeOptimalRebuild = DynamicRecipePatternRegistry.class.getDeclaredField("ACTIVE_OPTIMAL_REBUILD");
        activeOptimalRebuild.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> session = (ThreadLocal<Object>) activeOptimalRebuild.get(null);

        Class<?> context = Class.forName(
                "applygray.integration.ae2.DynamicRecipePatternRegistry$OptimalRebuildContext");
        var constructor = context.getDeclaredConstructor(int.class, int.class, long.class, long.class);
        constructor.setAccessible(true);
        Object expected = constructor.newInstance(1, 2, 3L, System.nanoTime());

        session.set(expected);
        try {
            DynamicRecipePatternRegistry.leaveCraftingCalculation(null);
            assertSame(expected, session.get(),
                    "a failed attempt must preserve optimal rebuild state for its recovery calculation");

            DynamicRecipePatternRegistry.finishCraftingCalculationSession();
            assertNull(session.get(), "the state must be released after the outer crafting task finishes");
        } finally {
            session.remove();
        }
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

    @Test
    void treatsEveryOrePrefixAsAnExternalInput() {
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("ore"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("oreNetherrack"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("oreBlackgranite"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("rawOre"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("rawOreCopper"));
        assertFalse(DynamicRecipePatternRegistry.isOreInputPrefix("dust"));
        assertFalse(DynamicRecipePatternRegistry.isOreInputPrefix("crushed"));
    }

    @Test
    void doesNotEncodeRecipesThatConsumeOreInputs() throws ReflectiveOperationException {
        Item ore = Items.DIAMOND;
        Item dust = Items.REDSTONE;
        OreDictionary.registerOre("oreDynamicPatternTest", new ItemStack(ore));

        assertNull(encode(createSingleItemRecipe(ore, dust)));
    }

    @Test
    void doesNotEncodeRecipesWithAnOreInputAlternative() throws ReflectiveOperationException {
        Item ore = Items.EMERALD;
        Item nonOreAlternative = Items.IRON_INGOT;
        Item dust = Items.REDSTONE;
        OreDictionary.registerOre("oreDynamicPatternAlternativeTest", new ItemStack(ore));

        assertNull(encode(createAlternativeItemRecipe(nonOreAlternative, ore, dust)));
    }

    @Test
    void exposesOnlyThePrimaryOutputOfRecipesWithDeterministicByproducts() {
        GenericStack mainOutput = stack(Items.GOLD_INGOT, 1);
        GenericStack byproduct = stack(Items.REDSTONE, 1);
        List<GenericStack> outputs = List.of(mainOutput, byproduct);

        assertEquals(List.of(mainOutput),
                DynamicRecipePatternRegistry.selectPrimaryPatternOutputs(key(Items.GOLD_INGOT), outputs));
        assertTrue(DynamicRecipePatternRegistry.selectPrimaryPatternOutputs(key(Items.REDSTONE), outputs).isEmpty());
    }

    @Test
    void doesNotEncodeRecipesWithChancedOutputs() throws ReflectiveOperationException {
        assertNull(encode(createChancedOutputRecipe(Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE)));
    }

    @Test
    void indexesRecipesByTheirDeterministicOutputs() throws ReflectiveOperationException {
        Item matchingInput = testItem("indexed_matching_input");
        Item matchingOutput = testItem("indexed_matching_output");
        Item unrelatedInput = testItem("indexed_unrelated_input");
        Item unrelatedOutput = testItem("indexed_unrelated_output");
        Recipe matching = createSingleItemRecipe(matchingInput, matchingOutput);
        Recipe unrelated = createSingleItemRecipe(unrelatedInput, unrelatedOutput);

        Object outputIndex = createOutputIndex(List.of(matching, unrelated));

        assertEquals(List.of(matching), getIndexedRecipes(outputIndex, key(matchingOutput)));
        assertTrue(getIndexedRecipes(outputIndex, key(unrelatedInput)).isEmpty());
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

    private static Recipe createSingleItemRecipe(Item input, Item output) {
        return new Recipe(List.of(new GTRecipeItemInput(new ItemStack(input))),
                List.of(new ItemStack(output)), ChancedOutputList.<ItemStack, ChancedItemOutput>empty(),
                List.of(), List.of(), ChancedOutputList.<FluidStack, ChancedFluidOutput>empty(),
                List.of(new ItemStack(input)), 20, 1, false, false, new RecipePropertyStorageImpl(), null);
    }

    private static Recipe createAlternativeItemRecipe(Item firstInput, Item secondInput, Item output) {
        return new Recipe(List.of(new GTRecipeItemInput(new ItemStack(firstInput), new ItemStack(secondInput))),
                List.of(new ItemStack(output)), ChancedOutputList.<ItemStack, ChancedItemOutput>empty(),
                List.of(), List.of(), ChancedOutputList.<FluidStack, ChancedFluidOutput>empty(),
                List.of(new ItemStack(firstInput)), 20, 1, false, false, new RecipePropertyStorageImpl(), null);
    }

    private static Recipe createChancedOutputRecipe(Item input, Item mainOutput, Item chancedOutput) {
        return new Recipe(List.of(new GTRecipeItemInput(new ItemStack(input))), List.of(new ItemStack(mainOutput)),
                new ChancedOutputList<>(ChancedOutputLogic.OR,
                        List.of(new ChancedItemOutput(new ItemStack(chancedOutput), 5000, 0))),
                List.of(), List.of(), ChancedOutputList.<FluidStack, ChancedFluidOutput>empty(),
                List.of(new ItemStack(input)), 20, 1, false, false, new RecipePropertyStorageImpl(), null);
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

    private static Object createOutputIndex(Collection<Recipe> recipes) throws ReflectiveOperationException {
        Class<?> outputIndex = Class.forName(
                "applygray.integration.ae2.DynamicRecipePatternRegistry$RecipeOutputIndex");
        Method method = outputIndex.getDeclaredMethod("create", Collection.class);
        method.setAccessible(true);
        return method.invoke(null, recipes);
    }

    @SuppressWarnings("unchecked")
    private static List<Recipe> getIndexedRecipes(Object outputIndex, AEItemKey target)
            throws ReflectiveOperationException {
        Method method = outputIndex.getClass().getDeclaredMethod("getRecipes", AEKey.class);
        method.setAccessible(true);
        return (List<Recipe>) method.invoke(outputIndex, target);
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
