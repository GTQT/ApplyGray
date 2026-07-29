package applygray.integration.ae2;

import applygray.ApplyGrayMod;
import applygray.mixins.supergiant.InvokerCraftingCalculation;

import gregtech.api.GTValues;
import gregtech.api.fluids.store.FluidStorageKeys;
import gregtech.api.unification.FluidUnifier;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.properties.PropertyKey;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.api.unification.stack.MaterialStack;
import gregtech.api.unification.stack.UnificationEntry;
import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IGrid;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.CraftingCalculation;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Lazy bridge between AE2's requested-output lookup and active RecipeMap pattern providers.
 * Only recipes requested by an AE crafting calculation become virtual patterns.
 */
public final class DynamicRecipePatternRegistry {

    private static final int STANDARD_FLUID_MILLIBUCKETS_PER_UNIT = 1000;
    /**
     * A RecipeMap can have hundreds of valid variants for one common output. Keep only this many lightweight
     * candidates for scoring, then materialize a single winner for AE2.
     */
    private static final int MAX_ROUTE_CANDIDATES_PER_TARGET = 8;
    private static final int MAX_MATERIALIZED_PATTERNS_PER_TARGET = 1;
    /**
     * Limits the work for a pathological common output even before candidates have been encoded. The output index is
     * ordered by RecipeMap registration order, so this is deliberately much larger than the exposed pattern limit.
     */
    private static final int MAX_RECIPES_PER_TARGET = 512;
    private static final int MAX_ROUTE_COST_DEPTH = 16;
    private static final int MAX_ROUTE_COST_EXPANSIONS = 64;
    private static final int MAX_ROUTE_COST_CALCULATION_EXPANSIONS = 512;
    private static final long MAX_ROUTE_COST_CALCULATION_NANOS = 2_000_000_000L;
    private static final int MAX_REFINED_ROUTE_CANDIDATES = 2;
    /** Lets route scoring compare the lathe and casting alternatives for one material-shaped output. */
    private static final int MAX_ROUTE_COST_MATERIAL_FORM_CANDIDATES = 2;
    private static final int MAX_NORMAL_PATTERNS_PER_TARGET = 32;
    private static final int MAX_ROUTE_COST_INPUT_ALTERNATIVES = 16;
    private static final long BOUNDED_ROUTE_COST_PENALTY = Long.MAX_VALUE / 4;
    private static final String GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX = "metaitem.general_circuit.";
    private static final String DISTILLATION_TOWER_RECIPE_MAP = "distillation_tower";
    /** Only polymer synthesis in these RecipeMaps may outrank a powder-to-fluid recycling route. */
    private static final Set<String> CHEMICAL_PRODUCT_SYNTHESIS_RECIPE_MAPS = Set.of(
            "chemical_reactor", "large_chemical_reactor", "polymerization_tank");

    private static final Map<IGrid, GridState> GRIDS = new ConcurrentHashMap<>();
    private static final Map<String, IGrid> PROVIDER_GRIDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> RECURSIVE_CYCLE_RECOVERY_REQUIRED = new ThreadLocal<>();
    private static final ThreadLocal<CraftingCalculation> ACTIVE_CRAFTING_CALCULATION = new ThreadLocal<>();
    /**
     * Identifies the worker calculation launched by the explicit rebuild action. A grid can have several concurrent
     * calculations, so only the calculation for this request may consume the pending full rebuild.
     */
    private static final ThreadLocal<OptimalRebuildRequest> ACTIVE_OPTIMAL_REBUILD_REQUEST = new ThreadLocal<>();
    /** Present only for the crafting calculation started by ApplyGray's explicit optimal rebuild action. */
    private static final ThreadLocal<OptimalRebuildContext> ACTIVE_OPTIMAL_REBUILD = new ThreadLocal<>();
    /** Prevents a normal-pattern probe used by route costing from recursively appending dynamic patterns. */
    private static final ThreadLocal<Boolean> NORMAL_PATTERN_COST_LOOKUP = new ThreadLocal<>();
    /** Caps all recursive route refinement performed by one AE crafting calculation. */
    private static final ThreadLocal<RouteCostBudget> ROUTE_COST_BUDGET = new ThreadLocal<>();

    private DynamicRecipePatternRegistry() {}

    /** Marks the calculation currently performing a lazy pattern lookup on this thread. */
    public static void enterCraftingCalculation(CraftingCalculation calculation) {
        if (calculation != null) {
            ACTIVE_CRAFTING_CALCULATION.set(calculation);
        }
    }

    /** Removes the current calculation's cooperative lookup context. */
    public static void leaveCraftingCalculation(CraftingCalculation calculation) {
        if (ACTIVE_CRAFTING_CALCULATION.get() == calculation) {
            ACTIVE_CRAFTING_CALCULATION.remove();
        }
    }

    /** Ends one CraftingService task after all recursive-cycle recovery attempts have either succeeded or failed. */
    public static void finishCraftingCalculationSession() {
        ROUTE_COST_BUDGET.remove();
        OptimalRebuildContext optimalRebuild = ACTIVE_OPTIMAL_REBUILD.get();
        OptimalRebuildRequest request = ACTIVE_OPTIMAL_REBUILD_REQUEST.get();
        if (optimalRebuild == null) {
            if (request != null) {
                ApplyGrayMod.LOGGER.info("Finished ApplyGray optimal rebuild for {} x{} without requesting any " +
                        "RecipeMap dynamic patterns", request.target, request.amount);
            }
            ACTIVE_OPTIMAL_REBUILD_REQUEST.remove();
            return;
        }

        long elapsedMillis = (System.nanoTime() - optimalRebuild.startedAt) / 1_000_000L;
        ApplyGrayMod.LOGGER.info("Finished Supergiant optimal rebuild for {} x{}: indexed {} active RecipeMaps from {} " +
                        "recipes in {} ms; inspected {} matching recipes for {} requested outputs and " +
                        "generated {} / reused {} dynamic patterns in {} ms; candidate priorities " +
                        "[chemical synthesis={}, dust/fluid={}, ingot={}, general={}, form change={}, recycling={}]; " +
                        "final plan dynamic routes [chemical synthesis={}, dust/fluid={}, ingot={}, general={}, " +
                        "form change={}, recycling={}]; dependency " +
                        "leaves [elemental dust={}]; inventory route scoring " +
                        "[targets={}, quick candidates={}, refined candidates={}, stock-only targets={}, " +
                        "expansions={}, normal pattern edges={}, dynamic edges={}, bounded fallbacks={}, " +
                        "total={} ms, slowest={} ms for {}]",
                request == null ? "<unknown>" : request.target, request == null ? 0 : request.amount,
                optimalRebuild.indexedRecipeMaps, optimalRebuild.indexedRecipes,
                optimalRebuild.indexRebuildMillis, optimalRebuild.matchingRecipeCandidates,
                optimalRebuild.requestedOutputs, optimalRebuild.generatedPatterns,
                optimalRebuild.reusedPatterns, elapsedMillis,
                optimalRebuild.chemicalSynthesisCandidates, optimalRebuild.dustOrFluidCandidates,
                optimalRebuild.ingotCandidates,
                optimalRebuild.generalCandidates, optimalRebuild.materialFormChangeCandidates,
                optimalRebuild.recyclingCandidates, optimalRebuild.selectedChemicalSynthesisPatterns,
                optimalRebuild.selectedDustOrFluidPatterns,
                optimalRebuild.selectedIngotPatterns, optimalRebuild.selectedGeneralPatterns,
                optimalRebuild.selectedMaterialFormChangePatterns, optimalRebuild.selectedRecyclingPatterns,
                optimalRebuild.elementalDustLeaves.size(), optimalRebuild.inventoryScoredTargets.size(),
                optimalRebuild.quickRouteCandidates, optimalRebuild.refinedRouteCandidates,
                optimalRebuild.stockOnlyRouteTargets, optimalRebuild.routeCostExpansions,
                optimalRebuild.normalPatternEdges, optimalRebuild.dynamicPatternEdges,
                optimalRebuild.boundedRouteCostFallbacks, optimalRebuild.routeScoringNanos / 1_000_000L,
                optimalRebuild.slowestRouteScoringNanos / 1_000_000L,
                optimalRebuild.slowestRouteScoringTarget == null ?
                        "<none>" : optimalRebuild.slowestRouteScoringTarget);
        ACTIVE_OPTIMAL_REBUILD.remove();
        ACTIVE_OPTIMAL_REBUILD_REQUEST.remove();
    }

    /** Records the final AE2 plan once, so an explicit rebuild has one concise, planner-facing diagnostic. */
    public static void recordOptimalRebuildPlan(ICraftingPlan plan) {
        OptimalRebuildContext optimalRebuild = ACTIVE_OPTIMAL_REBUILD.get();
        if (optimalRebuild != null && plan != null) {
            optimalRebuild.recordFinalPlan(plan);
        }
    }

    private static OptimalRebuildContext getActiveOptimalRebuild() {
        return ACTIVE_CRAFTING_CALCULATION.get() == null ? null : ACTIVE_OPTIMAL_REBUILD.get();
    }

    private static boolean hasActiveOptimalRebuildRequest() {
        return ACTIVE_CRAFTING_CALCULATION.get() != null && ACTIVE_OPTIMAL_REBUILD_REQUEST.get() != null;
    }

    /**
     * Arms the next calculation for one exact requested output. The matching is performed on the worker thread so an
     * unrelated concurrent calculation cannot consume the full rebuild.
     */
    public static void armOptimalRebuild(IGrid grid, AEKey target, long amount) {
        GridState state = GRIDS.get(grid);
        if (state != null && target != null && amount > 0) {
            state.armOptimalRebuild(target, amount);
        }
    }

    /**
     * Reserves the rebuild session for a CraftingService task while it is submitted. The caller must enter the
     * reserved session on that task's worker thread before invoking {@link CraftingCalculation#run()}.
     */
    public static boolean reserveOptimalRebuild(IGrid grid, AEKey target, long amount) {
        GridState state = GRIDS.get(grid);
        if (state == null) return false;

        return state.claimOptimalRebuild(target, amount) != null;
    }

    /** Returns whether the current crafting calculation was launched by the explicit optimal rebuild action. */
    public static boolean isOptimalRebuildCalculation() {
        return hasActiveOptimalRebuildRequest() && getActiveOptimalRebuild() != null;
    }

    /** Enters a rebuild session that was reserved at CraftingService task submission time. */
    public static void enterOptimalRebuild(AEKey target, long amount) {
        ACTIVE_OPTIMAL_REBUILD_REQUEST.set(new OptimalRebuildRequest(target, amount));
    }

    /** Removes an armed request when ContainerCraftConfirm could not schedule its calculation. */
    public static void cancelOptimalRebuild(IGrid grid, AEKey target, long amount) {
        GridState state = GRIDS.get(grid);
        if (state != null) {
            state.cancelOptimalRebuild(target, amount);
        }
    }

    public static void refreshProvider(MetaTileEntityMERecipeMapPatternProvider provider) {
        ProviderSnapshot snapshot = provider.createDynamicSnapshot();
        String providerId = provider.getDynamicProviderId();
        IGrid oldGrid = PROVIDER_GRIDS.get(providerId);

        if (snapshot == null) {
            if (oldGrid != null) unregister(provider);
            return;
        }

        if (oldGrid != null && oldGrid != snapshot.grid) {
            GridState oldState = GRIDS.get(oldGrid);
            if (oldState != null && oldState.removeProvider(providerId)) {
                GRIDS.remove(oldGrid, oldState);
            }
        }

        GridState state = GRIDS.computeIfAbsent(snapshot.grid, ignored -> new GridState());
        state.putProvider(snapshot);
        PROVIDER_GRIDS.put(providerId, snapshot.grid);
    }

    public static void unregister(MetaTileEntityMERecipeMapPatternProvider provider) {
        String providerId = provider.getDynamicProviderId();
        IGrid grid = PROVIDER_GRIDS.remove(providerId);
        if (grid == null) return;
        GridState state = GRIDS.get(grid);
        if (state != null && state.removeProvider(providerId)) {
            GRIDS.remove(grid, state);
        }
    }

    public static List<IPatternDetails> findPatterns(IGrid grid, AEKey target) {
        GridState state = GRIDS.get(grid);
        if (state == null || target == null) return Collections.emptyList();
        return state.findPatterns(target);
    }

    public static ICraftingProvider getProvider(IPatternDetails details) {
        for (GridState state : GRIDS.values()) {
            ICraftingProvider provider = state.providersByPattern.get(details);
            if (provider != null) return provider;
        }
        return null;
    }

    public static DynamicRecipePatternDetails getDynamicPattern(IPatternDetails details) {
        return details instanceof DynamicRecipePatternDetails ? (DynamicRecipePatternDetails) details : null;
    }

    /** Returns whether the current lookup may inspect only already-mounted, non-dynamic AE patterns. */
    public static boolean isNormalPatternCostLookup() {
        return Boolean.TRUE.equals(NORMAL_PATTERN_COST_LOOKUP.get());
    }

    public static boolean owns(IPatternDetails details, MetaTileEntityMERecipeMapPatternProvider provider) {
        for (GridState state : GRIDS.values()) {
            if (state.providersByPattern.get(details) == provider) return true;
        }
        return false;
    }

    /**
     * Invalidates only dynamic patterns that were selected by one crafting calculation.
     *
     * <p>Pattern lookup results sharing one of those details are invalidated as a whole. Filtering a selected
     * detail out of an existing result would leave an empty/partial cached lookup that could not regenerate the
     * pattern on the next calculation.</p>
     *
     * @return the number of dynamic patterns removed from this grid
     */
    public static int invalidatePlanPatterns(IGrid grid, Collection<? extends IPatternDetails> patterns) {
        GridState state = GRIDS.get(grid);
        if (state == null || patterns.isEmpty()) return 0;
        return state.invalidatePlanPatterns(patterns);
    }

