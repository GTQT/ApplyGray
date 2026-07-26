package applygray.integration.ae2;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.chance.output.ChancedOutputList;
import gregtech.api.recipes.chance.output.ChancedOutputLogic;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtech.api.recipes.ingredients.GTRecipeFluidInput;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.ingredients.GTRecipeItemInput;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.api.recipes.properties.RecipePropertyStorageImpl;
import gregtech.api.GTValues;
import gregtech.api.GregTechAPI;
import gregtech.api.modules.IModuleManager;
import gregtech.api.unification.material.Material;
import gregtech.common.items.behaviors.ProgrammableCircuit;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import ae2.api.crafting.IPatternDetails;
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
import java.util.function.Function;

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
    void wrapsEveryNonConsumableAndIntegratedCircuitInput() throws ReflectiveOperationException {
        Item firstNonConsumable = testItem("first_non_consumable");
        Item secondNonConsumable = testItem("second_non_consumable");
        Item integratedCircuit = testItem("integrated_circuit_7");
        Item programmableCircuit = testItem("programmable_circuit");
        Item output = testItem("multiple_circuit_output");
        Recipe recipe = new Recipe(List.of(
                new GTRecipeItemInput(new ItemStack(firstNonConsumable)).setNonConsumable(),
                new GTRecipeItemInput(new ItemStack(secondNonConsumable)).setNonConsumable(),
                new TestIntCircuitIngredient(new ItemStack(integratedCircuit))),
                List.of(new ItemStack(output)), ChancedOutputList.<ItemStack, ChancedItemOutput>empty(),
                List.of(), List.of(), ChancedOutputList.<FluidStack, ChancedFluidOutput>empty(),
                List.of(new ItemStack(firstNonConsumable)), 20, 1, false, false,
                new RecipePropertyStorageImpl(), null);
        Function<ItemStack, ItemStack> circuitWrapper = wrapped -> {
            ItemStack programmable = new ItemStack(programmableCircuit);
            return ProgrammableCircuit.wrap(wrapped, programmable);
        };

        Object encoded = encode(recipe, circuitWrapper);
        assertNotNull(encoded);
        assertEquals(-1, readField(encoded, "circuitConfiguration"),
                "integrated circuits must be represented by their programmable-circuit input");

        @SuppressWarnings("unchecked")
        List<GenericStack> inputs = (List<GenericStack>) readField(encoded, "inputs");
        assertEquals(3, inputs.size(), "both NC inputs and the integrated circuit must be retained");
        assertTrue(inputs.stream().allMatch(DynamicRecipePatternRegistryTest::hasWrappedCircuitData),
                "every virtual circuit requirement must be visible as its own programmable circuit");
        List<String> wrappedIds = inputs.stream()
                .map(DynamicRecipePatternRegistryTest::wrappedCircuitId)
                .toList();
        assertTrue(wrappedIds.containsAll(List.of(
                        "applygray_test:first_non_consumable",
                        "applygray_test:second_non_consumable",
                        "applygray_test:integrated_circuit_7")),
                "the three wrappers must retain both NC targets and the integrated-circuit target");
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
    void keepsArcFurnaceIngotRoutesAheadOfTargetMaterialFluidCasting() {
        Material annealedCopper = new TestMaterial("annealed_copper");
        Material oxygen = new ElementalTestMaterial();
        Material processFluid = new TestMaterial("process_fluid");

        assertFalse(DynamicRecipePatternRegistry.isPriorityFluidInput(annealedCopper, annealedCopper),
                "molten target material must be treated as a form change");
        assertFalse(DynamicRecipePatternRegistry.isPriorityFluidInput(annealedCopper, oxygen),
                "elemental oxygen must not hide the Arc Furnace ingot input");
        assertTrue(DynamicRecipePatternRegistry.isPriorityFluidInput(annealedCopper, processFluid),
                "unrelated process fluids must retain fluid-route priority");
        boolean hydrogenAndFluorineArePrimary = DynamicRecipePatternRegistry.isPrimaryElementalFluidRoute(
                true, false, false, false);
        assertTrue(hydrogenAndFluorineArePrimary,
                "elemental fluids must remain raw inputs when they are the entire reaction");
        assertFalse(DynamicRecipePatternRegistry.isPrimaryElementalFluidRoute(true, false, true, false),
                "oxygen beside an ingot must remain an Arc Furnace auxiliary");
        assertFalse(DynamicRecipePatternRegistry.isPrimaryElementalFluidRoute(true, false, false, true),
                "elemental fluid beside the target material must remain a form-change auxiliary");

        DynamicRecipePatternRegistry.CandidateRoutePriority arcFurnace =
                DynamicRecipePatternRegistry.classifyCandidateRoute(false, false, true, true, false);
        DynamicRecipePatternRegistry.CandidateRoutePriority fluidCasting =
                DynamicRecipePatternRegistry.classifyCandidateRoute(false, false, false, true, true);
        DynamicRecipePatternRegistry.CandidateRoutePriority chemicalBath =
                DynamicRecipePatternRegistry.classifyCandidateRoute(false, true, false, true, false);
        DynamicRecipePatternRegistry.CandidateRoutePriority hydrofluoricAcid =
                DynamicRecipePatternRegistry.classifyCandidateRoute(false, hydrogenAndFluorineArePrimary, false,
                        true, false);

        assertEquals(DynamicRecipePatternRegistry.CandidateRoutePriority.INGOT_INPUT, arcFurnace);
        assertEquals(DynamicRecipePatternRegistry.CandidateRoutePriority.MATERIAL_FORM_CHANGE, fluidCasting);
        assertEquals(DynamicRecipePatternRegistry.CandidateRoutePriority.DUST_OR_FLUID_INPUT, chemicalBath);
        assertEquals(DynamicRecipePatternRegistry.CandidateRoutePriority.DUST_OR_FLUID_INPUT, hydrofluoricAcid);
        assertTrue(DynamicRecipePatternRegistry.compareCandidateRoutePriority(arcFurnace, fluidCasting) < 0,
                "copper ingot plus oxygen must rank ahead of molten annealed copper casting");
        assertTrue(DynamicRecipePatternRegistry.compareCandidateRoutePriority(chemicalBath, fluidCasting) < 0,
                "carbon fiber plus epoxy chemical bath must rank ahead of reinforced epoxy resin casting");
    }

    @Test
    void retainsRoutePriorityWhenDynamicPatternsAreSortedForCrafting() {
        assertTrue(DynamicRecipePatternRegistry.compareDynamicPatternPriority(
                        DynamicRecipePatternRegistry.CandidateRoutePriority.DUST_OR_FLUID_INPUT,
                        GTValues.M * 2, 1, 1, "dust_route",
                        DynamicRecipePatternRegistry.CandidateRoutePriority.MATERIAL_FORM_CHANGE,
                        GTValues.M, 1, 1, "form_change") < 0,
                "the planner-facing ordering must preserve the dust/fluid route ahead of a cheaper form change");
    }

    @Test
    void invalidatesCachedPatternsWhenTheirInputsOrCircuitMetadataChange() {
        GenericStack oldNonConsumable = stack(testItem("cached_non_consumable"), 1);
        GenericStack wrappedNonConsumable = stack(testItem("wrapped_programmable_circuit"), 1);
        GenericStack output = stack(testItem("cached_pattern_output"), 1);
        List<GenericStack> inputs = List.of(oldNonConsumable);
        List<List<GenericStack>> alternatives = List.of(List.of(oldNonConsumable));
        DynamicRecipePatternDetails cached = new DynamicRecipePatternDetails("test:cached", "chemical",
                inputs, alternatives, List.of(output), -1, GTValues.M, 1,
                DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL);

        assertTrue(cached.matchesRecipeDefinition("chemical", inputs, alternatives, List.of(output), -1,
                GTValues.M, 1, DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL));
        assertFalse(cached.matchesRecipeDefinition("chemical", List.of(wrappedNonConsumable),
                List.of(List.of(wrappedNonConsumable)), List.of(output), -1, GTValues.M, 1,
                DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL),
                "a cached raw NC input must be replaced by its programmable-circuit representation");
        assertFalse(cached.matchesRecipeDefinition("chemical", inputs, alternatives, List.of(output), 1,
                GTValues.M, 1, DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL),
                "circuit metadata changes must also invalidate the cached pattern");
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
        @SuppressWarnings("unchecked")
        Map<Object, Object> rejectedPatterns = (Map<Object, Object>) readField(state, "rejectedRecipeKeysByTarget");
        AEItemKey originalTarget = key(Items.GOLD_INGOT);
        AEItemKey dependencyTarget = key(Items.IRON_INGOT);
        AEItemKey unrelatedTarget = key(Items.EMERALD);
        IPatternDetails rootPlanPattern = pattern(stack(Items.IRON_INGOT, 1), stack(Items.GOLD_INGOT, 1));
        outputIndexes.put("test", "test");
        targetPatterns.put(originalTarget, List.of());
        targetPatterns.put(dependencyTarget, List.of());
        targetPatterns.put(unrelatedTarget, List.of());
        registeredPatterns.put("unrelated_pattern", "unrelated_pattern");
        rejectedPatterns.put(originalTarget, java.util.Set.of("root_rejected_pattern"));
        rejectedPatterns.put(dependencyTarget, java.util.Set.of("dependency_rejected_pattern"));
        rejectedPatterns.put(unrelatedTarget, java.util.Set.of("unrelated_rejected_pattern"));

        Method invalidate = gridState.getDeclaredMethod("invalidatePlanPatternsAndRecipeOutputIndexes",
                AEKey.class, Collection.class);
        invalidate.setAccessible(true);
        assertEquals(0, (int) invalidate.invoke(state, originalTarget, List.of(rootPlanPattern)));
        assertTrue(outputIndexes.isEmpty());
        assertEquals(1, targetPatterns.size(),
                "rebuild must not disturb cached targets outside the original request's dependency chain");
        assertEquals(1, registeredPatterns.size(),
                "rebuild must not remove dynamic patterns outside the original request's dependency chain");
        assertEquals(1, rejectedPatterns.size(),
                "rebuild must not clear recursive rejections outside the original request's dependency chain");
        assertFalse(targetPatterns.containsKey(originalTarget),
                "rebuild must clear the original target's cached candidate lookup");
        assertFalse(targetPatterns.containsKey(dependencyTarget),
                "rebuild must clear cached candidates reachable from the original request");
        assertTrue(targetPatterns.containsKey(unrelatedTarget),
                "rebuild must preserve candidates that are not reachable from the original request");
        assertTrue(registeredPatterns.containsKey("unrelated_pattern"));
        assertFalse(rejectedPatterns.containsKey(originalTarget),
                "rebuild must clear recursive rejections for the original target");
        assertFalse(rejectedPatterns.containsKey(dependencyTarget),
                "rebuild must clear recursive rejections for reachable dependencies");
        assertTrue(rejectedPatterns.containsKey(unrelatedTarget),
                "rebuild must preserve recursive rejections outside the original request's dependency chain");
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
    void bindsAnOptimalRebuildToItsOriginalRequestedOutput() throws ReflectiveOperationException {
        Class<?> gridState = Class.forName("applygray.integration.ae2.DynamicRecipePatternRegistry$GridState");
        var constructor = gridState.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();

        AEItemKey originalTarget = key(Items.GOLD_INGOT);
        AEItemKey otherTarget = key(Items.IRON_INGOT);
        Method arm = gridState.getDeclaredMethod("armOptimalRebuild", AEKey.class, long.class);
        Method claim = gridState.getDeclaredMethod("claimOptimalRebuild", AEKey.class, long.class);
        arm.setAccessible(true);
        claim.setAccessible(true);

        arm.invoke(state, originalTarget, 64L);
        assertNull(claim.invoke(state, otherTarget, 64L),
                "a concurrent calculation for another output must not consume the root rebuild");
        assertNull(claim.invoke(state, originalTarget, 1L),
                "a calculation with a different requested amount must not consume the root rebuild");
        assertNotNull(claim.invoke(state, originalTarget, 64L),
                "the calculation for the original request must consume the rebuild");
        assertNull(claim.invoke(state, originalTarget, 64L),
                "the rebuild session must only be claimed once");
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
    void treatsOreProcessingPrefixesAsExternalInputs() {
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("ore"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("oreNetherrack"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("oreBlackgranite"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("rawOre"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("rawOreCopper"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("crushed"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("crushedPurified"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("crushedCentrifuged"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("dustImpure"));
        assertTrue(DynamicRecipePatternRegistry.isOreInputPrefix("dustPure"));
        assertFalse(DynamicRecipePatternRegistry.isOreInputPrefix("dust"));
        assertFalse(DynamicRecipePatternRegistry.isOreInputPrefix("ingot"));
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
    void exposesTheRequestedOutputOfRecipesWithDeterministicByproducts() {
        GenericStack mainOutput = stack(Items.GOLD_INGOT, 1);
        GenericStack byproduct = stack(Items.REDSTONE, 1);
        List<GenericStack> outputs = List.of(mainOutput, byproduct);

        assertEquals(List.of(mainOutput),
                DynamicRecipePatternRegistry.selectRequestedPatternOutputs(key(Items.GOLD_INGOT), outputs));
        assertEquals(List.of(byproduct),
                DynamicRecipePatternRegistry.selectRequestedPatternOutputs(key(Items.REDSTONE), outputs));
    }

    @Test
    void givesEachRequestedOutputItsOwnDynamicPatternCacheKey() {
        String baseRecipeKey = "test:recipe_map:42";

        assertFalse(DynamicRecipePatternRegistry.createTargetedRecipeKey(baseRecipeKey, key(Items.GOLD_INGOT))
                .equals(DynamicRecipePatternRegistry.createTargetedRecipeKey(baseRecipeKey, key(Items.REDSTONE))));
    }

    @Test
    void treatsGregTechElementsAsRecursiveLeaves() {
        assertTrue(DynamicRecipePatternRegistry.isElementalMaterial(new ElementalTestMaterial()));
        assertFalse(DynamicRecipePatternRegistry.isElementalMaterial(null));
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

    private static final class ElementalTestMaterial extends Material {

        private ElementalTestMaterial() {
            super(new ResourceLocation("applygray_test", "elemental_leaf"));
        }

        @Override
        public boolean isElement() {
            return true;
        }
    }

    private static final class TestMaterial extends Material {

        private TestMaterial(String name) {
            super(new ResourceLocation("applygray_test", name));
        }
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

    private static IPatternDetails pattern(GenericStack input, GenericStack output) {
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return null;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[]{new IInput() {
                    @Override
                    public GenericStack[] possibleInputs() {
                        return new GenericStack[]{input};
                    }

                    @Override
                    public long getMultiplier() {
                        return input.amount();
                    }

                    @Override
                    public boolean isValid(AEKey candidate, net.minecraft.world.World level) {
                        return input.what().equals(candidate);
                    }

                    @Override
                    public AEKey getRemainingKey(AEKey template) {
                        return null;
                    }
                }};
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of(output);
            }
        };
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

    private static Object encode(Recipe recipe, Function<ItemStack, ItemStack> programmableCircuitFactory)
            throws ReflectiveOperationException {
        Method method = DynamicRecipePatternRegistry.class.getDeclaredMethod("encodeRecipe",
                Recipe.class, KeyCounter.class, Function.class);
        method.setAccessible(true);
        return method.invoke(null, recipe, null, programmableCircuitFactory);
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

    private static boolean hasWrappedCircuitData(GenericStack stack) {
        if (!(stack.what() instanceof AEItemKey itemKey)) {
            return false;
        }
        ItemStack itemStack = itemKey.toStack(1);
        return itemStack.hasTagCompound() && itemStack.getTagCompound().hasKey("targetItem");
    }

    private static String wrappedCircuitId(GenericStack stack) {
        AEItemKey itemKey = (AEItemKey) stack.what();
        return itemKey.toStack(1).getTagCompound().getCompoundTag("targetItem").getString("string_id");
    }

    private static final class TestIntCircuitIngredient extends IntCircuitIngredient {

        private final ItemStack circuit;

        private TestIntCircuitIngredient(ItemStack circuit) {
            super(0);
            this.circuit = circuit;
        }

        @Override
        public ItemStack[] getInputStacks() {
            return new ItemStack[]{circuit};
        }
    }
}
