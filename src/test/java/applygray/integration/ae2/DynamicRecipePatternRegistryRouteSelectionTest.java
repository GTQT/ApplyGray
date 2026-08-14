package applygray.integration.ae2;

import applygray.integration.ae2.rules.PlanningMode;
import applygray.integration.ae2.rules.CyclePolicy;
import applygray.integration.ae2.rules.PlanningBudget;
import ae2.integration.data.CraftingTreeStackRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRecipePatternRegistryRouteSelectionTest {

    @Test
    void resourceFirstPrefersDependencyRouteCostOverStaticRecipeCost() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareRouteAndStaticCost(
                PlanningMode.RESOURCE_FIRST, -1, 1));
    }

    @Test
    void stockFirstPrefersDependencyRouteCostOverStaticRecipeCost() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareRouteAndStaticCost(
                PlanningMode.STOCK_FIRST, -1, 1));
    }

    @Test
    void throughputFirstRetainsStaticRecipePriority() {
        assertEquals(1, DynamicRecipePatternRegistry.compareRouteAndStaticCost(
                PlanningMode.THROUGHPUT_FIRST, -1, 1));
    }

    @Test
    void staticCostBreaksEqualResourceRouteCost() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareRouteAndStaticCost(
                PlanningMode.RESOURCE_FIRST, 0, -1));
    }

    @Test
    void rawMaterialLeafIsNotAnUnresolvedIntermediate() {
        assertEquals(0, DynamicRecipePatternRegistry.directInputUnresolvedPenalty(1, false, true));
        assertEquals(1, DynamicRecipePatternRegistry.directInputUnresolvedPenalty(1, false, false));
    }

    @Test
    void nonConsumableControlTokenTerminatesOnlyTheScoringDependency() {
        assertTrue(DynamicRecipePatternRegistry.isRouteDependencyLeaf(false, true));
        assertFalse(DynamicRecipePatternRegistry.isRouteDependencyLeaf(false, false));
    }

    @Test
    void completeRouteBeatsCheaperButUnexpandedLowerBound() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareRouteCompletenessAndMaterials(
                0, 5, 1, 1));
    }

    @Test
    void rawMaterialLeafBeatsAnEquallySizedUnresolvedIntermediate() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareRouteCompletenessAndMaterials(
                0, 0, 1, 0, 1, 1));
    }

    @Test
    void directSolidFormBeatsASeeminglyCheaperIndirectRecoveryRoute() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareRouteCompletenessSafetyFormAndMaterials(
                0, 0, 0, 0, 8, 0, 0, 0, 3, 1));
    }

    @Test
    void sameMaterialSolidInputIsNotAHighPriorityMaterialSource() {
        assertFalse(DynamicRecipePatternRegistry.isPrioritySolidMaterialInput(true, true));
        assertTrue(DynamicRecipePatternRegistry.isPrioritySolidMaterialInput(true, false));
        assertFalse(DynamicRecipePatternRegistry.isPrioritySolidMaterialInput(false, false));
    }

    @Test
    void solidMaterialFormCostUsesTheCanonicalMaterialHierarchy() {
        assertEquals(0, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, false, true, false,
                "ingot"));
        assertEquals(1, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, false, true, false,
                "dust"));
        assertEquals(1, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, false, true, true,
                null));
        assertEquals(2, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, false, true, false,
                "plate"));
        assertEquals(0, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, true, false, true, false,
                "dust"));
        assertEquals(2, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, true, false, true, true,
                null));
        assertEquals(3, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, false, false, false,
                "ingot"));
    }

    @Test
    void fluidRecoveryFormCostUsesCanonicalShapesAndDefersProcessedStock() {
        assertEquals(0, DynamicRecipePatternRegistry.fluidMaterialRecoveryInputFormCost(
                true, true, true, "ingot"));
        assertEquals(1, DynamicRecipePatternRegistry.fluidMaterialRecoveryInputFormCost(
                true, true, true, "dust"));
        assertEquals(8, DynamicRecipePatternRegistry.fluidMaterialRecoveryInputFormCost(
                true, true, true, "screw"));
        assertEquals(8, DynamicRecipePatternRegistry.fluidMaterialRecoveryInputFormCost(
                true, true, true, "bolt"));
        assertEquals(8, DynamicRecipePatternRegistry.fluidMaterialRecoveryInputFormCost(
                true, true, true, "round"));
        assertEquals(0, DynamicRecipePatternRegistry.fluidMaterialRecoveryInputFormCost(
                true, false, true, "ingot"));
    }

    @Test
    void routeComparisonAmountUsesLargestPositiveRootBatch() {
        assertEquals(144, DynamicRecipePatternRegistry.routeComparisonAmount(
                List.of(16L, 144L, 0L), Long::longValue));
        assertEquals(1, DynamicRecipePatternRegistry.routeComparisonAmount(
                List.of(0L, -1L), Long::longValue));
    }

    @Test
    void chemicallySynthesizedProcessedSolidContinuesItsFluidRouteBeforeIngotAndDust() {
        int directIngotForm = DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, true,
                true, false, "ingot");
        int directDustForm = DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, true,
                true, false, "dust");
        int directFluidForm = DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, true,
                true, true, null);
        int directProcessedForm = DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, true,
                true, false, "plate");
        int directChemicalSource = DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, false, true,
                false, false, null);

        assertEquals(5, directIngotForm);
        assertEquals(6, directDustForm);
        assertEquals(4, directFluidForm);
        assertEquals(7, directProcessedForm);
        assertEquals(3, directChemicalSource);
        assertTrue(directIngotForm > directChemicalSource);
        assertTrue(directDustForm > directChemicalSource);
        assertTrue(directFluidForm > directChemicalSource);
        assertTrue(directProcessedForm > directChemicalSource);
        assertTrue(directFluidForm < directIngotForm);
        assertTrue(directIngotForm < directDustForm);
        assertTrue(directDustForm < directProcessedForm);
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                true, directFluidForm, directIngotForm, false, false, false));
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                true, directIngotForm, directDustForm, false, false, false));
    }

    @Test
    void directScoreRetainsChemicalProductFormPenaltyBeforeAvailableFallbackInputs() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareDirectRouteCost(
                0, 3, 2, 8, 0,
                0, 4, 0, 0, 1));
    }

    @Test
    void onlyProcessedSolidMaterialFormsReceiveTheIngotPreference() {
        assertTrue(DynamicRecipePatternRegistry.isProcessedSolidMaterialForm(true, true, "plate"));
        assertTrue(DynamicRecipePatternRegistry.isProcessedSolidMaterialForm(true, true, "foil"));
        assertFalse(DynamicRecipePatternRegistry.isProcessedSolidMaterialForm(true, true, "dust"));
        assertFalse(DynamicRecipePatternRegistry.isProcessedSolidMaterialForm(true, true, "ingot"));
        assertFalse(DynamicRecipePatternRegistry.isProcessedSolidMaterialForm(false, true, "plate"));
    }

    @Test
    void primaryCompoundSynthesisAllowsBasicFluidAtomSourcesButNotCompoundDetours() {
        assertTrue(DynamicRecipePatternRegistry.isPrimaryCompoundSynthesis(true, false,
                false, true, true, 1));
        assertFalse(DynamicRecipePatternRegistry.isPrimaryCompoundSynthesis(true, false,
                false, true, false, 1));
        assertFalse(DynamicRecipePatternRegistry.isPrimaryCompoundSynthesis(true, false,
                false, true, true, 0));
    }

    @Test
    void standaloneGenerationPrefersPrimaryCompoundSynthesisOverCompoundRecovery() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandalonePrimaryCompoundSynthesis(true, false));
        assertEquals(1, DynamicRecipePatternRegistry.compareStandalonePrimaryCompoundSynthesis(false, true));
        assertEquals(0, DynamicRecipePatternRegistry.compareStandalonePrimaryCompoundSynthesis(true, true));
    }

    @Test
    void standaloneGenerationKeepsRecoveryBehindAnEquallyDirectMaterialSource() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneRecyclingRoute(false, true));
        assertEquals(1, DynamicRecipePatternRegistry.compareStandaloneRecyclingRoute(true, false));
        assertEquals(0, DynamicRecipePatternRegistry.compareStandaloneRecyclingRoute(false, false));
    }

    @Test
    void standaloneGenerationKeepsCanonicalSolidSourceAheadOfRecursiveRouteCost() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                true, 0, 2, false, false, false));
        assertEquals(1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                true, 2, 0, false, false, false));
    }

    @Test
    void standaloneGenerationPrefersDeclaredIngotTransformationOverTargetDustFallback() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneDeclaredIngotTransformation(
                true, true, false));
        assertEquals(1, DynamicRecipePatternRegistry.compareStandaloneDeclaredIngotTransformation(
                true, false, true));
        assertEquals(0, DynamicRecipePatternRegistry.compareStandaloneDeclaredIngotTransformation(
                false, true, false));
    }

    @Test
    void standaloneGenerationKeepsDirectPolymerSynthesisAheadOfPowderExtraction() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                false, 0, 0, true, true, false));
        assertEquals(1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                false, 0, 0, true, false, true));
        assertEquals(0, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                false, 0, 0, false, true, false));
    }

    @Test
    void incompleteChemicalBathKeepsItsFairRefinementAheadOfPowderFallback() {
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneIncompleteChemicalBath(
                true, true, true, true, false, false, false));
        assertEquals(1, DynamicRecipePatternRegistry.compareStandaloneIncompleteChemicalBath(
                true, false, false, false, true, true, true));
        assertEquals(0, DynamicRecipePatternRegistry.compareStandaloneIncompleteChemicalBath(
                false, true, true, true, false, false, false));
        assertEquals(0, DynamicRecipePatternRegistry.compareStandaloneIncompleteChemicalBath(
                true, true, false, true, false, false, false));
    }

    @Test
    void fairRefinementStopsOnlyAfterRepeatedIdenticalFrontiers() {
        assertTrue(DynamicRecipePatternRegistry.shouldContinueFairRouteRefinement(true, 0));
        assertTrue(DynamicRecipePatternRegistry.shouldContinueFairRouteRefinement(true, 1));
        assertFalse(DynamicRecipePatternRegistry.shouldContinueFairRouteRefinement(true, 2));
        assertFalse(DynamicRecipePatternRegistry.shouldContinueFairRouteRefinement(false, 0));
    }

    @Test
    void standaloneDeadlineKeepsMaterializingTheSelectedRoute() {
        assertEquals(DynamicRecipePatternRegistry.StandaloneTreeMaterializationStep.REFINED,
                DynamicRecipePatternRegistry.selectStandaloneTreeMaterializationStep(62, 4096, false));
        assertEquals(DynamicRecipePatternRegistry.StandaloneTreeMaterializationStep.FAST_CONTINUATION,
                DynamicRecipePatternRegistry.selectStandaloneTreeMaterializationStep(62, 4096, true));
        assertEquals(DynamicRecipePatternRegistry.StandaloneTreeMaterializationStep.STOP,
                DynamicRecipePatternRegistry.selectStandaloneTreeMaterializationStep(4096, 4096, true));
    }

    @Test
    void standaloneDeadlineStillAllowsBoundedCandidateEnumeration() {
        assertFalse(DynamicRecipePatternRegistry.shouldStopCandidateEnumeration(true, true));
        assertTrue(DynamicRecipePatternRegistry.shouldStopCandidateEnumeration(true, false));
        assertFalse(DynamicRecipePatternRegistry.shouldStopCandidateEnumeration(false, false));
    }

    @Test
    void standalonePreviewUsesItsOwnConfiguredCapacityUntilTheProtocolLimit() {
        assertEquals(4096, DynamicRecipePatternRegistry.getPatternGenerationTreeNodeLimit(PlanningBudget.DEFAULT));

        PlanningBudget.Builder builder = PlanningBudget.builder();
        builder.maxStandaloneRouteExpansionsPerCalculation(16_384, 0, "test");
        assertEquals(CraftingTreeStackRegistry.MAX_TREE_NODES,
                DynamicRecipePatternRegistry.getPatternGenerationTreeNodeLimit(builder.build()));
    }

    @Test
    void hotIngotUsesDustAsItsCanonicalInputForm() {
        assertTrue(DynamicRecipePatternRegistry.isIngotPrefix("ingotHot"));
        assertEquals(0, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, true, false,
                true, false, "dust"));
        assertEquals(1, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, true, false,
                true, false, "ingotHot"));
        assertEquals(2, DynamicRecipePatternRegistry.solidMaterialInputFormCost(true, true, false,
                true, true, null));
        assertEquals(-1, DynamicRecipePatternRegistry.compareStandaloneSourcePreference(
                true, 1, 2, false, false, false));
    }

    @Test
    void directElementalDustSmeltingTerminatesOnlyBaseAndHotIngotCycles() {
        assertTrue(DynamicRecipePatternRegistry.isDirectIngotOrHotIngotPrefix("ingot"));
        assertTrue(DynamicRecipePatternRegistry.isDirectIngotOrHotIngotPrefix("ingotHot"));
        assertFalse(DynamicRecipePatternRegistry.isDirectIngotOrHotIngotPrefix("ingotDouble"));

        assertTrue(DynamicRecipePatternRegistry.isCanonicalSameMaterialDustToIngotTransition(
                "ingotHot", true, true, false));
        assertFalse(DynamicRecipePatternRegistry.isCanonicalSameMaterialDustToIngotTransition(
                "ingotHot", false, true, false));
        assertFalse(DynamicRecipePatternRegistry.isCanonicalSameMaterialDustToIngotTransition(
                "ingot", true, false, false));
        assertFalse(DynamicRecipePatternRegistry.isCanonicalSameMaterialDustToIngotTransition(
                "ingot", true, true, true));
    }

    @Test
    void recyclingMetadataClassifiesNormalExtractorRecoveryRoutes() {
        assertTrue(DynamicRecipePatternRegistry.isRecyclingRecipe(false, true, true, true));
        assertTrue(DynamicRecipePatternRegistry.isRecyclingRecipe(true, false, false, false));
        assertFalse(DynamicRecipePatternRegistry.isRecyclingRecipe(false, true, true, false));
        assertFalse(DynamicRecipePatternRegistry.isRecyclingRecipe(false, false, true, true));
    }

    @Test
    void boundedRecipeScanCoversEachRecipeMapBeforeRepeatingABroadMap() {
        assertEquals(List.of(0, 1, 2, 0, 0),
                DynamicRecipePatternRegistry.fairRecipeScanBucketOrder(List.of(500, 1, 1), 5));
        assertEquals(List.of(0, 1, 0, 0, 0, 0),
                DynamicRecipePatternRegistry.fairRecipeScanBucketOrder(List.of(5, 1), 6));
    }

    @Test
    void staticDominanceOnlyAppliesToTheSameDependencyTree() {
        assertTrue(DynamicRecipePatternRegistry.sameDependencyOptions(
                List.of(List.of("sulfur"), List.of("water")),
                List.of(List.of("sulfur"), List.of("water"))));
        assertFalse(DynamicRecipePatternRegistry.sameDependencyOptions(
                List.of(List.of("sulfur_trioxide"), List.of("water")),
                List.of(List.of("sulfur"), List.of("water"))));
    }

    @Test
    void refinementCoversDistinctDependencyShapesBeforeMachineVariants() {
        List<String> ranked = List.of("h2s-reactor", "h2s-large-reactor", "sulfur-water", "so3-water");

        assertEquals(List.of("h2s-reactor", "sulfur-water", "so3-water"),
                DynamicRecipePatternRegistry.selectDiverseCandidates(ranked, 3,
                        candidate -> candidate.startsWith("h2s") ? "h2s-oxygen" : candidate));
    }

    @Test
    void refinementUsesDuplicateShapesOnlyAfterEveryShapeIsCovered() {
        List<String> ranked = List.of("nugget-compressor", "nugget-alloy-smelter", "hot-ingot", "molten");

        assertEquals(List.of("nugget-compressor", "hot-ingot", "molten", "nugget-alloy-smelter"),
                DynamicRecipePatternRegistry.selectDiverseCandidates(ranked, 4,
                        candidate -> candidate.startsWith("nugget") ? "nugget" : candidate));
    }

    @Test
    void progressiveRefinementReservesCapacityAfterTheFirstFairProbe() {
        assertEquals(32, DynamicRecipePatternRegistry.routeInitialExpansionQuota(512, 64, 8));
        assertEquals(64, DynamicRecipePatternRegistry.routeInitialExpansionQuota(4096, 64, 8));
        assertEquals(64, DynamicRecipePatternRegistry.routeInitialExpansionQuota(512, 64, 1));
    }

    @Test
    void standaloneFairAllowanceIsNotPartitionedTwice() {
        assertEquals(64, DynamicRecipePatternRegistry.routeInitialStandaloneExpansionQuota(64, 64));
        assertEquals(32, DynamicRecipePatternRegistry.routeInitialStandaloneExpansionQuota(32, 64));
        assertEquals(0, DynamicRecipePatternRegistry.routeInitialStandaloneExpansionQuota(0, 64));
    }

    @Test
    void progressiveRefinementDoublesOnlyWhenItCanReachNewSearchSpace() {
        assertEquals(128, DynamicRecipePatternRegistry.routeNextExpansionQuota(4096, 64));
        assertEquals(100, DynamicRecipePatternRegistry.routeNextExpansionQuota(100, 64));
        assertEquals(64, DynamicRecipePatternRegistry.routeNextExpansionQuota(64, 64));
    }

    @Test
    void standaloneTargetBudgetsAreDeadlineBoundedWithoutAnExpansionCap() throws ReflectiveOperationException {
        Class<?> budgetType = Class.forName(
                "applygray.integration.ae2.DynamicRecipePatternRegistry$RouteCostBudget");
        var constructor = budgetType.getDeclaredConstructor(PlanningBudget.class, boolean.class);
        var tryExpansion = budgetType.getDeclaredMethod("tryExpansion");
        var remainingExpansions = budgetType.getDeclaredMethod("getRemainingExpansions");
        var maxExpansions = budgetType.getDeclaredMethod("getMaxExpansions");
        constructor.setAccessible(true);
        tryExpansion.setAccessible(true);
        remainingExpansions.setAccessible(true);
        maxExpansions.setAccessible(true);

        Object firstTarget = constructor.newInstance(PlanningBudget.DEFAULT, true);
        assertEquals(0, ((Number) maxExpansions.invoke(firstTarget)).intValue());
        assertEquals(Integer.MAX_VALUE, ((Number) remainingExpansions.invoke(firstTarget)).intValue());
        for (int expansion = 0; expansion < 8192; expansion++) {
            assertTrue((Boolean) tryExpansion.invoke(firstTarget));
        }

        Object secondTarget = constructor.newInstance(PlanningBudget.DEFAULT, true);
        assertEquals(Integer.MAX_VALUE, ((Number) remainingExpansions.invoke(secondTarget)).intValue());
        assertTrue((Boolean) tryExpansion.invoke(secondTarget));
    }

    @Test
    void sccExpansionStopsAtExistingAndExplicitSeedRoutes() {
        assertTrue(DynamicRecipePatternRegistry.terminatesCycleGraph(true, false,
                CyclePolicy.BREAK_AT_EXTERNAL_SEED));
        assertTrue(DynamicRecipePatternRegistry.terminatesCycleGraph(false, true,
                CyclePolicy.BREAK_AT_EXTERNAL_SEED));
        assertTrue(DynamicRecipePatternRegistry.terminatesCycleGraph(false, false, CyclePolicy.EXTERNAL_SEED));
        assertFalse(DynamicRecipePatternRegistry.terminatesCycleGraph(false, false,
                CyclePolicy.BREAK_AT_EXTERNAL_SEED));
    }

    @Test
    void sccExpandsOnlySelfAndSameMaterialDependencies() {
        assertTrue(DynamicRecipePatternRegistry.isCycleScopeDependency(true, false));
        assertTrue(DynamicRecipePatternRegistry.isCycleScopeDependency(false, true));
        assertFalse(DynamicRecipePatternRegistry.isCycleScopeDependency(false, false));
    }
}