    /**
     * Invalidates the dynamic dependency chain reachable from one root request, including cached per-output candidate
     * lists and recursive rejection records for that chain. The root calculation armed by the caller will recreate
     * its complete dynamic chain from the active RecipeMaps.
     *
     * @return the number of dynamic patterns removed from this grid
     */
    public static int invalidatePlanPatternsAndRecipeOutputIndexes(IGrid grid, AEKey rootTarget,
                                                                    Collection<? extends IPatternDetails> patterns) {
        GridState state = GRIDS.get(grid);
        if (state == null) return 0;
        return state.invalidatePlanPatternsAndRecipeOutputIndexes(rootTarget, patterns);
    }

    /**
     * Invalidates generated patterns from one provider without removing its ownership bindings for in-flight CPUs.
     *
     * @return the number of currently registered patterns removed from the provider
     */
    public static int clearProviderPatterns(MetaTileEntityMERecipeMapPatternProvider provider) {
        String providerId = provider.getDynamicProviderId();
        IGrid grid = PROVIDER_GRIDS.get(providerId);
        if (grid == null) return 0;

        GridState state = GRIDS.get(grid);
        return state == null ? 0 : state.clearProviderPatterns(providerId);
    }

    /**
     * Clears every dynamic pattern in a recursive segment while the explicit optimal rebuild recalculates that
     * segment. Ordinary crafting requests must use {@link #rejectRecursiveCycleAtOutput(AEKey, IPatternDetails)}
     * instead, so their cached intermediate patterns remain intact.
     */
    public static int invalidateRecursiveCycleForOptimalRebuild(AEKey target,
                                                                 Collection<? extends IPatternDetails> patterns) {
        if (!isOptimalRebuildCalculation() || target == null || patterns.isEmpty()) return 0;

        int removedCount = 0;
        for (GridState state : GRIDS.values()) {
            removedCount += state.invalidateRecursiveCycleForOptimalRebuild(target, patterns);
        }
        if (removedCount > 0) {
            RECURSIVE_CYCLE_RECOVERY_REQUIRED.set(Boolean.TRUE);
        }
        return removedCount;
    }

    /**
     * Breaks a non-productive recursive chain at a dust that has an ore form.
     *
     * <p>Only the selected pattern is rejected, and only while producing {@code target}. The next calculation can
     * then satisfy the dust through an ore-processing chain or report the dust as a missing external input.</p>
     *
     * @return the number of dynamic patterns removed from the matching grid
     */
    public static int rejectRecursiveCycleAtOreDust(AEKey target, IPatternDetails pattern) {
        if (!isOreBackedDust(target) || pattern == null) return 0;
        return rejectRecursiveCycleAtOutput(target, pattern);
    }

    /**
     * Rejects one selected dynamic pattern for the output it was producing in a non-productive recursive chain.
     *
     * <p>This preserves the association between the pattern and its requested output. It prevents the reverse edge
     * of a cycle from being selected again through a different output lookup on the next calculation.</p>
     *
     * @return the number of dynamic patterns removed from the matching grid
     */
    public static int rejectRecursiveCycleAtOutput(AEKey target, IPatternDetails pattern) {
        if (target == null || pattern == null) return 0;
        int removedCount = 0;
        for (GridState state : GRIDS.values()) {
            removedCount += state.rejectRecursiveCycleAtOutput(target, pattern);
        }
        if (removedCount > 0) {
            RECURSIVE_CYCLE_RECOVERY_REQUIRED.set(Boolean.TRUE);
            ApplyGrayMod.LOGGER.debug("Discarded {} cached lazy RecipeMap pattern(s) from a non-productive recursive " +
                    "cycle while producing {}", removedCount, target);
        }
        return removedCount;
    }

    /** Clears the current crafting thread's recursive-cycle recovery signal. */
    public static void clearRecursiveCycleRecovery() {
        RECURSIVE_CYCLE_RECOVERY_REQUIRED.remove();
    }

    /**
     * Returns whether the current crafting calculation removed recursive dynamic patterns, then clears the signal.
     */
    public static boolean consumeRecursiveCycleRecovery() {
        boolean required = Boolean.TRUE.equals(RECURSIVE_CYCLE_RECOVERY_REQUIRED.get());
        RECURSIVE_CYCLE_RECOVERY_REQUIRED.remove();
        return required;
    }

    /**
     * Checks a dynamic detail against its requested output and any recursive-cycle rejection recorded for it.
     */
    public static boolean isPatternAvailableFor(AEKey target, IPatternDetails details) {
        DynamicRecipePatternDetails dynamic = getDynamicPattern(details);
        if (dynamic == null) return true;
        if (target == null || !dynamic.netProduces(target)) return false;

        for (GridState state : GRIDS.values()) {
            if (state.isRejectedFor(target, dynamic)) return false;
        }
        return true;
    }

    /** Orders virtual patterns by their material route, then by input required per requested net output. */
    public static int compareDynamicPatternPriority(AEKey requested, DynamicRecipePatternDetails left,
                                                    DynamicRecipePatternDetails right) {
        long leftOutput = requested == null ? 0 : left.getNetOutputAmount(requested);
        long rightOutput = requested == null ? 0 : right.getNetOutputAmount(requested);
        return compareDynamicPatternPriority(left.getRoutePriority(), left.getRawMaterialCost(), leftOutput,
                left.getStepCost(), left.getRecipeKey(), right.getRoutePriority(), right.getRawMaterialCost(),
                rightOutput, right.getStepCost(), right.getRecipeKey());
    }

    /**
     * Reorders dynamic candidates using current network stock and already-mounted patterns.
     *
     * <p>A direct-input pass first removes obvious losers without expanding RecipeMap dependencies. If direct stock
     * does not decide the route, only the best quick candidate and the best static candidate receive bounded recursive
     * scoring. Inventory-dependent values live only for this lookup and are never persisted in pattern NBT.</p>
     */
    public static void sortPatternsForCrafting(IGrid grid, AEKey requested, List<IPatternDetails> patterns) {
        if (requested == null || patterns.size() < 2) return;
        GridState state = GRIDS.get(grid);
        if (state == null) return;

        long startedAt = System.nanoTime();
        RouteCostEstimator estimator = new RouteCostEstimator(grid, state, getRouteCostBudget());
        Map<IPatternDetails, DirectRouteCost> quickCosts = new IdentityHashMap<>();
        for (IPatternDetails pattern : patterns) {
            quickCosts.put(pattern, estimator.estimateDirect(pattern));
        }
        patterns.sort((left, right) -> {
            DynamicRecipePatternDetails leftDynamic = (DynamicRecipePatternDetails) left;
            DynamicRecipePatternDetails rightDynamic = (DynamicRecipePatternDetails) right;
            int mandatoryRoute = compareMandatoryRoutePriority(leftDynamic.getRoutePriority(),
                    rightDynamic.getRoutePriority());
            if (mandatoryRoute != 0) return mandatoryRoute;
            int quickCost = quickCosts.get(left).compareTo(quickCosts.get(right));
            if (quickCost != 0) return quickCost;
            return compareDynamicPatternPriority(requested, leftDynamic, rightDynamic);
        });

        boolean stockOnlySelection = quickCosts.get(patterns.get(0)).isFullyStocked();
        Map<IPatternDetails, RouteCost> refinedCosts = new IdentityHashMap<>();
        if (!stockOnlySelection) {
            List<IPatternDetails> refined = new ArrayList<>(MAX_REFINED_ROUTE_CANDIDATES);
            refined.add(patterns.get(0));

            IPatternDetails staticBest = patterns.get(0);
            for (int index = 1; index < patterns.size(); index++) {
                IPatternDetails candidate = patterns.get(index);
                if (compareDynamicPatternPriority(requested, (DynamicRecipePatternDetails) candidate,
                        (DynamicRecipePatternDetails) staticBest) < 0) {
                    staticBest = candidate;
                }
            }
            if (staticBest != refined.get(0)) {
                refined.add(staticBest);
            } else if (patterns.size() > 1) {
                refined.add(patterns.get(1));
            }

            for (IPatternDetails pattern : refined) {
                refinedCosts.put(pattern, estimator.estimateRoot(pattern, requested));
            }
            refined.sort((left, right) -> {
                int mandatoryRoute = compareMandatoryRoutePriority(
                        ((DynamicRecipePatternDetails) left).getRoutePriority(),
                        ((DynamicRecipePatternDetails) right).getRoutePriority());
                if (mandatoryRoute != 0) return mandatoryRoute;
                int routeCost = refinedCosts.get(left).compareTo(refinedCosts.get(right));
                if (routeCost != 0) return routeCost;
                return compareDynamicPatternPriority(requested, (DynamicRecipePatternDetails) left,
                        (DynamicRecipePatternDetails) right);
            });

            IPatternDetails selected = refined.get(0);
            if (patterns.get(0) != selected) {
                patterns.remove(selected);
                patterns.add(0, selected);
            }
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
        if (optimalRebuild != null) {
            optimalRebuild.recordRouteCostEstimator(requested, patterns.size(), refinedCosts.size(),
                    stockOnlySelection, elapsedNanos, estimator);
            IPatternDetails selected = patterns.get(0);
            if (optimalRebuild.inventoryScoredTargets.add(requested)) {
                DynamicRecipePatternDetails dynamic = (DynamicRecipePatternDetails) selected;
                List<String> ranking = new ArrayList<>(patterns.size());
                for (IPatternDetails pattern : patterns) {
                    DynamicRecipePatternDetails candidate = (DynamicRecipePatternDetails) pattern;
                    RouteCost refinedCost = refinedCosts.get(pattern);
                    ranking.add(candidate.getRecipeMapName() + "[" + candidate.getRoutePriority() + "]={quick=" +
                            quickCosts.get(pattern) +
                            (refinedCost == null ? "" : ", refined=" + refinedCost) + '}');
                }
                ApplyGrayMod.LOGGER.debug("Inventory-aware RecipeMap route for {} selected {} in {} after {} " +
                                "refined candidate(s) in {} ms from candidate ranking {}",
                        requested, dynamic.getRecipeKey(), dynamic.getRecipeMapName(), refinedCosts.size(),
                        elapsedNanos / 1_000_000L, ranking);
            }
        }
    }

    static int compareDynamicPatternPriority(CandidateRoutePriority leftPriority, long leftCost, long leftOutput,
                                             int leftSteps, String leftRecipeKey,
                                             CandidateRoutePriority rightPriority, long rightCost, long rightOutput,
                                             int rightSteps, String rightRecipeKey) {
        int route = compareCandidateRoutePriority(leftPriority, rightPriority);
        if (route != 0) return route;

        int efficiency = compareInputOutputEfficiency(leftCost, leftOutput, rightCost, rightOutput);
        if (efficiency != 0) return efficiency;

        int steps = Integer.compare(leftSteps, rightSteps);
        return steps != 0 ? steps : leftRecipeKey.compareTo(rightRecipeKey);
    }

    /**
     * Exposes only the requested deterministic output, even when it is not the recipe's first output.
     * Other outputs remain outside this virtual pattern because RecipeMap dynamic patterns model one requested
     * product at a time.
     */
    static List<GenericStack> selectRequestedPatternOutputs(AEKey requested, List<GenericStack> recipeOutputs) {
        if (requested == null || recipeOutputs.isEmpty()) {
            return Collections.emptyList();
        }
        for (GenericStack output : recipeOutputs) {
            if (requested.matches(output)) {
                return Collections.singletonList(output);
            }
        }
        return Collections.emptyList();
    }

    static boolean isOreInputPrefix(String prefixName) {
        return prefixName != null && (prefixName.startsWith("ore") || prefixName.startsWith("rawOre") ||
                prefixName.startsWith("crushed") || "dustImpure".equals(prefixName) ||
                "dustPure".equals(prefixName));
    }

    static boolean isOreBackedDust(String prefixName, boolean materialHasOreProperty) {
        return materialHasOreProperty && ("dust".equals(prefixName) || "dustSmall".equals(prefixName) ||
                "dustTiny".equals(prefixName) || "dustImpure".equals(prefixName) || "dustPure".equals(prefixName));
    }

    private static boolean isExternalOreInput(AEKey target) {
        return target instanceof AEItemKey itemKey && isExternalOreInput(itemKey.toStack());
    }

    private static boolean isExternalOreInput(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (int oreDictionaryId : OreDictionary.getOreIDs(stack)) {
            if (isOreInputPrefix(OreDictionary.getOreName(oreDictionaryId))) {
                return true;
            }
        }
        UnificationEntry entry = OreDictUnifier.getUnificationEntry(stack);
        return entry != null && entry.orePrefix != null && isOreInputPrefix(entry.orePrefix.name());
    }

    private static boolean containsExternalOreInput(ItemStack[] choices) {
        for (ItemStack choice : choices) {
            if (isExternalOreInput(choice)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOreBackedDust(AEKey target) {
        if (!(target instanceof AEItemKey itemKey)) return false;
        UnificationEntry entry = OreDictUnifier.getUnificationEntry(itemKey.toStack());
        return entry != null && isOreBackedDust(entry.orePrefix.name(),
                entry.material != null && entry.material.hasProperty(PropertyKey.ORE));
    }

    /**
     * Orders automatic material routes before their cost is considered. A recipe that breaks an existing material
     * form apart must never outrank a recipe that actually synthesizes the requested material from dusts, fluids,
     * or ingots merely because the former is a smaller batch.
     */
    private static CandidateRoutePriority getCandidateRoutePriority(AEKey target, RecipeMap<?> recipeMap,
                                                                      Recipe recipe, EncodedRecipe encoded) {
        if (isRecyclingRecipe(recipe)) {
            return CandidateRoutePriority.RECYCLING;
        }

        boolean usesDust = false;
        boolean usesPriorityFluid = false;
        boolean usesElementalFluid = false;
        boolean usesIngot = false;
        Material targetMaterial = getMaterialForKey(target);
        boolean hasMaterialInput = false;
        boolean hasTargetMaterialInput = false;
        boolean hasNonTargetMaterialInput = false;
        boolean onlyTargetMaterialInputs = targetMaterial != null;

        for (GenericStack input : encoded.inputs) {
            AEKey inputKey = input.what();
            Material inputMaterial = getMaterialForKey(inputKey);
            if (inputKey instanceof AEFluidKey) {
                usesPriorityFluid |= isPriorityFluidInput(targetMaterial, inputMaterial);
                usesElementalFluid |= isElementalMaterial(inputMaterial);
            } else if (inputKey instanceof AEItemKey itemKey) {
                UnificationEntry entry = OreDictUnifier.getUnificationEntry(itemKey.toStack());
                if (entry != null) {
                    String prefixName = entry.orePrefix.name();
                    usesDust |= isDustPrefix(prefixName);
                    usesIngot |= isIngotPrefix(prefixName);
                }
            }

            if (inputMaterial != null) {
                hasMaterialInput = true;
                if (targetMaterial != null && targetMaterial.equals(inputMaterial)) {
                    hasTargetMaterialInput = true;
                } else {
                    onlyTargetMaterialInputs = false;
                    hasNonTargetMaterialInput = true;
                }
            }
        }

        // Element-to-element fusion is a fallback for an elemental target. Keeping it below an ingot route lets
        // metal fluids follow the shorter dust -> ingot -> fluid chain. Elemental fluids remain primary inputs for
        // non-element reactions such as hydrogen plus fluorine to hydrofluoric acid.
        usesPriorityFluid |= !isElementalMaterial(targetMaterial) &&
                isPrimaryElementalFluidRoute(usesElementalFluid, usesDust, usesIngot, hasTargetMaterialInput);
        CandidateRoutePriority fallback = classifyCandidateRoute(usesDust, usesPriorityFluid, usesIngot,
                hasMaterialInput, onlyTargetMaterialInputs);
        return promoteChemicalProductSynthesis(target instanceof AEFluidKey,
                targetMaterial != null && targetMaterial.hasProperty(PropertyKey.POLYMER),
                recipeMap == null ? null : recipeMap.getUnlocalizedName(), hasTargetMaterialInput,
                hasNonTargetMaterialInput, fallback);
    }

    /**
     * Polymer fluids have an automatic extractor-recycling conversion from their dust form. That conversion is
     * useful for recycling, but it must not replace the actual chemical synthesis chain. Other fluid products retain
     * their existing route priority.
     */
    static CandidateRoutePriority promoteChemicalProductSynthesis(boolean targetIsFluid, boolean targetIsPolymer,
                                                                   @Nullable String recipeMapName,
                                                                   boolean hasTargetMaterialInput,
                                                                   boolean hasNonTargetMaterialInput,
                                                                   CandidateRoutePriority fallback) {
        boolean polymerSynthesis = targetIsFluid && targetIsPolymer && !hasTargetMaterialInput &&
                hasNonTargetMaterialInput;
        if (polymerSynthesis && isChemicalProductSynthesisRecipeMap(recipeMapName)) {
            return CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS;
        }
        return fallback;
    }

    static boolean isChemicalProductSynthesisRecipeMap(@Nullable String recipeMapName) {
        return recipeMapName != null && CHEMICAL_PRODUCT_SYNTHESIS_RECIPE_MAPS.contains(recipeMapName);
    }

    /**
     * Molten forms of the requested material are only a form change, while elemental fluids such as the
     * automatically-added Arc Furnace oxygen are processing auxiliaries. Neither may hide an ingot route.
     */
    static boolean isPriorityFluidInput(Material targetMaterial, Material inputMaterial) {
        return inputMaterial == null ||
                (!inputMaterial.equals(targetMaterial) && !isElementalMaterial(inputMaterial));
    }

    /**
     * Elemental fluids are auxiliary inputs beside a dust, ingot, or target-material form change, but they are the
     * actual raw materials of reactions such as hydrogen plus fluorine to hydrofluoric acid.
     */
    static boolean isPrimaryElementalFluidRoute(boolean usesElementalFluid, boolean usesDust, boolean usesIngot,
                                                boolean hasTargetMaterialInput) {
        return usesElementalFluid && !usesDust && !usesIngot && !hasTargetMaterialInput;
    }

    static CandidateRoutePriority classifyCandidateRoute(boolean usesDust, boolean usesPriorityFluid,
                                                         boolean usesIngot,
                                                         boolean hasMaterialInput,
                                                         boolean onlyTargetMaterialInputs) {
        if (usesDust || usesPriorityFluid) {
            return CandidateRoutePriority.DUST_OR_FLUID_INPUT;
        }
        if (usesIngot) {
            return CandidateRoutePriority.INGOT_INPUT;
        }
        if (hasMaterialInput && onlyTargetMaterialInputs) {
            return CandidateRoutePriority.MATERIAL_FORM_CHANGE;
        }
        return CandidateRoutePriority.GENERAL;
    }

    private static boolean isRecyclingRecipe(Recipe recipe) {
        if (recipe == null || recipe.getRecipeCategory() == null) return false;
        return isRecyclingRecipeCategoryName(recipe.getRecipeCategory().getName());
    }

    static boolean isRecyclingRecipeCategoryName(String categoryName) {
        return "recycling".equals(categoryName) ||
                (categoryName != null && categoryName.endsWith("_recycling"));
    }

    static boolean isDustPrefix(String prefixName) {
        return prefixName != null && prefixName.startsWith("dust");
    }

    static boolean isIngotPrefix(String prefixName) {
        return prefixName != null && prefixName.startsWith("ingot");
    }

    private static Material getMaterialForKey(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            MaterialStack materialStack = OreDictUnifier.getMaterial(itemKey.toStack());
            return materialStack == null ? null : materialStack.material;
        }
        if (key instanceof AEFluidKey fluidKey) {
            return FluidUnifier.getMaterialFromFluid(fluidKey.getFluid());
        }
        return null;
    }

    static boolean isElementalMaterial(Material material) {
        return material != null && material.isElement();
    }

    static boolean isDynamicRecipeMapEnabled(String recipeMapName) {
        return !DISTILLATION_TOWER_RECIPE_MAP.equals(recipeMapName);
    }

    private static boolean isDynamicRecipeMapEnabled(RecipeMap<?> recipeMap) {
        return recipeMap != null && isDynamicRecipeMapEnabled(recipeMap.getUnlocalizedName());
    }

    static boolean isElementalDust(String prefixName, boolean materialIsElement) {
        return materialIsElement && isDustPrefix(prefixName);
    }

    private static boolean isElementalDust(AEKey key) {
        if (!(key instanceof AEItemKey itemKey)) return false;
        UnificationEntry entry = OreDictUnifier.getUnificationEntry(itemKey.toStack());
        return entry != null && entry.orePrefix != null &&
                isElementalDust(entry.orePrefix.name(), isElementalMaterial(getMaterialForKey(key)));
    }

    /**
     * Separates virtual patterns for distinct requested outputs of one physical RecipeMap recipe. The serialized
     * target representation keeps NBT-sensitive item and fluid keys distinct in the persisted provider cache.
     */
    static String createTargetedRecipeKey(String recipeKey, AEKey target) {
        if (target == null) {
            throw new IllegalArgumentException("Dynamic RecipeMap pattern target cannot be null");
        }
        return recipeKey + ":target:" + target.getType().getId() + ':' + target.toTag().toString();
    }

    public static final class ProviderSnapshot {

        private final IGrid grid;
        private final String providerId;
        private final long epoch;
        private final RecipeMap<?>[] recipeMaps;
        private final MetaTileEntityMERecipeMapPatternProvider provider;

        public ProviderSnapshot(IGrid grid, String providerId, long epoch, RecipeMap<?>[] recipeMaps,
                                MetaTileEntityMERecipeMapPatternProvider provider) {
            this.grid = grid;
            this.providerId = providerId;
            this.epoch = epoch;
            this.recipeMaps = Arrays.copyOf(recipeMaps, recipeMaps.length);
            this.provider = provider;
        }

        private boolean sameDefinition(ProviderSnapshot other) {
            return other != null && epoch == other.epoch && Arrays.equals(recipeMaps, other.recipeMaps) &&
                    provider == other.provider;
        }
    }

    private static final class GridState {

        private final Map<String, ProviderSnapshot> providers = new ConcurrentHashMap<>();
        private final Map<AEKey, List<DynamicRecipePatternDetails>> patternsByTarget = new ConcurrentHashMap<>();
        private final Map<String, DynamicRecipePatternDetails> patternsByRecipe = new ConcurrentHashMap<>();
        private final Map<AEKey, Set<String>> rejectedRecipeKeysByTarget = new ConcurrentHashMap<>();
        /**
         * Resolves a requested output to its producing recipes without rescanning every RecipeMap for every node in a
         * recursive crafting calculation. It is cleared whenever the set of providers changes. ApplyGray's explicit
         * rebuild action marks the complete active set for one eager rescan before candidate selection resumes.
         */
        private final Map<RecipeMap<?>, RecipeOutputIndex> recipeOutputIndexes = new ConcurrentHashMap<>();
        // Retired dynamic details must remain resolvable while an already-submitted CPU still holds them.
        // Weak keys release the association once no plan or CPU references the old detail anymore.
        private final Map<IPatternDetails, ICraftingProvider> providersByPattern =
                Collections.synchronizedMap(new WeakHashMap<>());
        /** Prevents an index built before an explicit rebuild from being written back after it was cleared. */
        private volatile long recipeOutputIndexEpoch;
        /** Zero means no explicit full index rebuild is pending. Guarded by {@code this}. */
        private long pendingFullRecipeOutputIndexEpoch;
        /** Guards one eager index rebuild at a time. Guarded by {@code this}. */
        private boolean fullRecipeOutputIndexRebuildInProgress;
        /** The root request allowed to consume {@link #pendingFullRecipeOutputIndexEpoch}. Guarded by {@code this}. */
        private OptimalRebuildRequest pendingOptimalRebuild;

        private synchronized void putProvider(ProviderSnapshot snapshot) {
            ProviderSnapshot existing = providers.put(snapshot.providerId, snapshot);
            if (!snapshot.sameDefinition(existing)) {
                clearGenerated();
                bindAllCachedPatterns();
            } else {
                bindCachedPatterns(snapshot);
            }
        }

        private void bindAllCachedPatterns() {
            for (ProviderSnapshot provider : providers.values()) {
                bindCachedPatterns(provider);
            }
        }

        private void bindCachedPatterns(ProviderSnapshot snapshot) {
            for (DynamicRecipePatternDetails detail : snapshot.provider.getCachedDynamicPatterns()) {
                if (!isRecipeMapAvailable(snapshot, detail)) {
                    continue;
                }
                patternsByRecipe.put(detail.getRecipeKey(), detail);
                providersByPattern.put(detail, snapshot.provider);
                bindCachedPatternOutputs(detail);
            }
        }

        /**
         * Restores the target lookup for persisted patterns so a world reload can use an already materialized dynamic
         * pattern without rescanning the corresponding RecipeMap.
         */
        private void bindCachedPatternOutputs(DynamicRecipePatternDetails detail) {
            for (GenericStack output : detail.getOutputs()) {
                if (output == null || output.amount() <= 0 || !detail.netProduces(output.what())) {
                    continue;
                }
                AEKey target = output.what();
                patternsByTarget.compute(target, (ignored, existing) -> {
                    List<DynamicRecipePatternDetails> updated = existing == null ?
                            new ArrayList<>() : new ArrayList<>(existing);
                    updated.removeIf(candidate -> candidate.getRecipeKey().equals(detail.getRecipeKey()));
                    updated.add(detail);
                    updated.sort((left, right) -> compareDynamicPatternPriority(target, left, right));
                    if (updated.size() > MAX_MATERIALIZED_PATTERNS_PER_TARGET) {
                        updated = new ArrayList<>(updated.subList(0, MAX_MATERIALIZED_PATTERNS_PER_TARGET));
                    }
                    return Collections.unmodifiableList(updated);
                });
            }
        }

        private static boolean isRecipeMapAvailable(ProviderSnapshot snapshot, DynamicRecipePatternDetails detail) {
            for (RecipeMap<?> recipeMap : snapshot.recipeMaps) {
                if (isDynamicRecipeMapEnabled(recipeMap) &&
                        recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return whether no active providers remain on this grid.
         */
        private synchronized boolean removeProvider(String providerId) {
            if (providers.remove(providerId) != null) {
                clearGenerated();
                bindAllCachedPatterns();
            }
            return providers.isEmpty();
        }

        private List<IPatternDetails> findPatterns(AEKey target) {
            // Ores and their processing intermediates are external resources. A parent recipe may consume them,
            // but the lazy generator must never attempt to manufacture an ore-processing chain itself.
            if (isExternalOreInput(target)) {
                return Collections.emptyList();
            }
            // Element dust is the external periodic-table input. Ingots, fluids, and processed forms must still
            // expand so chains such as foil -> ingot -> dust and fluid -> ingot -> dust remain craftable.
            if (isElementalDust(target)) {
                OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
                if (optimalRebuild != null && optimalRebuild.elementalDustLeaves.add(target)) {
                    ApplyGrayMod.LOGGER.debug("Stopped dynamic RecipeMap dependency expansion at elemental dust {}",
                            target);
                }
                return Collections.emptyList();
            }
            if (!cooperateWithCraftingCalculation()) {
                return Collections.emptyList();
            }
            if (!ensureFullRecipeOutputIndexRebuild()) {
                return Collections.emptyList();
            }
            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            if (optimalRebuild != null) {
                optimalRebuild.requestedOutputs++;
            }

            List<DynamicRecipePatternDetails> existing = patternsByTarget.get(target);
            if (existing == null) {
                long startedAt = System.nanoTime();
                List<DynamicRecipePatternDetails> generated = createPatterns(target);
                if (Thread.currentThread().isInterrupted()) {
                    abortCancelledCalculation();
                    return Collections.emptyList();
                }
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                if (elapsedMillis >= 1_000L) {
                    ApplyGrayMod.LOGGER.warn("Lazy RecipeMap pattern lookup for {} took {} ms and found {} patterns",
                            target, elapsedMillis, generated.size());
                }

                synchronized (this) {
                    existing = patternsByTarget.get(target);
                    if (existing == null) {
                        patternsByTarget.put(target, generated);
                        existing = generated;
                    }
                }
            }
            List<IPatternDetails> available = new ArrayList<>(existing.size());
            for (int index = 0; index < existing.size(); index++) {
                if (!cooperateWithCraftingCalculation()) {
                    return Collections.emptyList();
                }
                DynamicRecipePatternDetails detail = existing.get(index);
                if (isPatternAvailableFor(target, detail)) {
                    available.add(detail);
                }
            }
            return available;
        }

        /**
         * Supplies dynamic dependency edges to the route estimator without recursively invoking CraftingService.
         * Material-form routes retain their two cheapest alternatives so a round can follow either its lathe or
         * casting chain to a producible input. All other targets keep one edge to bound recursive scoring.
         */
        private List<PatternCandidate> getCandidatesForRouteCost(AEKey target) {
            if (target == null || isExternalOreInput(target) || isElementalDust(target)) {
                return Collections.emptyList();
            }
            List<PatternCandidate> candidates = collectPatternCandidates(target,
                    MAX_ROUTE_COST_MATERIAL_FORM_CANDIDATES);
            if (candidates.isEmpty() ||
                    candidates.get(0).cost.routePriority != CandidateRoutePriority.MATERIAL_FORM_CHANGE) {
                return candidates.isEmpty() ? candidates : Collections.singletonList(candidates.get(0));
            }

            List<PatternCandidate> formChangeCandidates = new ArrayList<>(candidates.size());
            for (PatternCandidate candidate : candidates) {
                if (candidate.cost.routePriority != CandidateRoutePriority.MATERIAL_FORM_CHANGE) break;
                formChangeCandidates.add(candidate);
            }
            return formChangeCandidates;
        }

        private List<DynamicRecipePatternDetails> createPatterns(AEKey target) {
            List<PatternCandidate> candidates = collectPatternCandidates(target, MAX_ROUTE_CANDIDATES_PER_TARGET);
            if (candidates.isEmpty()) {
                return Collections.emptyList();
            }

            PatternCandidate selected = selectBestCandidate(target, candidates);
            DynamicRecipePatternDetails detail = materializePattern(target, selected);
            return detail == null ? Collections.emptyList() : Collections.singletonList(detail);
        }

        private List<PatternCandidate> collectPatternCandidates(AEKey target, int candidateLimit) {
            List<PatternCandidate> candidates = new ArrayList<>(candidateLimit);
            List<ProviderSnapshot> sources = new ArrayList<>(providers.values());
            Set<String> seenRecipeKeys = new HashSet<>();
            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            boolean inspectAllCandidates = optimalRebuild != null;
            long inspectedRecipes = 0;
            boolean cappedRecipeScan = false;

            scan:
            for (ProviderSnapshot source : sources) {
                KeyCounter storedItems = getStoredItems(source);
                for (RecipeMap<?> recipeMap : source.recipeMaps) {
                    if (!isDynamicRecipeMapEnabled(recipeMap)) {
                        continue;
                    }
                    RecipeOutputIndex outputIndex = getRecipeOutputIndex(recipeMap);
                    if (outputIndex == null) {
                        return Collections.emptyList();
                    }
                    for (Recipe recipe : outputIndex.getRecipes(target)) {
                        if (!cooperateWithCraftingCalculation()) {
                            return Collections.emptyList();
                        }
                        if (!inspectAllCandidates && inspectedRecipes >= MAX_RECIPES_PER_TARGET) {
                            cappedRecipeScan = true;
                            break scan;
                        }
                        inspectedRecipes++;
                        if (optimalRebuild != null) {
                            optimalRebuild.matchingRecipeCandidates++;
                        }
                        EncodedRecipe encoded = encodeRecipe(recipe, storedItems);
                        if (encoded == null) continue;
                        List<GenericStack> patternOutputs = selectRequestedPatternOutputs(target, encoded.outputs);
                        if (patternOutputs.isEmpty()) continue;
                        encoded = encoded.withOutputs(patternOutputs);
                        long netOutput = DynamicRecipePatternDetails.getNetOutputAmount(target, encoded.inputs,
                                encoded.alternatives, encoded.outputs);
                        if (netOutput <= 0) continue;
                        CandidateRoutePriority routePriority = getCandidateRoutePriority(target, recipeMap, recipe,
                                encoded);
                        if (optimalRebuild != null) {
                            optimalRebuild.recordCandidate(routePriority);
                        }
                        // This ranking only affects pattern preference. Avoiding recursive cost evaluation keeps
                        // large RecipeMaps from turning one lookup into a full dependency scan.
                        Cost cost = Cost.fallback(recipe, storedItems, netOutput, routePriority);
                        PatternCandidate candidate = new PatternCandidate(source, recipeMap, recipe, target, encoded,
                                cost);
                        if (!seenRecipeKeys.add(candidate.recipeKey)) continue;
                        if (isRejectedFor(target, candidate.recipeKey)) continue;
                        keepBestCandidate(candidates, candidate, candidateLimit);
                    }
                }
            }

            if (cappedRecipeScan) {
                ApplyGrayMod.LOGGER.warn("Lazy RecipeMap pattern lookup for {} stopped after {} recipe candidates " +
                                "to keep the crafting calculation bounded",
                        target, MAX_RECIPES_PER_TARGET);
            }
            return candidates;
        }

        private PatternCandidate selectBestCandidate(AEKey target, List<PatternCandidate> candidates) {
            if (candidates.size() < 2) {
                return candidates.get(0);
            }

            long startedAt = System.nanoTime();
            PatternCandidate staticBest = candidates.get(0);
            RouteCostEstimator estimator = new RouteCostEstimator(staticBest.source.grid, this,
                    getRouteCostBudget());
            Map<PatternCandidate, DirectRouteCost> quickCosts = new IdentityHashMap<>();
            for (PatternCandidate candidate : candidates) {
                quickCosts.put(candidate, estimator.estimateDirect(candidate));
            }
            candidates.sort((left, right) -> {
                int mandatoryRoute = compareMandatoryRoutePriority(left.cost.routePriority,
                        right.cost.routePriority);
                if (mandatoryRoute != 0) return mandatoryRoute;
                int quickCost = quickCosts.get(left).compareTo(quickCosts.get(right));
                return quickCost != 0 ? quickCost : compareCandidates(left, right);
            });

            boolean stockOnlySelection = quickCosts.get(candidates.get(0)).isFullyStocked();
            Map<PatternCandidate, RouteCost> refinedCosts = new IdentityHashMap<>();
            if (!stockOnlySelection) {
                List<PatternCandidate> refined = new ArrayList<>(MAX_REFINED_ROUTE_CANDIDATES);
                refined.add(candidates.get(0));
                if (staticBest != refined.get(0)) {
                    refined.add(staticBest);
                } else if (candidates.size() > 1) {
                    refined.add(candidates.get(1));
                }

                for (PatternCandidate candidate : refined) {
                    refinedCosts.put(candidate, estimator.estimateRoot(candidate, target));
                }
                refined.sort((left, right) -> {
                    int mandatoryRoute = compareMandatoryRoutePriority(left.cost.routePriority,
                            right.cost.routePriority);
                    if (mandatoryRoute != 0) return mandatoryRoute;
                    int routeCost = refinedCosts.get(left).compareTo(refinedCosts.get(right));
                    return routeCost != 0 ? routeCost : compareCandidates(left, right);
                });

                PatternCandidate selected = refined.get(0);
                if (candidates.get(0) != selected) {
                    candidates.remove(selected);
                    candidates.add(0, selected);
                }
            }

            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            if (optimalRebuild != null) {
                optimalRebuild.inventoryScoredTargets.add(target);
                optimalRebuild.recordRouteCostEstimator(target, candidates.size(), refinedCosts.size(),
                        stockOnlySelection, System.nanoTime() - startedAt, estimator);
            }
            return candidates.get(0);
        }

        @Nullable
        private DynamicRecipePatternDetails materializePattern(AEKey target, PatternCandidate candidate) {
            if (!cooperateWithCraftingCalculation()) {
                return null;
            }

            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            DynamicRecipePatternDetails detail = candidate.source.provider
                    .getCachedDynamicPattern(candidate.recipeKey);
            if (detail != null && !detail.matchesRecipeDefinition(candidate.recipeMap.getUnlocalizedName(),
                    candidate.encoded.inputs, candidate.encoded.alternatives, candidate.encoded.outputs,
                    candidate.encoded.circuitConfiguration, candidate.cost.rawMaterials, candidate.cost.steps,
                    candidate.cost.routePriority)) {
                candidate.source.provider.removeCachedDynamicPattern(candidate.recipeKey);
                detail = null;
            }
            if (detail == null) {
                detail = new DynamicRecipePatternDetails(candidate.recipeKey,
                        candidate.recipeMap.getUnlocalizedName(), candidate.encoded.inputs,
                        candidate.encoded.alternatives, candidate.encoded.outputs,
                        candidate.encoded.circuitConfiguration,
                        candidate.cost.rawMaterials, candidate.cost.steps, candidate.cost.routePriority);
                if (!isPatternAvailableFor(target, detail)) {
                    return null;
                }
                detail = candidate.source.provider.cacheDynamicPattern(detail);
                if (!isPatternAvailableFor(target, detail)) {
                    return null;
                }
                if (optimalRebuild != null) {
                    optimalRebuild.generatedPatterns++;
                }
            } else {
                if (!isPatternAvailableFor(target, detail)) {
                    return null;
                }
                if (optimalRebuild != null) {
                    optimalRebuild.reusedPatterns++;
                }
            }
            patternsByRecipe.put(candidate.recipeKey, detail);
            providersByPattern.put(detail, candidate.source.provider);
            return detail;
        }

        /** Fully rebuilds every active RecipeMap output index after the explicit ApplyGray rebuild action. */
        private boolean ensureFullRecipeOutputIndexRebuild() {
            long epoch;
            List<RecipeMap<?>> recipeMaps;
            CraftingCalculation calculation = ACTIVE_CRAFTING_CALCULATION.get();
            synchronized (this) {
                while (fullRecipeOutputIndexRebuildInProgress) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (pendingFullRecipeOutputIndexEpoch == 0) {
                    return true;
                }
                // A terminal query or another player's calculation can call getCraftingFor while the new plan is
                // being scheduled. Only the exact root calculation armed by the rebuild button may consume it.
                if (calculation == null || !hasActiveOptimalRebuildRequest()) {
                    return false;
                }

                epoch = pendingFullRecipeOutputIndexEpoch;
                fullRecipeOutputIndexRebuildInProgress = true;
                recipeMaps = getActiveRecipeMaps();
            }

            long startedAt = System.nanoTime();
            int scannedRecipeMaps = 0;
            int scannedRecipes = 0;
            boolean completed = false;
            try {
                for (RecipeMap<?> recipeMap : recipeMaps) {
                    if (!cooperateWithCraftingCalculation()) {
                        return false;
                    }
                    RecipeOutputIndex outputIndex = rebuildRecipeOutputIndex(recipeMap, epoch);
                    if (outputIndex == null) {
                        return false;
                    }
                    scannedRecipeMaps++;
                    scannedRecipes += outputIndex.recipeCount;
                }
                completed = true;
                return true;
            } finally {
                synchronized (this) {
                    if (completed && pendingFullRecipeOutputIndexEpoch == epoch) {
                        pendingFullRecipeOutputIndexEpoch = 0;
                        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                        ACTIVE_OPTIMAL_REBUILD.set(new OptimalRebuildContext(scannedRecipeMaps, scannedRecipes,
                                elapsedMillis, startedAt));
                        ApplyGrayMod.LOGGER.info("Fully rebuilt {} active RecipeMap output indexes from {} recipes " +
                                        "for the ApplyGray optimal rebuild in {} ms; this calculation will inspect " +
                                        "every matching candidate recipe",
                                scannedRecipeMaps, scannedRecipes, elapsedMillis);
                    }
                    fullRecipeOutputIndexRebuildInProgress = false;
                    notifyAll();
                }
            }
        }

        private List<RecipeMap<?>> getActiveRecipeMaps() {
            Set<RecipeMap<?>> uniqueRecipeMaps = new HashSet<>();
            for (ProviderSnapshot snapshot : providers.values()) {
                for (RecipeMap<?> recipeMap : snapshot.recipeMaps) {
                    if (isDynamicRecipeMapEnabled(recipeMap)) {
                        uniqueRecipeMaps.add(recipeMap);
                    }
                }
            }
            return new ArrayList<>(uniqueRecipeMaps);
        }

        private RecipeOutputIndex getRecipeOutputIndex(RecipeMap<?> recipeMap) {
            return buildRecipeOutputIndex(recipeMap, recipeOutputIndexEpoch, false);
        }

        private RecipeOutputIndex rebuildRecipeOutputIndex(RecipeMap<?> recipeMap, long expectedEpoch) {
            return buildRecipeOutputIndex(recipeMap, expectedEpoch, true);
        }

        private RecipeOutputIndex buildRecipeOutputIndex(RecipeMap<?> recipeMap, long expectedEpoch,
                                                          boolean forceRebuild) {
            Collection<Recipe> recipes = recipeMap.getRecipeList();
            int recipeCount = recipes.size();
            RecipeOutputIndex existing = recipeOutputIndexes.get(recipeMap);
            if (!forceRebuild && existing != null && existing.recipeCount == recipeCount) {
                return existing;
            }

            long startedAt = System.nanoTime();
            RecipeOutputIndex indexed = RecipeOutputIndex.create(recipes);
            if (indexed == null) {
                return null;
            }

            RecipeOutputIndex result = indexed;
            synchronized (this) {
                if (expectedEpoch != recipeOutputIndexEpoch) {
                    // A newer provider refresh or explicit rebuild discarded this index while it was being scanned.
                    // The running calculation may use this local result, but cannot repopulate the new cache with it.
                    return indexed;
                }
                if (forceRebuild) {
                    recipeOutputIndexes.put(recipeMap, indexed);
                } else {
                    RecipeOutputIndex current = recipeOutputIndexes.putIfAbsent(recipeMap, indexed);
                    result = current != null && current.recipeCount == recipeCount ? current : indexed;
                    if (current != null && current.recipeCount != recipeCount) {
                        recipeOutputIndexes.replace(recipeMap, current, indexed);
                        result = indexed;
                    }
                }
            }

            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            if (elapsedMillis >= 1_000L) {
                ApplyGrayMod.LOGGER.warn("Indexed {} deterministic outputs from {} recipes in RecipeMap {} in {} ms",
                        result.outputCount, result.recipeCount, recipeMap.getUnlocalizedName(), elapsedMillis);
            }
            return result;
        }

        private static void keepBestCandidate(List<PatternCandidate> candidates, PatternCandidate candidate,
                                              int candidateLimit) {
            int insertionIndex = 0;
            while (insertionIndex < candidates.size() &&
                    compareCandidates(candidates.get(insertionIndex), candidate) <= 0) {
                insertionIndex++;
            }
            if (insertionIndex >= candidateLimit) {
                return;
            }

            candidates.add(insertionIndex, candidate);
            if (candidates.size() > candidateLimit) {
                candidates.remove(candidates.size() - 1);
            }
        }

        private static int compareCandidates(PatternCandidate left, PatternCandidate right) {
            int comparison = left.cost.compareTo(right.cost);
            return comparison != 0 ? comparison : left.recipeKey.compareTo(right.recipeKey);
        }

        private static boolean cooperateWithCraftingCalculation() {
            if (Thread.currentThread().isInterrupted()) {
                return abortCancelledCalculation();
            }

            CraftingCalculation calculation = ACTIVE_CRAFTING_CALCULATION.get();
            if (!(calculation instanceof InvokerCraftingCalculation pausable)) {
                return true;
            }
            try {
                pausable.applygray$handlePausing();
                return !Thread.currentThread().isInterrupted() || abortCancelledCalculation();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return abortCancelledCalculation();
            }
        }

        private static boolean abortCancelledCalculation() {
            if (ACTIVE_CRAFTING_CALCULATION.get() != null) {
                throw new CancellationException("Lazy RecipeMap pattern lookup cancelled");
            }
            return false;
        }

        private synchronized int invalidatePlanPatterns(Collection<? extends IPatternDetails> patterns) {
            Set<DynamicRecipePatternDetails> selectedPatterns = new HashSet<>();
            for (IPatternDetails pattern : patterns) {
                DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
                if (dynamic != null) selectedPatterns.add(dynamic);
            }
            if (selectedPatterns.isEmpty()) return 0;

            Set<DynamicRecipePatternDetails> removedPatterns = new HashSet<>();
            for (DynamicRecipePatternDetails selected : selectedPatterns) {
                String recipeKey = selected.getRecipeKey();
                DynamicRecipePatternDetails registered = patternsByRecipe.get(recipeKey);
                if (registered != selected || !patternsByRecipe.remove(recipeKey, registered)) continue;

                removedPatterns.add(registered);
                ICraftingProvider provider = providersByPattern.get(registered);
                if (provider instanceof MetaTileEntityMERecipeMapPatternProvider) {
                    ((MetaTileEntityMERecipeMapPatternProvider) provider).removeCachedDynamicPattern(recipeKey);
                }
            }
            if (removedPatterns.isEmpty()) return 0;

            removePatternsFromTargetCache(removedPatterns);
            return removedPatterns.size();
        }

        private synchronized int invalidatePlanPatternsAndRecipeOutputIndexes(AEKey rootTarget,
                Collection<? extends IPatternDetails> patterns) {
            Set<DynamicRecipePatternDetails> chainPatterns =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            Set<AEKey> chainTargets = collectDynamicDependencyTargets(rootTarget, patterns, chainPatterns);
            int clearedTargetLookups = 0;
            for (AEKey target : chainTargets) {
                if (patternsByTarget.remove(target) != null) {
                    clearedTargetLookups++;
                }
            }

            int clearedPatterns = 0;
            for (DynamicRecipePatternDetails detail : chainPatterns) {
                String recipeKey = detail.getRecipeKey();
                DynamicRecipePatternDetails registered = patternsByRecipe.remove(recipeKey);
                if (registered == null) continue;

                clearedPatterns++;
                ICraftingProvider provider = providersByPattern.get(registered);
                if (provider instanceof MetaTileEntityMERecipeMapPatternProvider) {
                    ((MetaTileEntityMERecipeMapPatternProvider) provider).removeCachedDynamicPattern(recipeKey);
                }
            }

            int clearedRejections = 0;
            for (AEKey target : chainTargets) {
                Set<String> rejected = rejectedRecipeKeysByTarget.remove(target);
                if (rejected != null) {
                    clearedRejections += rejected.size();
                }
            }

            int clearedIndexes = recipeOutputIndexes.size();
            requestFullRecipeOutputIndexRebuild();
            ApplyGrayMod.LOGGER.info("Cleared {} dynamic RecipeMap patterns, {} cached target lookups, and {} " +
                    "recursive rejections reachable from {}, plus {} RecipeMap output indexes; the root " +
                    "calculation will re-evaluate only this chain's dynamic candidates",
                    clearedPatterns, clearedTargetLookups, clearedRejections, rootTarget, clearedIndexes);
            return clearedPatterns;
        }

        private Set<AEKey> collectDynamicDependencyTargets(AEKey rootTarget,
                                                            Collection<? extends IPatternDetails> patterns,
                                                            Set<DynamicRecipePatternDetails> chainPatterns) {
            Set<AEKey> chainTargets = new HashSet<>();
            Set<IPatternDetails> visitedPatterns = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<AEKey> targetsToVisit = new ArrayDeque<>();
            Deque<IPatternDetails> patternsToVisit = new ArrayDeque<>();
            if (rootTarget != null) {
                targetsToVisit.add(rootTarget);
            }
            for (IPatternDetails pattern : patterns) {
                if (pattern != null) {
                    patternsToVisit.add(pattern);
                }
            }

            while (!targetsToVisit.isEmpty() || !patternsToVisit.isEmpty()) {
                while (!targetsToVisit.isEmpty()) {
                    AEKey target = targetsToVisit.removeFirst();
                    if (!chainTargets.add(target)) continue;

                    List<DynamicRecipePatternDetails> candidates = patternsByTarget.get(target);
                    if (candidates != null) {
                        patternsToVisit.addAll(candidates);
                    }
                }

                if (patternsToVisit.isEmpty()) continue;
                IPatternDetails pattern = patternsToVisit.removeFirst();
                if (!visitedPatterns.add(pattern)) continue;

                if (pattern instanceof DynamicRecipePatternDetails) {
                    chainPatterns.add((DynamicRecipePatternDetails) pattern);
                }
                for (GenericStack output : pattern.getOutputs()) {
                    if (output != null) {
                        targetsToVisit.add(output.what());
                    }
                }
                for (IPatternDetails.IInput input : pattern.getInputs()) {
                    for (GenericStack possibleInput : input.possibleInputs()) {
                        if (possibleInput != null) {
                            targetsToVisit.add(possibleInput.what());
                        }
                    }
                }
            }
            return chainTargets;
        }

        private synchronized void armOptimalRebuild(AEKey target, long amount) {
            pendingOptimalRebuild = new OptimalRebuildRequest(target, amount);
        }

        private synchronized OptimalRebuildRequest claimOptimalRebuild(AEKey target, long amount) {
            OptimalRebuildRequest pending = pendingOptimalRebuild;
            if (pending == null || !pending.matches(target, amount)) {
                return null;
            }
            pendingOptimalRebuild = null;
            return pending;
        }

        private synchronized void cancelOptimalRebuild(AEKey target, long amount) {
            if (pendingOptimalRebuild != null && pendingOptimalRebuild.matches(target, amount)) {
                pendingOptimalRebuild = null;
            }
        }

        private synchronized int clearProviderPatterns(String providerId) {
            String recipeKeyPrefix = providerId + ':';
            Set<DynamicRecipePatternDetails> removedPatterns = new HashSet<>();
            for (Map.Entry<String, DynamicRecipePatternDetails> entry : patternsByRecipe.entrySet()) {
                if (!entry.getKey().startsWith(recipeKeyPrefix)) continue;
                if (patternsByRecipe.remove(entry.getKey(), entry.getValue())) {
                    removedPatterns.add(entry.getValue());
                }
            }

            Set<AEKey> targetsWithClearedRejections = new HashSet<>();
            for (Map.Entry<AEKey, Set<String>> entry : rejectedRecipeKeysByTarget.entrySet()) {
                Set<String> rejectedRecipeKeys = entry.getValue();
                if (rejectedRecipeKeys.removeIf(recipeKey -> recipeKey.startsWith(recipeKeyPrefix))) {
                    targetsWithClearedRejections.add(entry.getKey());
                }
                if (rejectedRecipeKeys.isEmpty()) {
                    rejectedRecipeKeysByTarget.remove(entry.getKey(), rejectedRecipeKeys);
                }
            }

            for (AEKey target : targetsWithClearedRejections) {
                patternsByTarget.remove(target);
            }
            if (removedPatterns.isEmpty()) return 0;

            removePatternsFromTargetCache(removedPatterns);
            return removedPatterns.size();
        }

        private void removePatternsFromTargetCache(Set<DynamicRecipePatternDetails> patterns) {
            List<AEKey> affectedTargets = new ArrayList<>();
            for (Map.Entry<AEKey, List<DynamicRecipePatternDetails>> entry : patternsByTarget.entrySet()) {
                for (DynamicRecipePatternDetails detail : entry.getValue()) {
                    if (patterns.contains(detail)) {
                        affectedTargets.add(entry.getKey());
                        break;
                    }
                }
            }
            for (AEKey target : affectedTargets) {
                patternsByTarget.remove(target);
            }
        }

        private synchronized int invalidateRecursiveCycleForOptimalRebuild(AEKey target,
                                                                            Collection<? extends IPatternDetails> patterns) {
            Set<DynamicRecipePatternDetails> cyclePatterns = new HashSet<>();
            for (IPatternDetails pattern : patterns) {
                DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
                if (dynamic != null && patternsByRecipe.get(dynamic.getRecipeKey()) == dynamic) {
                    cyclePatterns.add(dynamic);
                }
            }
            if (cyclePatterns.isEmpty()) return 0;

            Set<String> rejected = rejectedRecipeKeysByTarget.computeIfAbsent(target,
                    ignored -> ConcurrentHashMap.newKeySet());
            Set<DynamicRecipePatternDetails> newlyRejected = new HashSet<>();
            for (DynamicRecipePatternDetails detail : cyclePatterns) {
                if (rejected.add(detail.getRecipeKey())) {
                    newlyRejected.add(detail);
                }
            }
            if (newlyRejected.isEmpty()) return 0;

            int removedCount = invalidatePlanPatterns(newlyRejected);
            if (removedCount > 0) {
                ApplyGrayMod.LOGGER.info("Cleared {} cached lazy RecipeMap pattern(s) from a non-productive " +
                        "recursive cycle during an optimal rebuild for {}", removedCount, target);
            }
            return removedCount;
        }

        private synchronized int rejectRecursiveCycleAtOutput(AEKey target, IPatternDetails pattern) {
            DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
            if (dynamic == null || !dynamic.netProduces(target) ||
                    patternsByRecipe.get(dynamic.getRecipeKey()) != dynamic) {
                return 0;
            }

            Set<String> rejected = rejectedRecipeKeysByTarget.computeIfAbsent(target,
                    ignored -> ConcurrentHashMap.newKeySet());
            if (!rejected.add(dynamic.getRecipeKey())) return 0;

            return invalidatePlanPatterns(Collections.singleton(dynamic));
        }

        private boolean isPatternAvailableFor(AEKey target, DynamicRecipePatternDetails detail) {
            return detail.netProduces(target) && !isRejectedFor(target, detail);
        }

        private boolean isRejectedFor(AEKey target, DynamicRecipePatternDetails detail) {
            return isRejectedFor(target, detail.getRecipeKey());
        }

        private boolean isRejectedFor(AEKey target, String recipeKey) {
            Set<String> rejected = rejectedRecipeKeysByTarget.get(target);
            return rejected != null && rejected.contains(recipeKey);
        }

        private void clearGenerated() {
            patternsByTarget.clear();
            patternsByRecipe.clear();
            rejectedRecipeKeysByTarget.clear();
            invalidateRecipeOutputIndexes();
            providersByPattern.clear();
        }

        private void requestFullRecipeOutputIndexRebuild() {
            invalidateRecipeOutputIndexes();
            pendingFullRecipeOutputIndexEpoch = recipeOutputIndexEpoch;
        }

        private void invalidateRecipeOutputIndexes() {
            recipeOutputIndexEpoch++;
            recipeOutputIndexes.clear();
            pendingFullRecipeOutputIndexEpoch = 0;
        }
    }

    /**
     * Immutable, deterministic-output index for one RecipeMap. It is intentionally built only on the crafting worker
     * and yields through the active calculation after every recipe, so initial indexing cannot monopolize a tick.
     */
    private static final class RecipeOutputIndex {

        private final int recipeCount;
        private final int outputCount;
        private final Map<AEKey, List<Recipe>> recipesByOutput;

        private RecipeOutputIndex(int recipeCount, Map<AEKey, List<Recipe>> recipesByOutput) {
            this.recipeCount = recipeCount;
            this.outputCount = recipesByOutput.size();
            this.recipesByOutput = recipesByOutput;
        }

        private static RecipeOutputIndex create(Collection<Recipe> recipes) {
            Map<AEKey, List<Recipe>> mutableIndex = new HashMap<>();
            for (Recipe recipe : recipes) {
                if (!GridState.cooperateWithCraftingCalculation()) {
                    return null;
                }
                if (!recipe.getChancedOutputs().getChancedEntries().isEmpty() ||
                        !recipe.getChancedFluidOutputs().getChancedEntries().isEmpty()) {
                    continue;
                }

                for (ItemStack output : recipe.getOutputs()) {
                    addRecipe(mutableIndex, AEItemKey.of(output), recipe);
                }
                for (FluidStack output : recipe.getFluidOutputs()) {
                    addRecipe(mutableIndex, AEFluidKey.of(output), recipe);
                }
            }

            Map<AEKey, List<Recipe>> immutableIndex = new HashMap<>(mutableIndex.size());
            for (Map.Entry<AEKey, List<Recipe>> entry : mutableIndex.entrySet()) {
                immutableIndex.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            return new RecipeOutputIndex(recipes.size(), Collections.unmodifiableMap(immutableIndex));
        }

        private static void addRecipe(Map<AEKey, List<Recipe>> index, AEKey output, Recipe recipe) {
            if (output == null) return;
            List<Recipe> producingRecipes = index.computeIfAbsent(output, ignored -> new ArrayList<>());
            if (producingRecipes.isEmpty() || producingRecipes.get(producingRecipes.size() - 1) != recipe) {
                producingRecipes.add(recipe);
            }
        }

        private List<Recipe> getRecipes(AEKey target) {
            List<Recipe> recipes = recipesByOutput.get(target);
            return recipes == null ? Collections.emptyList() : recipes;
        }
    }

    private static KeyCounter getStoredItems(ProviderSnapshot source) {
        return source == null ? null : source.grid.getStorageService().getCachedInventory();
    }

    private static EncodedRecipe encodeRecipe(ProviderSnapshot source, Recipe recipe) {
        return encodeRecipe(recipe, getStoredItems(source));
    }

    private static EncodedRecipe encodeRecipe(Recipe recipe, KeyCounter storedItems) {
        return encodeRecipe(recipe, storedItems, DynamicRecipePatternRegistry::createProgrammableCircuit);
    }

    private static EncodedRecipe encodeRecipe(Recipe recipe, KeyCounter storedItems,
                                              Function<ItemStack, ItemStack> programmableCircuitFactory) {
        if (!recipe.getChancedOutputs().getChancedEntries().isEmpty() ||
                !recipe.getChancedFluidOutputs().getChancedEntries().isEmpty()) return null;
        if (producesGeneralCircuitBoard(recipe)) return null;

        List<GenericStack> inputs = new ArrayList<>();
        List<List<GenericStack>> alternatives = new ArrayList<>();
        int circuitConfiguration = -1;
        for (GTRecipeInput input : recipe.getInputs()) {
            if (input instanceof IntCircuitIngredient) {
                List<GenericStack> programmableOptions = encodeNonConsumableItem(input, storedItems,
                        programmableCircuitFactory);
                if (programmableOptions == null) return null;
                inputs.add(programmableOptions.get(0));
                alternatives.add(programmableOptions);
                continue;
            }
            if (input.isNonConsumable()) {
                List<GenericStack> programmableOptions = encodeNonConsumableItem(input, storedItems,
                        programmableCircuitFactory);
                if (programmableOptions == null) return null;
                inputs.add(programmableOptions.get(0));
                alternatives.add(programmableOptions);
                continue;
            }

            FluidStack fluid = input.getInputFluidStack();
            if (fluid != null) {
                FluidStack copy = fluid.copy();
                copy.amount = input.getAmount();
                GenericStack genericFluid = GenericStack.fromFluidStack(copy);
                if (genericFluid == null) return null;
                inputs.add(genericFluid);
                alternatives.add(Collections.singletonList(genericFluid));
                continue;
            }

            ItemStack[] choices = input.getInputStacks();
            if (choices.length == 0) return null;
            if (containsExternalOreInput(choices)) return null;
            List<GenericStack> options = new ArrayList<>();
            for (ItemStack choice : prioritizeItemChoices(choices, storedItems)) {
                ItemStack option = choice.copy();
                option.setCount(input.getAmount());
                GenericStack genericOption = GenericStack.fromItemStack(option);
                if (genericOption != null) options.add(genericOption);
            }
            if (options.isEmpty()) return null;
            inputs.add(options.get(0));
            alternatives.add(options);
        }
        for (GTRecipeInput input : recipe.getFluidInputs()) {
            if (input.isNonConsumable()) return null;
            FluidStack fluid = input.getInputFluidStack();
            if (fluid == null) return null;

            FluidStack copy = fluid.copy();
            copy.amount = input.getAmount();
            GenericStack genericFluid = GenericStack.fromFluidStack(copy);
            if (genericFluid == null) return null;
            inputs.add(genericFluid);
            alternatives.add(Collections.singletonList(genericFluid));
        }

        List<GenericStack> outputs = new ArrayList<>();
        for (ItemStack output : recipe.getOutputs()) {
            GenericStack genericOutput = GenericStack.fromItemStack(output);
            if (genericOutput != null) outputs.add(genericOutput);
        }
        for (FluidStack output : recipe.getFluidOutputs()) {
            GenericStack genericOutput = GenericStack.fromFluidStack(output);
            if (genericOutput != null) outputs.add(genericOutput);
        }
        if (inputs.isEmpty() || outputs.isEmpty() || inputs.size() > 81 || outputs.size() > 27) return null;
        return new EncodedRecipe(inputs, alternatives, outputs, circuitConfiguration);
    }

    /**
     * Converts one non-consumable item requirement into the corresponding programmable circuit.
     * Non-consumable fluids and multi-count item requirements have no equivalent virtual circuit representation.
     */
    private static List<GenericStack> encodeNonConsumableItem(GTRecipeInput input, KeyCounter storedItems,
                                                               Function<ItemStack, ItemStack> programmableCircuitFactory) {
        if (input.getInputFluidStack() != null || input.getAmount() != 1 ||
                programmableCircuitFactory == null) {
            return null;
        }

        ItemStack[] choices = input.getInputStacks();
        if (choices == null || choices.length == 0) return null;
        if (containsExternalOreInput(choices)) return null;

        List<GenericStack> programmableOptions = new ArrayList<>();
        for (ItemStack choice : prioritizeItemChoices(choices, storedItems)) {
            ItemStack programmable = programmableCircuitFactory.apply(choice);
            if (programmable == null || programmable.isEmpty()) return null;
            GenericStack genericProgrammable = GenericStack.fromItemStack(programmable);
            if (genericProgrammable != null) programmableOptions.add(genericProgrammable);
        }
        return programmableOptions.isEmpty() ? null : programmableOptions;
    }

    @Nullable
    private static ItemStack createProgrammableCircuit(ItemStack choice) {
        if (MetaItems.PROGRAMMABLE_CIRCUIT == null) {
            return null;
        }
        ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
        if (programmable.isEmpty()) {
            return null;
        }
        return ProgrammableCircuit.wrap(choice, programmable);
    }

    /**
     * Dynamic patterns use the first alternative as their default AE input. Universal circuit boards always come
     * first; remaining candidates prefer materials already present in the provider's AE network.
     */
    private static List<ItemStack> prioritizeGeneralCircuitBoards(ItemStack[] choices) {
        return prioritizeItemChoices(choices, null);
    }

    private static List<ItemStack> prioritizeItemChoices(ItemStack[] choices, KeyCounter storedItems) {
        List<ItemStack> storedGeneralCircuitBoards = new ArrayList<>(choices.length);
        List<ItemStack> storedOtherChoices = new ArrayList<>(choices.length);
        List<ItemStack> missingGeneralCircuitBoards = new ArrayList<>(choices.length);
        List<ItemStack> missingOtherChoices = new ArrayList<>(choices.length);
        for (ItemStack choice : choices) {
            if (choice == null || choice.isEmpty()) continue;
            if (isStoredItem(choice, storedItems)) {
                (isGeneralCircuitBoard(choice) ? storedGeneralCircuitBoards : storedOtherChoices).add(choice);
            } else {
                (isGeneralCircuitBoard(choice) ? missingGeneralCircuitBoards : missingOtherChoices).add(choice);
            }
        }
        List<ItemStack> orderedChoices = new ArrayList<>(choices.length);
        orderedChoices.addAll(storedGeneralCircuitBoards);
        orderedChoices.addAll(missingGeneralCircuitBoards);
        orderedChoices.addAll(storedOtherChoices);
        orderedChoices.addAll(missingOtherChoices);
        return orderedChoices;
    }

    private static boolean isStoredItem(ItemStack stack, KeyCounter storedItems) {
        if (storedItems == null) return false;
        AEItemKey itemKey = AEItemKey.of(stack);
        return itemKey != null && storedItems.get(itemKey) > 0;
    }

    private static boolean isGeneralCircuitBoard(ItemStack stack) {
        String translationKey = stack.getTranslationKey();
        return translationKey.startsWith(GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX) ||
                translationKey.startsWith("item." + GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX);
    }

    /**
     * General circuit boards are the default fallback input for virtual patterns, so their conversion recipes
     * must not themselves become virtual patterns.
     */
    private static boolean producesGeneralCircuitBoard(Recipe recipe) {
        for (ItemStack output : recipe.getOutputs()) {
            if (isGeneralCircuitBoard(output)) return true;
        }
        return false;
    }

    /**
     * Lower input per net output wins. Equal efficiencies prefer fewer total inputs, then more net output.
     */
    static int compareInputOutputEfficiency(long leftInput, long leftOutput,
                                            long rightInput, long rightOutput) {
        boolean leftProduces = leftOutput > 0;
        boolean rightProduces = rightOutput > 0;
        if (leftProduces != rightProduces) return leftProduces ? -1 : 1;
        if (leftProduces) {
            int perUnit = compareInputPerOutput(leftInput, leftOutput, rightInput, rightOutput);
            if (perUnit != 0) return perUnit;
        }

        int input = Long.compare(leftInput, rightInput);
        return input != 0 ? input : Long.compare(rightOutput, leftOutput);
    }

    private static int compareInputPerOutput(long leftInput, long leftOutput,
                                             long rightInput, long rightOutput) {
        try {
            return Long.compare(Math.multiplyExact(leftInput, rightOutput),
                    Math.multiplyExact(rightInput, leftOutput));
        } catch (ArithmeticException ignored) {
            return BigInteger.valueOf(leftInput).multiply(BigInteger.valueOf(rightOutput)).compareTo(
                    BigInteger.valueOf(rightInput).multiply(BigInteger.valueOf(leftOutput)));
        }
    }

    private static long estimateItemRawMaterialCost(GTRecipeInput input, KeyCounter storedItems) {
        ItemStack[] inputStacks = input.getInputStacks();
        if (inputStacks == null || inputStacks.length == 0) return 0;
        List<ItemStack> choices = prioritizeItemChoices(inputStacks, storedItems);
        return choices.isEmpty() ? 0 : estimateItemRawMaterialCost(choices.get(0), input.getAmount());
    }

    private static long estimateItemRawMaterialCost(ItemStack input, int amount) {
        MaterialStack material = OreDictUnifier.getMaterial(input);
        return estimateItemRawMaterialCost(material == null ? GTValues.M : material.amount, amount);
    }

    static long estimateItemRawMaterialCost(long materialAmount, int amount) {
        if (amount <= 0) return 0;
        return multiplySaturated(materialAmount > 0 ? materialAmount : GTValues.M, amount);
    }

    /** Converts fluid amounts to the same GT material-unit scale used for unified item inputs. */
    private static long estimateFluidRawMaterialCost(FluidStack fluid, int amount) {
        if (amount <= 0) return 0;
        int millibucketsPerUnit = isMoltenMaterialFluid(fluid) ?
                GTValues.L : STANDARD_FLUID_MILLIBUCKETS_PER_UNIT;
        return divideRoundUp(multiplySaturated(amount, GTValues.M), millibucketsPerUnit);
    }

    private static long addSaturated(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long multiplySaturated(long left, long right) {
        return left != 0 && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
    }

    private static long divideRoundUp(long value, long divisor) {
        return value == 0 ? 0 : (value - 1) / divisor + 1;
    }

    private static boolean isMoltenMaterialFluid(FluidStack fluidStack) {
        Material material = FluidUnifier.getMaterialFromFluid(fluidStack.getFluid());
        return material != null && material.hasFluid() &&
                material.getFluid(FluidStorageKeys.MOLTEN) == fluidStack.getFluid();
    }

    private static RouteCostBudget getRouteCostBudget() {
        if (ACTIVE_CRAFTING_CALCULATION.get() == null) {
            return new RouteCostBudget();
        }

        RouteCostBudget budget = ROUTE_COST_BUDGET.get();
        if (budget == null) {
            budget = new RouteCostBudget();
            ROUTE_COST_BUDGET.set(budget);
        }
        return budget;
    }

    private static List<IPatternDetails> getNormalPatternsForRouteCost(IGrid grid, AEKey target) {
        Boolean previous = NORMAL_PATTERN_COST_LOOKUP.get();
        NORMAL_PATTERN_COST_LOOKUP.set(Boolean.TRUE);
        try {
            Collection<IPatternDetails> mounted = grid.getCraftingService().getCraftingFor(target);
            if (mounted.isEmpty()) return Collections.emptyList();

            List<IPatternDetails> normal = new ArrayList<>(Math.min(mounted.size(),
                    MAX_NORMAL_PATTERNS_PER_TARGET));
            for (IPatternDetails pattern : mounted) {
                if (!(pattern instanceof DynamicRecipePatternDetails)) {
                    normal.add(pattern);
                    if (normal.size() >= MAX_NORMAL_PATTERNS_PER_TARGET) break;
                }
            }
            return normal;
        } finally {
            if (previous == null) {
                NORMAL_PATTERN_COST_LOOKUP.remove();
            } else {
                NORMAL_PATTERN_COST_LOOKUP.set(previous);
            }
        }
    }

    private static long estimateKeyMaterialAmount(AEKey key, long amount) {
        if (amount <= 0) return 0;
        if (key instanceof AEItemKey itemKey) {
            MaterialStack material = OreDictUnifier.getMaterial(itemKey.toStack());
            return multiplySaturated(material == null || material.amount <= 0 ? GTValues.M : material.amount,
                    amount);
        }
        if (key instanceof AEFluidKey fluidKey) {
            FluidStack fluid = fluidKey.toStack(1);
            int millibucketsPerUnit = isMoltenMaterialFluid(fluid) ?
                    GTValues.L : STANDARD_FLUID_MILLIBUCKETS_PER_UNIT;
            return divideRoundUp(multiplySaturated(amount, GTValues.M), millibucketsPerUnit);
        }
        return multiplySaturated(amount, GTValues.M);
    }

    /**
     * Bounded, inventory-consuming search over mounted AE patterns and dynamic RecipeMap edges.
     *
     * <p>Each root candidate receives an independent sparse stock ledger. Within one route, inputs share the ledger
     * so the same stored stack cannot make two dependencies look free. Normal mounted patterns suppress dynamic edges
     * for the same output, matching CraftingService behavior.</p>
     */
    private static final class RouteCostEstimator {

        private final IGrid grid;
        private final GridState state;
        private final RouteCostBudget budget;
        private final InventorySnapshot inventory;
        private final Map<AEKey, List<RouteEdge>> edgesByOutput = new HashMap<>();
        private final Map<AEKey, List<IPatternDetails>> normalPatternsByOutput = new HashMap<>();
        private final Set<AEKey> countedNormalTargets = new HashSet<>();
        private int currentRootExpansions;
        private int totalExpansions;
        private int normalPatternEdges;
        private int dynamicPatternEdges;
        private int boundedFallbacks;

        private RouteCostEstimator(IGrid grid, GridState state, RouteCostBudget budget) {
            this.grid = grid;
            this.state = state;
            this.budget = budget;
            inventory = new InventorySnapshot(grid.getStorageService().getCachedInventory());
        }

        private DirectRouteCost estimateDirect(IPatternDetails pattern) {
            return estimateDirect(RouteEdge.of(pattern));
        }

        private DirectRouteCost estimateDirect(PatternCandidate candidate) {
            return estimateDirect(RouteEdge.of(candidate));
        }

        private DirectRouteCost estimateDirect(RouteEdge edge) {
            InventoryLedger ledger = new InventoryLedger(inventory);
            DirectRouteCost total = DirectRouteCost.ZERO;
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                DirectRouteChoice best = null;
                int optionLimit = Math.min(options.length, MAX_ROUTE_COST_INPUT_ALTERNATIVES);
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;

                    long required = multiplySaturated(option.amount(), input.getMultiplier());
                    InventoryLedger branch = ledger.copy();
                    long fromStock = branch.consume(option.what(), required);
                    long remaining = required - fromStock;
                    boolean normalPattern = remaining > 0 && !getNormalEdges(option.what()).isEmpty();
                    DirectRouteCost optionCost = DirectRouteCost.input(option.what(), fromStock, remaining,
                            normalPattern);
                    DirectRouteChoice choice = new DirectRouteChoice(optionCost, branch);
                    if (best == null || choice.cost.compareTo(best.cost) < 0) {
                        best = choice;
                    }
                }
                if (best == null) {
                    total = total.plus(DirectRouteCost.UNRESOLVED);
                    continue;
                }
                ledger.replaceWith(best.ledger);
                total = total.plus(best.cost);
            }
            return total;
        }

        private RouteCost estimateRoot(IPatternDetails pattern, AEKey target) {
            return estimateRoot(RouteEdge.of(pattern), target);
        }

        private RouteCost estimateRoot(PatternCandidate candidate, AEKey target) {
            return estimateRoot(RouteEdge.of(candidate), target);
        }

        private RouteCost estimateRoot(RouteEdge edge, AEKey target) {
            currentRootExpansions = 0;
            InventoryLedger ledger = new InventoryLedger(inventory);
            Set<AEKey> path = new HashSet<>();
            path.add(target);
            return estimateEdge(edge, 1, ledger, path, 0);
        }

        private RouteCost estimateEdge(RouteEdge edge, long crafts, InventoryLedger ledger,
                                       Set<AEKey> path, int depth) {
            RouteCost total = RouteCost.executions(crafts, depth + 1);
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                RouteChoice best = null;
                int optionLimit = Math.min(options.length, MAX_ROUTE_COST_INPUT_ALTERNATIVES);
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;

                    long perCraft = multiplySaturated(option.amount(), input.getMultiplier());
                    long required = multiplySaturated(perCraft, crafts);
                    InventoryLedger branch = ledger.copy();
                    RouteCost optionCost = estimateKey(option.what(), required, branch, path, depth + 1);
                    RouteChoice choice = new RouteChoice(optionCost, branch);
                    if (best == null || choice.cost.compareTo(best.cost) < 0) {
                        best = choice;
                    }
                }
                if (best == null) {
                    boundedFallbacks++;
                    total = total.plus(RouteCost.bounded(depth + 1));
                    continue;
                }
                ledger.replaceWith(best.ledger);
                total = total.plus(best.cost);
            }
            return total;
        }

        private RouteCost estimateKey(AEKey key, long amount, InventoryLedger ledger,
                                      Set<AEKey> path, int depth) {
            long fromStock = ledger.consume(key, amount);
            RouteCost stockCost = RouteCost.stock(estimateKeyMaterialAmount(key, fromStock));
            long remaining = amount - fromStock;
            if (remaining <= 0) return stockCost;

            if (depth >= MAX_ROUTE_COST_DEPTH || currentRootExpansions++ >= MAX_ROUTE_COST_EXPANSIONS ||
                    !budget.tryExpansion()) {
                boundedFallbacks++;
                return stockCost.plus(RouteCost.bounded(depth));
            }
            totalExpansions++;
            if (!GridState.cooperateWithCraftingCalculation()) {
                boundedFallbacks++;
                return stockCost.plus(RouteCost.bounded(depth));
            }
            if (!path.add(key)) {
                return stockCost.plus(RouteCost.missing(key, remaining, depth));
            }

            try {
                List<RouteEdge> edges = getEdges(key);
                boolean hasMandatoryRoute = false;
                for (RouteEdge edge : edges) {
                    if (edge.hasMandatoryRoutePriority()) {
                        hasMandatoryRoute = true;
                        break;
                    }
                }
                RouteChoice best = null;
                for (RouteEdge edge : edges) {
                    if (hasMandatoryRoute && !edge.hasMandatoryRoutePriority()) {
                        continue;
                    }
                    long netOutput = edge.getNetOutput(key);
                    if (netOutput <= 0) continue;

                    long crafts = divideRoundUp(remaining, netOutput);
                    InventoryLedger branch = ledger.copy();
                    RouteCost patternCost = estimateEdge(edge, crafts, branch, path, depth);
                    for (GenericStack output : edge.outputs) {
                        branch.add(output.what(), multiplySaturated(output.amount(), crafts));
                    }
                    if (branch.consume(key, remaining) < remaining) {
                        continue;
                    }

                    RouteChoice choice = new RouteChoice(patternCost, branch);
                    if (best == null || choice.cost.compareTo(best.cost) < 0) {
                        best = choice;
                    }
                }

                if (best == null) {
                    return stockCost.plus(RouteCost.missing(key, remaining, depth));
                }
                ledger.replaceWith(best.ledger);
                return stockCost.plus(best.cost);
            } finally {
                path.remove(key);
            }
        }

        private List<RouteEdge> getEdges(AEKey target) {
            List<RouteEdge> cached = edgesByOutput.get(target);
            if (cached != null) return cached;

            List<IPatternDetails> normal = getNormalEdges(target);
            if (!normal.isEmpty()) {
                List<RouteEdge> result = new ArrayList<>(normal.size());
                for (IPatternDetails pattern : normal) {
                    result.add(RouteEdge.of(pattern));
                }
                result = Collections.unmodifiableList(result);
                edgesByOutput.put(target, result);
                return result;
            }

            List<PatternCandidate> dynamic = state.getCandidatesForRouteCost(target);
            dynamicPatternEdges += dynamic.size();
            List<RouteEdge> result = new ArrayList<>(dynamic.size());
            for (PatternCandidate candidate : dynamic) {
                result.add(RouteEdge.of(candidate));
            }
            result = Collections.unmodifiableList(result);
            edgesByOutput.put(target, result);
            return result;
        }

        private List<IPatternDetails> getNormalEdges(AEKey target) {
            List<IPatternDetails> normal = normalPatternsByOutput.computeIfAbsent(target,
                    key -> Collections.unmodifiableList(new ArrayList<>(getNormalPatternsForRouteCost(grid, key))));
            if (!normal.isEmpty() && countedNormalTargets.add(target)) {
                normalPatternEdges += normal.size();
            }
            return normal;
        }
    }

    /** Input/output metadata used by route scoring before a dynamic pattern is encoded or registered. */
    private static final class RouteEdge {

        private final IPatternDetails.IInput[] inputs;
        private final List<GenericStack> outputs;
        @Nullable private final CandidateRoutePriority routePriority;

        private RouteEdge(IPatternDetails.IInput[] inputs, List<GenericStack> outputs,
                          @Nullable CandidateRoutePriority routePriority) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.routePriority = routePriority;
        }

        private static RouteEdge of(IPatternDetails pattern) {
            DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
            return new RouteEdge(pattern.getInputs(), pattern.getOutputs(),
                    dynamic == null ? null : dynamic.getRoutePriority());
        }

        private static RouteEdge of(PatternCandidate candidate) {
            return new RouteEdge(DynamicRecipePatternDetails.createScoringInputs(candidate.encoded.inputs,
                    candidate.encoded.alternatives), candidate.encoded.outputs, candidate.cost.routePriority);
        }

        private boolean hasMandatoryRoutePriority() {
            return routePriority == CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS;
        }

        private long getNetOutput(AEKey target) {
            long output = 0;
            for (GenericStack stack : outputs) {
                if (target.matches(stack)) {
                    output = addSaturated(output, stack.amount());
                }
            }
            for (IPatternDetails.IInput input : inputs) {
                for (GenericStack option : input.possibleInputs()) {
                    if (target.matches(option)) {
                        long consumed = multiplySaturated(option.amount(), input.getMultiplier());
                        output = consumed >= output ? 0 : output - consumed;
                        break;
                    }
                }
            }
            return output;
        }
    }

    /** Hard lifetime bound for all recursive route scoring performed by one AE calculation. */
    private static final class RouteCostBudget {

        private final long deadlineNanos = System.nanoTime() + MAX_ROUTE_COST_CALCULATION_NANOS;
        private int expansions;

        private boolean tryExpansion() {
            if (expansions >= MAX_ROUTE_COST_CALCULATION_EXPANSIONS ||
                    System.nanoTime() >= deadlineNanos) {
                return false;
            }
            expansions++;
            return true;
        }
    }

    /** Keeps the first observed amount for each key instead of cloning every key in the network. */
    private static final class InventorySnapshot {

        private final KeyCounter source;
        private final Map<AEKey, Long> amounts = new HashMap<>();

        private InventorySnapshot(KeyCounter source) {
            this.source = source;
        }

        private long get(AEKey key) {
            return amounts.computeIfAbsent(key, candidate -> Math.max(0, source.get(candidate)));
        }
    }

    private static final class InventoryLedger {

        private final InventorySnapshot inventory;
        private Map<AEKey, Long> availableOverrides;

        private InventoryLedger(InventorySnapshot inventory) {
            this.inventory = inventory;
            availableOverrides = new HashMap<>();
        }

        private InventoryLedger(InventorySnapshot inventory, Map<AEKey, Long> availableOverrides) {
            this.inventory = inventory;
            this.availableOverrides = new HashMap<>(availableOverrides);
        }

        private InventoryLedger copy() {
            return new InventoryLedger(inventory, availableOverrides);
        }

        private void replaceWith(InventoryLedger source) {
            availableOverrides = new HashMap<>(source.availableOverrides);
        }

        private long consume(AEKey key, long amount) {
            if (key == null || amount <= 0) return 0;
            long stored = getAvailable(key);
            long consumed = Math.min(stored, amount);
            if (consumed > 0) {
                availableOverrides.put(key, stored - consumed);
            }
            return consumed;
        }

        private void add(AEKey key, long amount) {
            if (key != null && amount > 0) {
                availableOverrides.put(key, addSaturated(getAvailable(key), amount));
            }
        }

        private long getAvailable(AEKey key) {
            Long overridden = availableOverrides.get(key);
            return overridden == null ? inventory.get(key) : overridden;
        }
    }

    private static final class RouteChoice {

        private final RouteCost cost;
        private final InventoryLedger ledger;

        private RouteChoice(RouteCost cost, InventoryLedger ledger) {
            this.cost = cost;
            this.ledger = ledger;
        }
    }

    private static final class DirectRouteChoice {

        private final DirectRouteCost cost;
        private final InventoryLedger ledger;

        private DirectRouteChoice(DirectRouteCost cost, InventoryLedger ledger) {
            this.cost = cost;
            this.ledger = ledger;
        }
    }

    /** Cheap direct-input score used to select the only candidates that need recursive refinement. */
    private static final class DirectRouteCost implements Comparable<DirectRouteCost> {

        private static final DirectRouteCost ZERO = new DirectRouteCost(0, 0, 0, 0);
        private static final DirectRouteCost UNRESOLVED =
                new DirectRouteCost(1, 1, BOUNDED_ROUTE_COST_PENALTY, 0);

        private final int unresolvedInputs;
        private final int dependentInputs;
        private final long missingMaterials;
        private final long consumedStockMaterials;

        private DirectRouteCost(int unresolvedInputs, int dependentInputs, long missingMaterials,
                                long consumedStockMaterials) {
            this.unresolvedInputs = unresolvedInputs;
            this.dependentInputs = dependentInputs;
            this.missingMaterials = missingMaterials;
            this.consumedStockMaterials = consumedStockMaterials;
        }

        private static DirectRouteCost input(AEKey key, long fromStock, long remaining,
                                             boolean hasNormalPattern) {
            return new DirectRouteCost(remaining > 0 && !hasNormalPattern ? 1 : 0,
                    remaining > 0 ? 1 : 0, estimateKeyMaterialAmount(key, remaining),
                    estimateKeyMaterialAmount(key, fromStock));
        }

        private boolean isFullyStocked() {
            return dependentInputs == 0;
        }

        private DirectRouteCost plus(DirectRouteCost other) {
            return new DirectRouteCost(unresolvedInputs + other.unresolvedInputs,
                    dependentInputs + other.dependentInputs,
                    addSaturated(missingMaterials, other.missingMaterials),
                    addSaturated(consumedStockMaterials, other.consumedStockMaterials));
        }

        @Override
        public int compareTo(DirectRouteCost other) {
            int unresolved = Integer.compare(unresolvedInputs, other.unresolvedInputs);
            if (unresolved != 0) return unresolved;
            int dependencies = Integer.compare(dependentInputs, other.dependentInputs);
            if (dependencies != 0) return dependencies;
            int missing = Long.compare(missingMaterials, other.missingMaterials);
            if (missing != 0) return missing;
            return Long.compare(consumedStockMaterials, other.consumedStockMaterials);
        }

        @Override
        public String toString() {
            return "[unresolved=" + unresolvedInputs + ", dependencies=" + dependentInputs +
                    ", missing=" + missingMaterials + ", stock=" + consumedStockMaterials + ']';
        }
    }

    private static final class RouteCost implements Comparable<RouteCost> {

        private final long missingMaterials;
        private final int maxDepth;
        private final long executions;
        private final long consumedStockMaterials;
        private final int boundedFallbacks;

        private RouteCost(long missingMaterials, int maxDepth, long executions,
                          long consumedStockMaterials, int boundedFallbacks) {
            this.missingMaterials = missingMaterials;
            this.maxDepth = maxDepth;
            this.executions = executions;
            this.consumedStockMaterials = consumedStockMaterials;
            this.boundedFallbacks = boundedFallbacks;
        }

        private static RouteCost executions(long executions, int depth) {
            return new RouteCost(0, depth, executions, 0, 0);
        }

        private static RouteCost stock(long materials) {
            return new RouteCost(0, 0, 0, materials, 0);
        }

        private static RouteCost missing(AEKey key, long amount, int depth) {
            return new RouteCost(estimateKeyMaterialAmount(key, amount), depth, 0, 0, 0);
        }

        private static RouteCost bounded(int depth) {
            return new RouteCost(BOUNDED_ROUTE_COST_PENALTY, depth, 0, 0, 1);
        }

        private RouteCost plus(RouteCost other) {
            return new RouteCost(addSaturated(missingMaterials, other.missingMaterials),
                    Math.max(maxDepth, other.maxDepth), addSaturated(executions, other.executions),
                    addSaturated(consumedStockMaterials, other.consumedStockMaterials),
                    boundedFallbacks + other.boundedFallbacks);
        }

        @Override
        public int compareTo(RouteCost other) {
            int missing = Long.compare(missingMaterials, other.missingMaterials);
            if (missing != 0) return missing;
            int bounded = Integer.compare(boundedFallbacks, other.boundedFallbacks);
            if (bounded != 0) return bounded;
            int depth = Integer.compare(maxDepth, other.maxDepth);
            if (depth != 0) return depth;
            int executionCount = Long.compare(executions, other.executions);
            if (executionCount != 0) return executionCount;
            return Long.compare(consumedStockMaterials, other.consumedStockMaterials);
        }

        @Override
        public String toString() {
            return "[missing=" + missingMaterials + ", depth=" + maxDepth + ", executions=" + executions +
                    ", stock=" + consumedStockMaterials + ", bounded=" + boundedFallbacks + ']';
        }
    }

    /** Aggregates one explicit rebuild so diagnostics do not emit one line per generated pattern. */
    private static final class OptimalRebuildContext {
        private final int indexedRecipeMaps;
        private final int indexedRecipes;
        private final long indexRebuildMillis;
        private final long startedAt;
        private long matchingRecipeCandidates;
        private int requestedOutputs;
        private int generatedPatterns;
        private int reusedPatterns;
        private int chemicalSynthesisCandidates;
        private int dustOrFluidCandidates;
        private int ingotCandidates;
        private int generalCandidates;
        private int materialFormChangeCandidates;
        private int recyclingCandidates;
        private int selectedChemicalSynthesisPatterns;
        private int selectedDustOrFluidPatterns;
        private int selectedIngotPatterns;
        private int selectedGeneralPatterns;
        private int selectedMaterialFormChangePatterns;
        private int selectedRecyclingPatterns;
        private final Set<AEKey> elementalDustLeaves = new HashSet<>();
        private final Set<AEKey> inventoryScoredTargets = new HashSet<>();
        private int quickRouteCandidates;
        private int refinedRouteCandidates;
        private int stockOnlyRouteTargets;
        private int routeCostExpansions;
        private int normalPatternEdges;
        private int dynamicPatternEdges;
        private int boundedRouteCostFallbacks;
        private long routeScoringNanos;
        private long slowestRouteScoringNanos;
        private AEKey slowestRouteScoringTarget;

        private OptimalRebuildContext(int indexedRecipeMaps, int indexedRecipes, long indexRebuildMillis,
                                      long startedAt) {
            this.indexedRecipeMaps = indexedRecipeMaps;
            this.indexedRecipes = indexedRecipes;
            this.indexRebuildMillis = indexRebuildMillis;
            this.startedAt = startedAt;
        }

        private void recordCandidate(CandidateRoutePriority routePriority) {
            switch (routePriority) {
                case CHEMICAL_PRODUCT_SYNTHESIS -> chemicalSynthesisCandidates++;
                case DUST_OR_FLUID_INPUT -> dustOrFluidCandidates++;
                case INGOT_INPUT -> ingotCandidates++;
                case GENERAL -> generalCandidates++;
                case MATERIAL_FORM_CHANGE -> materialFormChangeCandidates++;
                case RECYCLING -> recyclingCandidates++;
            }
        }

        private void recordRouteCostEstimator(AEKey target, int quickCandidates, int refinedCandidates,
                                              boolean stockOnlySelection, long elapsedNanos,
                                              RouteCostEstimator estimator) {
            quickRouteCandidates += quickCandidates;
            refinedRouteCandidates += refinedCandidates;
            if (stockOnlySelection) {
                stockOnlyRouteTargets++;
            }
            routeCostExpansions += estimator.totalExpansions;
            normalPatternEdges += estimator.normalPatternEdges;
            dynamicPatternEdges += estimator.dynamicPatternEdges;
            boundedRouteCostFallbacks += estimator.boundedFallbacks;
            routeScoringNanos += elapsedNanos;
            if (elapsedNanos > slowestRouteScoringNanos) {
                slowestRouteScoringNanos = elapsedNanos;
                slowestRouteScoringTarget = target;
            }
        }

        private void recordFinalPlan(ICraftingPlan plan) {
            selectedChemicalSynthesisPatterns = 0;
            selectedDustOrFluidPatterns = 0;
            selectedIngotPatterns = 0;
            selectedGeneralPatterns = 0;
            selectedMaterialFormChangePatterns = 0;
            selectedRecyclingPatterns = 0;
            for (IPatternDetails details : plan.patternTimes().keySet()) {
                DynamicRecipePatternDetails dynamic = getDynamicPattern(details);
                if (dynamic == null) continue;
                switch (dynamic.getRoutePriority()) {
                    case CHEMICAL_PRODUCT_SYNTHESIS -> selectedChemicalSynthesisPatterns++;
                    case DUST_OR_FLUID_INPUT -> selectedDustOrFluidPatterns++;
                    case INGOT_INPUT -> selectedIngotPatterns++;
                    case GENERAL -> selectedGeneralPatterns++;
                    case MATERIAL_FORM_CHANGE -> selectedMaterialFormChangePatterns++;
                    case RECYCLING -> selectedRecyclingPatterns++;
                }
            }
        }
    }

    private static final class OptimalRebuildRequest {

        private final AEKey target;
        private final long amount;

        private OptimalRebuildRequest(AEKey target, long amount) {
            this.target = target;
            this.amount = amount;
        }

        private boolean matches(AEKey otherTarget, long otherAmount) {
            return target.equals(otherTarget) && amount == otherAmount;
        }
    }

    private static final class EncodedRecipe {
        private final List<GenericStack> inputs;
        private final List<List<GenericStack>> alternatives;
        private final List<GenericStack> outputs;
        private final int circuitConfiguration;

        private EncodedRecipe(List<GenericStack> inputs, List<List<GenericStack>> alternatives,
                              List<GenericStack> outputs,
                              int circuitConfiguration) {
            this.inputs = inputs;
            this.alternatives = alternatives;
            this.outputs = outputs;
            this.circuitConfiguration = circuitConfiguration;
        }

        private EncodedRecipe withOutputs(List<GenericStack> patternOutputs) {
            return new EncodedRecipe(inputs, alternatives, patternOutputs, circuitConfiguration);
        }
    }

    private static final class PatternCandidate {
        private final ProviderSnapshot source;
        private final RecipeMap<?> recipeMap;
        private final EncodedRecipe encoded;
        private final Cost cost;
        private final String recipeKey;

        private PatternCandidate(ProviderSnapshot source, RecipeMap<?> recipeMap, Recipe recipe, AEKey target,
                                 EncodedRecipe encoded, Cost cost) {
            this.source = source;
            this.recipeMap = recipeMap;
            this.encoded = encoded;
            this.cost = cost;
            String baseRecipeKey = source.providerId + ':' + recipeMap.getUnlocalizedName() + ':' + recipe.hashCode();
            this.recipeKey = createTargetedRecipeKey(baseRecipeKey, target);
        }
    }

    enum CandidateRoutePriority {
        CHEMICAL_PRODUCT_SYNTHESIS,
        DUST_OR_FLUID_INPUT,
        INGOT_INPUT,
        GENERAL,
        MATERIAL_FORM_CHANGE,
        RECYCLING
    }

    static int compareCandidateRoutePriority(CandidateRoutePriority left, CandidateRoutePriority right) {
        return Integer.compare(left.ordinal(), right.ordinal());
    }

    /** Chemical-product synthesis is a policy requirement rather than an inventory-dependent preference. */
    static int compareMandatoryRoutePriority(CandidateRoutePriority left, CandidateRoutePriority right) {
        boolean leftMandatory = left == CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS;
        boolean rightMandatory = right == CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS;
        return leftMandatory == rightMandatory ? 0 : leftMandatory ? -1 : 1;
    }

    private static final class Cost implements Comparable<Cost> {
        private final long rawMaterials;
        private final long netOutput;
        private final int steps;
        private final CandidateRoutePriority routePriority;

        private Cost(long rawMaterials, long netOutput, int steps, CandidateRoutePriority routePriority) {
            this.rawMaterials = rawMaterials;
            this.netOutput = netOutput;
            this.steps = steps;
            this.routePriority = routePriority;
        }

        private static Cost fallback(Recipe recipe, KeyCounter storedItems, long netOutput,
                                     CandidateRoutePriority routePriority) {
            long raw = 0;
            for (GTRecipeInput input : recipe.getInputs()) {
                if (input instanceof IntCircuitIngredient || input.isNonConsumable()) continue;
                FluidStack fluid = input.getInputFluidStack();
                raw = addSaturated(raw, fluid == null ? estimateItemRawMaterialCost(input, storedItems) :
                        estimateFluidRawMaterialCost(fluid, input.getAmount()));
            }
            for (GTRecipeInput input : recipe.getFluidInputs()) {
                if (input.isNonConsumable()) continue;
                FluidStack fluid = input.getInputFluidStack();
                if (fluid != null) raw = addSaturated(raw, estimateFluidRawMaterialCost(fluid, input.getAmount()));
            }
            return new Cost(raw, netOutput, 1, routePriority);
        }

        @Override
        public int compareTo(Cost other) {
            int route = compareCandidateRoutePriority(routePriority, other.routePriority);
            if (route != 0) return route;
            int efficiency = compareInputOutputEfficiency(rawMaterials, netOutput,
                    other.rawMaterials, other.netOutput);
            return efficiency != 0 ? efficiency : Integer.compare(steps, other.steps);
        }
    }
}
