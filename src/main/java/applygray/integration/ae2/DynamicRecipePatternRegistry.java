package applygray.integration.ae2;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.NonConsumableTokenLayout;
import applygray.integration.ae2.recipe.NormalizedRecipe;
import applygray.integration.ae2.recipe.RecipeFingerprint;
import applygray.integration.ae2.recipe.RecipeBindingResolver;
import applygray.integration.ae2.recipe.TargetedRecipe;
import applygray.integration.ae2.rules.BudgetExhaustionPolicy;
import applygray.integration.ae2.rules.CyclePolicy;
import applygray.integration.ae2.rules.OutputPolicy;
import applygray.integration.ae2.rules.PlanningBudget;
import applygray.integration.ae2.rules.PlanningMode;
import applygray.integration.ae2.rules.RecipePatternRules;
import applygray.integration.ae2.rules.RuleContext;
import applygray.integration.ae2.rules.RuleDecision;
import applygray.mixins.supergiant.InvokerCraftingCalculation;

import gregtech.api.GTValues;
import gregtech.api.fluids.store.FluidStorageKeys;
import gregtech.api.unification.FluidUnifier;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.properties.PropertyKey;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
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
    private static final long BOUNDED_ROUTE_COST_PENALTY = Long.MAX_VALUE / 4;
    private static final String GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX = "metaitem.general_circuit.";
    private static final Map<IGrid, GridState> GRIDS = new ConcurrentHashMap<>();
    private static final Map<String, IGrid> PROVIDER_GRIDS = new ConcurrentHashMap<>();
    private static final int MAX_PROVIDER_DIAGNOSTIC_EVENTS = 24;
    private static final Map<String, ProviderDiagnosticLog> PROVIDER_DIAGNOSTICS = new ConcurrentHashMap<>();
    private static final RecipePatternPlanningMetrics PLANNING_METRICS = new RecipePatternPlanningMetrics();
    /** 
     * Cooldown: skip refreshProvider if called more often than this (500 ms). 
     * Prevents rapid onMainNodeStateChanged + update() cycles from doing duplicate work.
     */
    private static final long REFRESH_DEBOUNCE_NANOS = 500_000_000L;
    private static final Map<String, Long> LAST_REFRESH_NANOS = new ConcurrentHashMap<>();
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
        refreshProvider(provider, false);
    }

    /** Replaces a provider snapshot immediately after an administrator changes its planning configuration. */
    public static void refreshProviderImmediately(MetaTileEntityMERecipeMapPatternProvider provider) {
        refreshProvider(provider, true);
    }

    private static void refreshProvider(MetaTileEntityMERecipeMapPatternProvider provider, boolean force) {
        String providerId = provider.getDynamicProviderId();
        long now = System.nanoTime();
        Long lastRefresh = LAST_REFRESH_NANOS.get(providerId);
        if (!force && lastRefresh != null && (now - lastRefresh) < REFRESH_DEBOUNCE_NANOS) {
            return;
        }
        LAST_REFRESH_NANOS.put(providerId, now);

        ProviderSnapshot snapshot = provider.createDynamicSnapshot();
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
        PROVIDER_DIAGNOSTICS.remove(providerId);
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

    /** Returns a stable copy of the most recent rule/candidate decisions for one Provider diagnostics view. */
    public static List<ProviderDiagnosticEvent> getProviderDiagnostics(String providerId) {
        ProviderDiagnosticLog diagnostics = PROVIDER_DIAGNOSTICS.get(providerId);
        return diagnostics == null ? Collections.emptyList() : diagnostics.snapshot();
    }

    /** Formats a compact, UI-safe summary of the last candidate decision without exposing mutable planner state. */
    public static String getProviderDiagnosticsText(String providerId, int maximumEvents) {
        List<ProviderDiagnosticEvent> events = getProviderDiagnostics(providerId);
        if (events.isEmpty()) return "尚无路线诊断";
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, events.size() - Math.max(1, maximumEvents));
        for (int index = start; index < events.size(); index++) {
            if (text.length() > 0) text.append('\n');
            text.append(events.get(index).toDisplayString());
        }
        return text.toString();
    }

    /** Returns an allocation-safe, global summary for the Provider diagnostics panel. */
    public static String getPlanningMetricsSummary() {
        return PLANNING_METRICS.snapshot().summarize();
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
        PlanningMode mode = left.getPlanningMode() == right.getPlanningMode() ?
                left.getPlanningMode() : PlanningMode.STOCK_FIRST;
        return compareDynamicPatternPriority(requested, left, right, mode);
    }

    private static int compareDynamicPatternPriority(AEKey requested, DynamicRecipePatternDetails left,
                                                     DynamicRecipePatternDetails right, PlanningMode mode) {
        long leftOutput = requested == null ? 0 : left.getNetOutputAmount(requested);
        long rightOutput = requested == null ? 0 : right.getNetOutputAmount(requested);
        int comparison;
        if (mode == PlanningMode.SAFE_FIRST) {
            comparison = Integer.compare(left.getHiddenActualOutputs().size(), right.getHiddenActualOutputs().size());
            if (comparison != 0) return comparison;
            comparison = Long.compare(left.getCycleRiskPenalty(), right.getCycleRiskPenalty());
            if (comparison != 0) return comparison;
        } else if (mode == PlanningMode.THROUGHPUT_FIRST) {
            comparison = Integer.compare(left.getStepCost(), right.getStepCost());
            if (comparison != 0) return comparison;
        } else if (mode == PlanningMode.STOCK_FIRST || mode == PlanningMode.PINNED) {
            comparison = compareDynamicRoutePolicy(left, right);
            if (comparison != 0) return comparison;
        }
        int efficiency = compareInputOutputEfficiency(left.getRawMaterialCost(), leftOutput,
                right.getRawMaterialCost(), rightOutput);
        if (efficiency != 0) return efficiency;
        int steps = Integer.compare(left.getStepCost(), right.getStepCost());
        if (steps != 0) return steps;
        comparison = compareDynamicRoutePolicy(left, right);
        return comparison != 0 ? comparison : left.getRecipeKey().compareTo(right.getRecipeKey());
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
        PlanningMode planningMode = resolveDetailPlanningMode(patterns);
        Map<IPatternDetails, DirectRouteCost> quickCosts = new IdentityHashMap<>();
        for (IPatternDetails pattern : patterns) {
            quickCosts.put(pattern, estimator.estimateDirect(pattern));
        }
        patterns.sort((left, right) -> {
            DynamicRecipePatternDetails leftDynamic = (DynamicRecipePatternDetails) left;
            DynamicRecipePatternDetails rightDynamic = (DynamicRecipePatternDetails) right;
            int staticCost = compareDynamicPatternPriority(requested, leftDynamic, rightDynamic, planningMode);
            int quickCost = quickCosts.get(left).compareTo(quickCosts.get(right));
            if (planningMode == PlanningMode.STOCK_FIRST) {
                if (quickCost != 0) return quickCost;
                return staticCost;
            }
            if (staticCost != 0) return staticCost;
            return quickCost;
        });

        boolean stockOnlySelection = quickCosts.get(patterns.get(0)).isFullyStocked();
        Map<IPatternDetails, RouteCost> refinedCosts = new IdentityHashMap<>();
        if (!stockOnlySelection) {
            List<IPatternDetails> refined = new ArrayList<>(getPlanningBudget().getMaxRefinedCandidates());
            refined.add(patterns.get(0));

            IPatternDetails staticBest = patterns.get(0);
            for (int index = 1; index < patterns.size(); index++) {
                IPatternDetails candidate = patterns.get(index);
                if (compareDynamicPatternPriority(requested, (DynamicRecipePatternDetails) candidate,
                        (DynamicRecipePatternDetails) staticBest, planningMode) < 0) {
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
                int staticCost = compareDynamicPatternPriority(requested, (DynamicRecipePatternDetails) left,
                        (DynamicRecipePatternDetails) right, planningMode);
                int routeCost = refinedCosts.get(left).compareTo(refinedCosts.get(right));
                if (planningMode == PlanningMode.STOCK_FIRST) {
                    if (routeCost != 0) return routeCost;
                    return staticCost;
                }
                if (staticCost != 0) return staticCost;
                return routeCost;
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
        long amount = 0;
        for (GenericStack output : recipeOutputs) {
            if (requested.matches(output)) {
                amount = addSaturated(amount, output.amount());
            }
        }
        return amount <= 0 ? Collections.emptyList() :
                Collections.singletonList(new GenericStack(requested, amount));
    }

    private static List<GenericStack> selectHiddenActualOutputs(AEKey target, List<GenericStack> actualOutputs) {
        List<GenericStack> hidden = new ArrayList<>();
        for (GenericStack output : actualOutputs) {
            if (output != null && output.amount() > 0 && !target.matches(output)) {
                hidden.add(output);
            }
        }
        return Collections.unmodifiableList(hidden);
    }

    private static PlanningMode getPlanningMode(@Nullable ProviderSnapshot source) {
        if (source != null && source.planningMode == PlanningMode.PINNED) return PlanningMode.PINNED;
        if (isOptimalRebuildCalculation()) return PlanningMode.RESOURCE_FIRST;
        return source == null ? PlanningMode.STOCK_FIRST : source.planningMode;
    }

    /** A Provider's explicit pinned mode is administrative and cannot be loosened by a lower rule profile. */
    private static PlanningMode resolveCandidatePlanningMode(ProviderSnapshot source, RuleDecision decision) {
        PlanningMode providerMode = getPlanningMode(source);
        if (providerMode == PlanningMode.PINNED || decision.getPlanningModeOverride() == null) {
            return providerMode;
        }
        return decision.getPlanningModeOverride();
    }

    /** Returns the active immutable budget; worker sessions capture it in {@link RouteCostBudget} on first use. */
    private static PlanningBudget getPlanningBudget() {
        return RecipePatternRules.getActive().getPlanningBudget();
    }

    /**
     * Exposes material relationships as neutral facts. Route ordering is intentionally left to the rule package;
     * this method must not choose a route group or assign a priority.
     */
    private static Map<String, Object> createRuleFacts(AEKey target, RecipeMap<?> recipeMap, Recipe recipe,
                                                        NormalizedRecipe normalized, EncodedRecipe encoded,
                                                        KeyCounter storedItems) {
        Map<String, Object> facts = new HashMap<>();
        Material targetMaterial = getMaterialForKey(target);
        boolean usesDust = false;
        boolean usesPriorityFluid = false;
        boolean usesElementalFluid = false;
        boolean usesIngot = false;
        boolean hasMaterialInput = false;
        boolean hasTargetMaterialInput = false;
        boolean hasNonTargetMaterialInput = false;
        boolean onlyTargetMaterialInputs = targetMaterial != null;
        Set<String> inputMaterials = new HashSet<>();
        Set<String> inputOrePrefixes = new HashSet<>();
        Set<String> deterministicOutputMaterials = new HashSet<>();
        Set<String> chancedOutputMaterials = new HashSet<>();

        for (GenericStack input : encoded.inputs) {
            AEKey inputKey = input.what();
            Material inputMaterial = getMaterialForKey(inputKey);
            if (inputKey instanceof AEFluidKey) {
                usesPriorityFluid |= isPriorityFluidInput(targetMaterial, inputMaterial);
                usesElementalFluid |= isElementalMaterial(inputMaterial);
            } else if (inputKey instanceof AEItemKey itemKey) {
                UnificationEntry entry = OreDictUnifier.getUnificationEntry(itemKey.toStack());
                if (entry != null && entry.orePrefix != null) {
                    String prefixName = entry.orePrefix.name();
                    inputOrePrefixes.add(prefixName);
                    usesDust |= isDustPrefix(prefixName);
                    usesIngot |= isIngotPrefix(prefixName);
                }
            }

            if (inputMaterial != null) {
                inputMaterials.add(inputMaterial.getName());
                hasMaterialInput = true;
                if (targetMaterial != null && targetMaterial.equals(inputMaterial)) {
                    hasTargetMaterialInput = true;
                } else {
                    onlyTargetMaterialInputs = false;
                    hasNonTargetMaterialInput = true;
                }
            }
        }
        for (GenericStack output : normalized.getDeterministicOutputs()) {
            Material material = getMaterialForKey(output.what());
            if (material != null) deterministicOutputMaterials.add(material.getName());
        }
        for (GenericStack output : normalized.getChancedOutputStacks()) {
            Material material = getMaterialForKey(output.what());
            if (material != null) chancedOutputMaterials.add(material.getName());
        }

        usesPriorityFluid |= !isElementalMaterial(targetMaterial) &&
                isPrimaryElementalFluidRoute(usesElementalFluid, usesDust, usesIngot, hasTargetMaterialInput);
        facts.put("recycling", isRecyclingRecipe(recipe));
        facts.put("targetIsFluid", target instanceof AEFluidKey);
        facts.put("targetIsPolymer", targetMaterial != null && targetMaterial.hasProperty(PropertyKey.POLYMER));
        facts.put("targetMaterial", targetMaterial == null ? "" : targetMaterial.getName());
        facts.put("targetOrePrefix", getOrePrefixForKey(target));
        facts.put("inputMaterials", Collections.unmodifiableSet(inputMaterials));
        facts.put("inputOrePrefixes", Collections.unmodifiableSet(inputOrePrefixes));
        facts.put("deterministicOutputMaterials", Collections.unmodifiableSet(deterministicOutputMaterials));
        facts.put("chancedOutputMaterials", Collections.unmodifiableSet(chancedOutputMaterials));
        facts.put("craftTweakerRecipe", recipe.getIsCTRecipe());
        facts.put("groovyRecipe", recipe.isGroovyRecipe());
        facts.put("usesDustInput", usesDust);
        facts.put("usesPriorityFluidInput", usesPriorityFluid);
        facts.put("usesIngotInput", usesIngot);
        facts.put("hasMaterialInput", hasMaterialInput);
        facts.put("hasTargetMaterialInput", hasTargetMaterialInput);
        facts.put("hasNonTargetMaterialInput", hasNonTargetMaterialInput);
        facts.put("onlyTargetMaterialInputs", onlyTargetMaterialInputs);
        // These aliases retain neutral material facts for pack rules without affecting Java-side candidate ordering.
        facts.put("dustOrFluidInput", usesDust || usesPriorityFluid);
        facts.put("ingotInput", usesIngot);
        facts.put("materialFormChange", hasMaterialInput && onlyTargetMaterialInputs);
        facts.put("generalRoute", true);
        facts.put("hasChancedOutputs", normalized.hasChancedOutputs());
        facts.put("nonConsumableFluid", normalized.getFluidInputs().stream()
                .anyMatch(NormalizedRecipe.NormalizedInput::isNonConsumable));
        facts.put("recipeMap", recipeMap.getUnlocalizedName());
        facts.put("recipeCategory", normalized.getCategory());
        facts.put("target", RecipeFingerprint.describeKey(target));
        long storedTargetAmount = storedItems == null ? 0 : Math.max(0, storedItems.get(target));
        facts.put("targetStoredAmount", storedTargetAmount);
        facts.put("targetInStock", storedTargetAmount > 0);
        facts.put("tokenSlots", encoded.tokenLayout.getRequiredVirtualSlots());
        facts.put("eut", normalized.getEUt());
        facts.put("duration", normalized.getDuration());
        return facts;
    }

    /** Legacy display category derived from rule tags; it must never influence candidate ordering. */
    private static CandidateRoutePriority getDiagnosticRoutePriority(RuleDecision decision) {
        Set<String> tags = decision.getTags();
        if (tags.contains("route.recycling")) return CandidateRoutePriority.RECYCLING;
        if (tags.contains("route.chemical_synthesis")) return CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS;
        if (tags.contains("route.dust_or_fluid")) return CandidateRoutePriority.DUST_OR_FLUID_INPUT;
        if (tags.contains("route.ingot")) return CandidateRoutePriority.INGOT_INPUT;
        if (tags.contains("route.material_form_change")) return CandidateRoutePriority.MATERIAL_FORM_CHANGE;
        return CandidateRoutePriority.GENERAL;
    }

    private static void logCandidateDecision(ProviderSnapshot source, AEKey target, RecipeMap<?> recipeMap,
                                             NormalizedRecipe normalized, RuleDecision decision,
                                             String decisionName, String reasonCode, long startedAtNanos) {
        long elapsedMillis = startedAtNanos <= 0 ? 0 : Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        PlanningMode planningMode = resolveCandidatePlanningMode(source, decision);
        ProviderDiagnosticEvent event = new ProviderDiagnosticEvent(source.providerId, source.position,
                source.dimension, String.valueOf(target), recipeMap.getUnlocalizedName(),
                normalized.getRecipeFingerprint(), source.ruleSetVersion, planningMode, decisionName, reasonCode,
                elapsedMillis);
        PROVIDER_DIAGNOSTICS.computeIfAbsent(source.providerId, ignored -> new ProviderDiagnosticLog()).record(event);
        if (!ApplyGrayMod.LOGGER.isDebugEnabled()) return;
        ApplyGrayMod.LOGGER.debug("Recipe-pattern decision providerId={} position={} dimension={} target={} " +
                        "recipeMapId={} recipeFingerprint={} ruleSetVersion={} planningMode={} decision={} " +
                        "reasonCode={} elapsedMs={}",
                source.providerId, source.position, source.dimension, target, recipeMap.getUnlocalizedName(),
                normalized.getRecipeFingerprint(), source.ruleSetVersion, planningMode, decisionName,
                reasonCode, elapsedMillis);
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

    @Nullable
    private static String getOrePrefixForKey(AEKey key) {
        if (!(key instanceof AEItemKey itemKey)) return null;
        UnificationEntry entry = OreDictUnifier.getUnificationEntry(itemKey.toStack());
        return entry == null || entry.orePrefix == null ? null : entry.orePrefix.name();
    }

    static boolean isElementalMaterial(Material material) {
        return material != null && material.isElement();
    }

    static boolean isDynamicRecipeMapEnabled(String recipeMapName) {
        return recipeMapName != null && !recipeMapName.isEmpty();
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
        return recipeKey + ":target:" + RecipeFingerprint.describeKey(target);
    }

    public static final class ProviderSnapshot {

        private final IGrid grid;
        private final String providerId;
        private final long epoch;
        private final RecipeMap<?>[] recipeMaps;
        private final MachineCapabilityProfile machineProfile;
        private final String ruleSetVersion;
        private final PlanningMode planningMode;
        private final String pinnedRouteGroup;
        private final String position;
        private final int dimension;
        private final MetaTileEntityMERecipeMapPatternProvider provider;

        public ProviderSnapshot(IGrid grid, String providerId, long epoch, RecipeMap<?>[] recipeMaps,
                                MachineCapabilityProfile machineProfile,
                                PlanningMode planningMode, String pinnedRouteGroup,
                                MetaTileEntityMERecipeMapPatternProvider provider) {
            this.grid = grid;
            this.providerId = providerId;
            this.epoch = epoch;
            this.recipeMaps = Arrays.copyOf(recipeMaps, recipeMaps.length);
            this.machineProfile = machineProfile;
            this.ruleSetVersion = RecipePatternRules.getActive().getVersion();
            this.planningMode = planningMode == null ? PlanningMode.STOCK_FIRST : planningMode;
            this.pinnedRouteGroup = pinnedRouteGroup == null ? "" : pinnedRouteGroup;
            this.position = provider.getPos().toString();
            this.dimension = machineProfile.getDimension();
            this.provider = provider;
        }

        private boolean sameDefinition(ProviderSnapshot other) {
            return other != null && epoch == other.epoch && Arrays.equals(recipeMaps, other.recipeMaps) &&
                    machineProfile.getVersion().equals(other.machineProfile.getVersion()) &&
                    ruleSetVersion.equals(other.ruleSetVersion) && planningMode == other.planningMode &&
                    pinnedRouteGroup.equals(other.pinnedRouteGroup) && provider == other.provider;
        }
    }

    /** Immutable, structured candidate decision retained for the Provider diagnostics view. */
    public static final class ProviderDiagnosticEvent {

        private final String providerId;
        private final String position;
        private final int dimension;
        private final String target;
        private final String recipeMapId;
        private final String recipeFingerprint;
        private final String ruleSetVersion;
        private final PlanningMode planningMode;
        private final String decision;
        private final String reasonCode;
        private final long elapsedMillis;

        private ProviderDiagnosticEvent(String providerId, String position, int dimension, String target,
                                        String recipeMapId, String recipeFingerprint, String ruleSetVersion,
                                        PlanningMode planningMode, String decision, String reasonCode,
                                        long elapsedMillis) {
            this.providerId = providerId;
            this.position = position;
            this.dimension = dimension;
            this.target = target;
            this.recipeMapId = recipeMapId;
            this.recipeFingerprint = recipeFingerprint;
            this.ruleSetVersion = ruleSetVersion;
            this.planningMode = planningMode;
            this.decision = decision;
            this.reasonCode = reasonCode;
            this.elapsedMillis = elapsedMillis;
        }

        public String getProviderId() {
            return providerId;
        }

        public String getPosition() {
            return position;
        }

        public int getDimension() {
            return dimension;
        }

        public String getTarget() {
            return target;
        }

        public String getRecipeMapId() {
            return recipeMapId;
        }

        public String getRecipeFingerprint() {
            return recipeFingerprint;
        }

        public String getRuleSetVersion() {
            return ruleSetVersion;
        }

        public PlanningMode getPlanningMode() {
            return planningMode;
        }

        public String getDecision() {
            return decision;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }

        private String toDisplayString() {
            String fingerprint = recipeFingerprint == null ? "?" :
                    recipeFingerprint.substring(0, Math.min(8, recipeFingerprint.length()));
            return decision + ' ' + reasonCode + " " + recipeMapId + '#' + fingerprint + " " + elapsedMillis +
                    "ms";
        }
    }

    private static final class ProviderDiagnosticLog {

        private final Deque<ProviderDiagnosticEvent> events = new ArrayDeque<>();

        private synchronized void record(ProviderDiagnosticEvent event) {
            events.addLast(event);
            while (events.size() > MAX_PROVIDER_DIAGNOSTIC_EVENTS) events.removeFirst();
        }

        private synchronized List<ProviderDiagnosticEvent> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    /** Called by the RecipeMap mixin whenever scripts or reloads mutate a registered RecipeMap. */
    public static void invalidateRecipeMapContents(RecipeMap<?> recipeMap) {
        if (recipeMap == null) return;
        RecipeBindingResolver.invalidate(recipeMap);
        int discardedCachedPatterns = 0;
        for (GridState state : GRIDS.values()) {
            discardedCachedPatterns += state.invalidateRecipeMapContents(recipeMap);
        }
        ApplyGrayMod.LOGGER.info("Invalidated dynamic RecipeMap pattern indexes after {} changed; discarded {} " +
                        "stale provider cache entries",
                recipeMap.getUnlocalizedName(), discardedCachedPatterns);
    }

    /** Called after an atomic rule-set swap so no new request can consume candidates scored by the old rules. */
    public static void invalidateRuleSetContents(String previousVersion, String currentVersion) {
        if (currentVersion == null || currentVersion.equals(previousVersion)) return;
        int discardedCachedPatterns = 0;
        for (GridState state : GRIDS.values()) {
            discardedCachedPatterns += state.invalidateRuleSetContents();
        }
        ApplyGrayMod.LOGGER.info("Invalidated dynamic RecipeMap pattern indexes after rule set changed {} -> {}; " +
                        "discarded {} stale provider cache entries",
                abbreviateVersion(previousVersion), abbreviateVersion(currentVersion), discardedCachedPatterns);
    }

    /** Invalidates candidate facts when an adapter is registered after providers have already started planning. */
    public static void invalidateRuleEngineContents(String source) {
        int discardedCachedPatterns = 0;
        for (GridState state : GRIDS.values()) {
            discardedCachedPatterns += state.invalidateRuleSetContents();
        }
        ApplyGrayMod.LOGGER.info("Invalidated dynamic RecipeMap pattern indexes after {} changed; discarded {} " +
                        "stale provider cache entries",
                source == null || source.isEmpty() ? "a rule-engine extension" : source, discardedCachedPatterns);
    }

    private static String abbreviateVersion(String version) {
        if (version == null) return "<none>";
        return version.substring(0, Math.min(12, version.length()));
    }

    private static int compareDynamicRoutePolicy(DynamicRecipePatternDetails left,
                                                 DynamicRecipePatternDetails right) {
        return Long.compare(right.getRuleRoutePriority(), left.getRuleRoutePriority());
    }

    private static PlanningMode resolveDetailPlanningMode(List<IPatternDetails> patterns) {
        PlanningMode selected = null;
        for (IPatternDetails pattern : patterns) {
            DynamicRecipePatternDetails detail = getDynamicPattern(pattern);
            if (detail == null) continue;
            if (selected == null) {
                selected = detail.getPlanningMode();
            } else if (selected != detail.getPlanningMode()) {
                return PlanningMode.STOCK_FIRST;
            }
        }
        return selected == null ? PlanningMode.STOCK_FIRST : selected;
    }

    private static final class GridState {

        private final Map<String, ProviderSnapshot> providers = new ConcurrentHashMap<>();
        private final Map<AEKey, List<DynamicRecipePatternDetails>> patternsByTarget = new ConcurrentHashMap<>();
        private final Map<String, DynamicRecipePatternDetails> patternsByRecipe = new ConcurrentHashMap<>();
        private final Map<AEKey, Set<String>> rejectedRecipeKeysByTarget = new ConcurrentHashMap<>();
        /**
         * Caches the expensive candidate list produced by {@link #collectPatternCandidates} for targets queried by
         * {@link #getCandidatesForRouteCost}. Avoids re-encoding recipes when the same intermediate output is scored
         * by multiple RouteCostEstimator instances within one crafting calculation. Cleared in {@link #clearGenerated()}
         * and expired by TTL.
         */
        private static final long ROUTE_CANDIDATE_CACHE_TTL_NANOS = 5_000_000_000L;
        private final Map<AEKey, CachedCandidates> routeCandidateCache = new ConcurrentHashMap<>();
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
                    int patternLimit = 1;
                    for (DynamicRecipePatternDetails candidate : updated) {
                        patternLimit = Math.max(patternLimit, candidate.getMaxPatternsForTarget());
                    }
                    patternLimit = Math.min(getPlanningBudget().getMaxCandidatesPerTarget(), patternLimit);
                    if (updated.size() > patternLimit) {
                        updated = new ArrayList<>(updated.subList(0, patternLimit));
                    }
                    return Collections.unmodifiableList(updated);
                });
            }
        }

        private static boolean isRecipeMapAvailable(ProviderSnapshot snapshot, DynamicRecipePatternDetails detail) {
            if (!snapshot.ruleSetVersion.equals(detail.getRecipeBinding().getRuleSetVersion()) ||
                    !snapshot.machineProfile.getVersion().equals(detail.getRecipeBinding().getMachineProfileVersion())) {
                return false;
            }
            for (RecipeMap<?> recipeMap : snapshot.recipeMaps) {
                if (isDynamicRecipeMapEnabled(recipeMap) &&
                        recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName()) &&
                        RecipeBindingResolver.resolve(detail.getRecipeBinding(), recipeMap).isResolved()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return whether no active providers remain on this grid.
         */
        private synchronized boolean removeProvider(String providerId) {
            ProviderSnapshot removed = providers.remove(providerId);
            if (removed != null) {
                clearGenerated();
                removeProviderBindings(removed.provider);
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
            long lookupStartedAt = System.nanoTime();
            try {
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
                    PLANNING_METRICS.recordTargetCacheMiss();
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
                } else {
                    PLANNING_METRICS.recordTargetCacheHit();
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
            } finally {
                PLANNING_METRICS.recordPlanningDuration(System.nanoTime() - lookupStartedAt);
            }
        }

        /** Supplies bounded dynamic dependency edges without recursively invoking CraftingService. */
        private List<PatternCandidate> getCandidatesForRouteCost(AEKey target) {
            if (target == null || isExternalOreInput(target) || isElementalDust(target)) {
                return Collections.emptyList();
            }
            CachedCandidates cached = routeCandidateCache.get(target);
            if (cached != null && (System.nanoTime() - cached.cachedAtNanos) < ROUTE_CANDIDATE_CACHE_TTL_NANOS) {
                PLANNING_METRICS.recordRouteCandidateCacheHit();
                return cached.candidates;
            }
            PLANNING_METRICS.recordRouteCandidateCacheMiss();
            List<PatternCandidate> candidates = collectPatternCandidates(target,
                    getPlanningBudget().getMaxDynamicCandidatesForCost());
            if (candidates.size() > 1 && getActiveOptimalRebuild() != null && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                List<String> recipeMaps = new ArrayList<>(candidates.size());
                for (PatternCandidate candidate : candidates) {
                    recipeMaps.add(candidate.recipeMap.getUnlocalizedName());
                }
                ApplyGrayMod.LOGGER.debug("Retained {} generic RecipeMap dependency route candidates for {}: {}",
                        candidates.size(), target, recipeMaps);
            }
            routeCandidateCache.put(target, new CachedCandidates(candidates, System.nanoTime()));
            return candidates;
        }

        private List<DynamicRecipePatternDetails> createPatterns(AEKey target) {
            List<PatternCandidate> candidates = collectPatternCandidates(target,
                    getPlanningBudget().getMaxCandidatesPerTarget());
            if (candidates.isEmpty()) {
                return Collections.emptyList();
            }

            selectBestCandidate(target, candidates);
            if (candidates.isEmpty()) {
                return Collections.emptyList();
            }
            // A cap is merged within each matching rule decision. A route-specific exposure rule must be able to
            // broaden the target's Pareto frontier even when another valid route has the default cap of one.
            int materializedLimit = 1;
            for (PatternCandidate candidate : candidates) {
                materializedLimit = Math.max(materializedLimit, candidate.decision.getMaxPatternsForTarget());
            }
            materializedLimit = Math.min(getPlanningBudget().getMaxCandidatesPerTarget(), materializedLimit);
            List<DynamicRecipePatternDetails> details = new ArrayList<>(materializedLimit);
            for (int index = 0; index < Math.min(materializedLimit, candidates.size()); index++) {
                DynamicRecipePatternDetails detail = materializePattern(target, candidates.get(index));
                if (detail != null) details.add(detail);
            }
            return Collections.unmodifiableList(details);
        }

        private List<PatternCandidate> collectPatternCandidates(AEKey target, int candidateLimit) {
            List<PatternCandidate> candidates = new ArrayList<>(candidateLimit);
            List<ProviderSnapshot> sources = new ArrayList<>(providers.values());
            Set<String> seenRecipeKeys = new HashSet<>();
            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            PlanningBudget planningBudget = getPlanningBudget();
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
                        long candidateStartedAt = System.nanoTime();
                        if (!cooperateWithCraftingCalculation()) {
                            return Collections.emptyList();
                        }
                        if (inspectedRecipes >= planningBudget.getMaxRecipesPerTarget()) {
                            cappedRecipeScan = true;
                            break scan;
                        }
                        inspectedRecipes++;
                        if (optimalRebuild != null) {
                            optimalRebuild.matchingRecipeCandidates++;
                        }
                        NormalizedRecipe normalized = outputIndex.normalize(recipe);
                        if (normalized == null) continue;
                        EncodedRecipe encoded = encodeRecipe(recipe, storedItems);
                        if (encoded == null) continue;
                        List<GenericStack> actualOutputs = encoded.outputs;
                        List<GenericStack> deterministicPatternOutputs = selectRequestedPatternOutputs(target,
                                actualOutputs);
                        List<GenericStack> chancedTargetOutputs = deterministicPatternOutputs.isEmpty() ?
                                selectRequestedPatternOutputs(target, normalized.getChancedOutputStacks()) :
                                Collections.emptyList();
                        if (deterministicPatternOutputs.isEmpty() && chancedTargetOutputs.isEmpty()) continue;
                        boolean chancedTarget = deterministicPatternOutputs.isEmpty();
                        List<GenericStack> hiddenOutputs = new ArrayList<>(selectHiddenActualOutputs(target,
                                actualOutputs));
                        for (GenericStack chancedOutput : normalized.getChancedOutputStacks()) {
                            if (!chancedTarget || !target.matches(chancedOutput)) hiddenOutputs.add(chancedOutput);
                        }
                        Map<String, Object> facts = new HashMap<>(source.machineProfile.getAdapterFacts());
                        facts.putAll(createRuleFacts(target, recipeMap, recipe, normalized, encoded, storedItems));
                        for (var contributor : RecipePatternRules.getFactContributors()) {
                            try {
                                contributor.contributeRecipeFacts(recipe, facts);
                            } catch (RuntimeException exception) {
                                ApplyGrayMod.LOGGER.warn("Recipe-pattern fact contributor {} failed for {} in {}",
                                        contributor.getClass().getName(), target, recipeMap.getUnlocalizedName(),
                                        exception);
                            }
                        }
                        RuleContext ruleContext = new RuleContext(normalized, source.machineProfile, target,
                                getPlanningMode(source), Collections.emptySet(), facts, hiddenOutputs.size(),
                                encoded.tokenLayout.getRequiredVirtualSlots());
                        RuleDecision decision = RecipePatternRules.evaluate(ruleContext);
                        if (!decision.isAllowed() || decision.getCyclePolicy() == CyclePolicy.FORBID) {
                            String reason = !decision.isAllowed() ? decision.getDenialCode() : "CYCLE_POLICY_FORBID";
                            logCandidateDecision(source, target, recipeMap, normalized, decision, "rejected", reason,
                                    candidateStartedAt);
                            continue;
                        }
                        List<GenericStack> patternOutputs = deterministicPatternOutputs;
                        if (chancedTarget) {
                            if (decision.getOutputPolicy() != OutputPolicy.GUARANTEED_LOWER_BOUND) {
                                logCandidateDecision(source, target, recipeMap, normalized, decision, "rejected",
                                        "CHANCED_TARGET_UNSAFE", candidateStartedAt);
                                continue;
                            }
                            long physicalMaximum = chancedTargetOutputs.get(0).amount();
                            long guaranteedLowerBound = RecipePatternRules.getGuaranteedOutputLowerBound(ruleContext,
                                    target);
                            if (guaranteedLowerBound <= 0 || guaranteedLowerBound > physicalMaximum) {
                                logCandidateDecision(source, target, recipeMap, normalized, decision, "rejected",
                                        "CHANCED_TARGET_LOWER_BOUND_UNPROVEN", candidateStartedAt);
                                continue;
                            }
                            patternOutputs = Collections.singletonList(new GenericStack(target, guaranteedLowerBound));
                            decision.explain("adapter:guaranteedLowerBound=" + guaranteedLowerBound);
                        }
                        encoded = encoded.withOutputs(patternOutputs);
                        long netOutput = DynamicRecipePatternDetails.getNetOutputAmount(target, encoded.inputs,
                                encoded.alternatives, encoded.outputs);
                        if (netOutput <= 0) continue;
                        PlanningMode planningMode = resolveCandidatePlanningMode(source, decision);
                        if (planningMode == PlanningMode.PINNED &&
                                !source.pinnedRouteGroup.equals(decision.getPinGroup())) {
                            String reason = source.pinnedRouteGroup.isEmpty() ? "PINNED_ROUTE_GROUP_UNSET" :
                                    "PINNED_ROUTE_GROUP_MISMATCH";
                            logCandidateDecision(source, target, recipeMap, normalized, decision, "rejected", reason,
                                    candidateStartedAt);
                            continue;
                        }
                        CandidateRoutePriority routePriority = getDiagnosticRoutePriority(decision);
                        logCandidateDecision(source, target, recipeMap, normalized, decision, "accepted", "OK",
                                candidateStartedAt);
                        if (optimalRebuild != null) {
                            optimalRebuild.recordCandidate(routePriority);
                        }
                        // This ranking only affects pattern preference. Avoiding recursive cost evaluation keeps
                        // large RecipeMaps from turning one lookup into a full dependency scan.
                        Cost cost = Cost.fallback(recipe, source.machineProfile, storedItems, netOutput,
                                routePriority, planningMode, decision);
                        TargetedRecipe targeted = new TargetedRecipe(target, patternOutputs.get(0).amount(),
                                encoded.inputs, encoded.alternatives, hiddenOutputs,
                                normalized.createBinding(target, source.ruleSetVersion,
                                        source.machineProfile.getVersion()), encoded.tokenLayout,
                                decision.getExplanation());
                        PatternCandidate candidate = new PatternCandidate(source, recipeMap, normalized, target,
                                encoded, targeted, decision, cost);
                        if (!seenRecipeKeys.add(candidate.recipeKey)) continue;
                        if (isRejectedFor(target, candidate.recipeKey)) continue;
                        keepBestCandidate(candidates, candidate, candidateLimit);
                    }
                }
            }

            if (cappedRecipeScan) {
                PLANNING_METRICS.recordBudgetExhaustion();
                ApplyGrayMod.LOGGER.warn("Lazy RecipeMap pattern lookup stopped target={} reasonCode=BUDGET_EXHAUSTED " +
                                "budgetReason=RECIPE_CANDIDATE_LIMIT limit={}",
                        target, planningBudget.getMaxRecipesPerTarget());
                if (planningBudget.getExhaustionPolicy() == BudgetExhaustionPolicy.REJECT) {
                    ApplyGrayMod.LOGGER.warn("Rejected incomplete RecipeMap candidates target={} " +
                                    "reasonCode=BUDGET_EXHAUSTED budgetPolicy=REJECT",
                            target);
                    return Collections.emptyList();
                }
            }
            return candidates;
        }

        private void selectBestCandidate(AEKey target, List<PatternCandidate> candidates) {
            if (candidates.isEmpty()) return;
            long startedAt = System.nanoTime();
            RouteCostEstimator estimator = new RouteCostEstimator(candidates.get(0).source.grid, this,
                    getRouteCostBudget());
            int rejectedCycleCandidates = estimator.rejectUnsafeRootCandidates(target, candidates);
            if (candidates.isEmpty()) {
                ApplyGrayMod.LOGGER.warn("Rejected all dynamic RecipeMap routes target={} reasonCode=CYCLE_NO_EXTERNAL_SEED " +
                                "rejectedCandidates={}",
                        target, rejectedCycleCandidates);
                return;
            }
            if (rejectIncompletePlanning(target, candidates, estimator)) return;
            if (candidates.size() < 2) return;

            PlanningMode planningMode = resolvePlanningMode(candidates);
            PatternCandidate staticBest = candidates.get(0);
            for (int index = 1; index < candidates.size(); index++) {
                PatternCandidate candidate = candidates.get(index);
                if (candidate.cost.compareTo(staticBest.cost, planningMode) < 0) {
                    staticBest = candidate;
                }
            }
            Map<PatternCandidate, DirectRouteCost> quickCosts = new IdentityHashMap<>();
            for (PatternCandidate candidate : candidates) {
                quickCosts.put(candidate, estimator.estimateDirect(candidate));
            }
            candidates.sort((left, right) -> {
                int staticCost = left.cost.compareTo(right.cost, planningMode);
                int quickCost = quickCosts.get(left).compareTo(quickCosts.get(right));
                if (planningMode == PlanningMode.STOCK_FIRST) {
                    if (quickCost != 0) return quickCost;
                    return staticCost != 0 ? staticCost : compareCandidates(left, right);
                }
                if (staticCost != 0) return staticCost;
                return quickCost != 0 ? quickCost : compareCandidates(left, right);
            });

            boolean stockOnlySelection = quickCosts.get(candidates.get(0)).isFullyStocked();
            Map<PatternCandidate, RouteCost> refinedCosts = new IdentityHashMap<>();
            if (!stockOnlySelection) {
                List<PatternCandidate> refined = new ArrayList<>(getPlanningBudget().getMaxRefinedCandidates());
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
                    int staticCost = left.cost.compareTo(right.cost, planningMode);
                    int routeCost = refinedCosts.get(left).compareTo(refinedCosts.get(right));
                    if (planningMode == PlanningMode.STOCK_FIRST) {
                        if (routeCost != 0) return routeCost;
                        return staticCost != 0 ? staticCost : compareCandidates(left, right);
                    }
                    if (staticCost != 0) return staticCost;
                    return routeCost != 0 ? routeCost : compareCandidates(left, right);
                });

                PatternCandidate selected = refined.get(0);
                if (candidates.get(0) != selected) {
                    candidates.remove(selected);
                    candidates.add(0, selected);
                }
            }

            if (rejectIncompletePlanning(target, candidates, estimator)) return;

            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            if (optimalRebuild != null) {
                optimalRebuild.inventoryScoredTargets.add(target);
                optimalRebuild.recordRouteCostEstimator(target, candidates.size(), refinedCosts.size(),
                        stockOnlySelection, System.nanoTime() - startedAt, estimator);
            }
        }

        /** Applies the configured conservative behavior after a bounded SCC or route-cost search could not finish. */
        private static boolean rejectIncompletePlanning(AEKey target, List<PatternCandidate> candidates,
                                                        RouteCostEstimator estimator) {
            if (getPlanningBudget().getExhaustionPolicy() != BudgetExhaustionPolicy.REJECT ||
                    !estimator.isIncomplete()) {
                return false;
            }
            candidates.clear();
            ApplyGrayMod.LOGGER.warn("Rejected incomplete RecipeMap route planning target={} " +
                            "reasonCode=BUDGET_EXHAUSTED budgetPolicy=REJECT budgetReason={}",
                    target, estimator.getIncompleteReason());
            return true;
        }

        /** Uses a provider-selected profile when all candidates agree; mixed providers retain the stock-safe default. */
        private static PlanningMode resolvePlanningMode(List<PatternCandidate> candidates) {
            PlanningMode selected = null;
            for (PatternCandidate candidate : candidates) {
                PlanningMode mode = candidate.cost.planningMode;
                if (selected == null) {
                    selected = mode;
                } else if (selected != mode) {
                    return PlanningMode.STOCK_FIRST;
                }
            }
            return selected == null ? PlanningMode.STOCK_FIRST : selected;
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
                    candidate.cost.routePriority, candidate.cost.ruleRoutePriority,
                    candidate.decision.getCyclePolicy(), candidate.cost.cycleRiskPenalty,
                    candidate.decision.getMaxPatternsForTarget(), candidate.cost.planningMode,
                    candidate.decision.getPinGroup(), candidate.targeted.getHiddenActualOutputs(),
                    candidate.targeted.getBinding(), candidate.targeted.getTokenLayout())) {
                candidate.source.provider.removeCachedDynamicPattern(candidate.recipeKey);
                detail = null;
            }
            if (detail == null) {
                detail = new DynamicRecipePatternDetails(candidate.recipeKey,
                        candidate.recipeMap.getUnlocalizedName(), candidate.encoded.inputs,
                        candidate.encoded.alternatives, candidate.encoded.outputs,
                        candidate.encoded.circuitConfiguration,
                        candidate.cost.rawMaterials, candidate.cost.steps, candidate.cost.routePriority,
                        candidate.cost.ruleRoutePriority, candidate.decision.getCyclePolicy(),
                        candidate.cost.cycleRiskPenalty, candidate.decision.getMaxPatternsForTarget(),
                        candidate.cost.planningMode, candidate.decision.getPinGroup(),
                        candidate.targeted.getHiddenActualOutputs(), candidate.targeted.getBinding(),
                        candidate.targeted.getTokenLayout(), candidate.targeted.getExplanation());
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
                PLANNING_METRICS.recordGeneratedPattern();
            } else {
                if (!isPatternAvailableFor(target, detail)) {
                    return null;
                }
                if (optimalRebuild != null) {
                    optimalRebuild.reusedPatterns++;
                }
                PLANNING_METRICS.recordReusedPattern();
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
            RecipeBindingResolver.invalidate(recipeMap);
            RecipeOutputIndex indexed = RecipeOutputIndex.create(recipeMap);
            if (indexed == null) {
                return null;
            }
            PLANNING_METRICS.recordIndex(indexed.recipeCount, System.nanoTime() - startedAt);

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
            for (int index = candidates.size() - 1; index >= 0; index--) {
                PatternCandidate existing = candidates.get(index);
                if (dominates(existing, candidate)) {
                    return;
                }
                if (dominates(candidate, existing)) {
                    candidates.remove(index);
                }
            }

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

        /** Retains non-dominated route alternatives before the configured candidate budget trims the frontier. */
        private static boolean dominates(PatternCandidate left, PatternCandidate right) {
            Cost leftCost = left.cost;
            Cost rightCost = right.cost;
            boolean routeNoWorse = leftCost.ruleRoutePriority >= rightCost.ruleRoutePriority;
            boolean rawNoWorse = compareInputOutputEfficiency(leftCost.rawMaterials, leftCost.netOutput,
                    rightCost.rawMaterials, rightCost.netOutput) <= 0;
            boolean hiddenNoWorse = leftCost.hiddenOutputPenalty <= rightCost.hiddenOutputPenalty;
            boolean cycleNoWorse = leftCost.cycleRiskPenalty <= rightCost.cycleRiskPenalty;
            boolean stepsNoWorse = leftCost.steps <= rightCost.steps;
            if (!routeNoWorse || !rawNoWorse || !hiddenNoWorse || !cycleNoWorse || !stepsNoWorse) {
                return false;
            }
            return leftCost.ruleRoutePriority > rightCost.ruleRoutePriority ||
                    compareInputOutputEfficiency(leftCost.rawMaterials, leftCost.netOutput,
                            rightCost.rawMaterials, rightCost.netOutput) < 0 ||
                    leftCost.hiddenOutputPenalty < rightCost.hiddenOutputPenalty ||
                    leftCost.cycleRiskPenalty < rightCost.cycleRiskPenalty || leftCost.steps < rightCost.steps;
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
            routeCandidateCache.clear();
            invalidateRecipeOutputIndexes();
            // Weak ownership entries deliberately survive cache invalidation so an already-submitted CPU can finish.
            // Fresh crafting lookups only see patterns in the cleared indexes above.
        }

        private void removeProviderBindings(ICraftingProvider provider) {
            synchronized (providersByPattern) {
                providersByPattern.entrySet().removeIf(entry -> entry.getValue() == provider);
            }
        }

        private void requestFullRecipeOutputIndexRebuild() {
            invalidateRecipeOutputIndexes();
            pendingFullRecipeOutputIndexEpoch = recipeOutputIndexEpoch;
        }

        private void invalidateRecipeOutputIndexes() {
            recipeOutputIndexEpoch++;
            recipeOutputIndexes.clear();
            RecipeBindingResolver.invalidateAll();
            pendingFullRecipeOutputIndexEpoch = 0;
        }

        private synchronized int invalidateRecipeMapContents(RecipeMap<?> recipeMap) {
            String recipeMapName = recipeMap.getUnlocalizedName();
            boolean affected = false;
            int discarded = 0;
            for (ProviderSnapshot snapshot : providers.values()) {
                boolean exposesRecipeMap = false;
                for (RecipeMap<?> exposedRecipeMap : snapshot.recipeMaps) {
                    if (exposedRecipeMap == recipeMap) {
                        exposesRecipeMap = true;
                        break;
                    }
                }
                if (!exposesRecipeMap) continue;
                affected = true;
                for (DynamicRecipePatternDetails detail : snapshot.provider.getCachedDynamicPatterns()) {
                    if (recipeMapName.equals(detail.getRecipeMapName())) {
                        snapshot.provider.removeCachedDynamicPattern(detail.getRecipeKey());
                        discarded++;
                    }
                }
            }
            if (!affected && !recipeOutputIndexes.containsKey(recipeMap)) return 0;
            clearGenerated();
            return discarded;
        }

        private synchronized int invalidateRuleSetContents() {
            int discarded = 0;
            for (ProviderSnapshot snapshot : providers.values()) {
                for (DynamicRecipePatternDetails detail : snapshot.provider.getCachedDynamicPatterns()) {
                    snapshot.provider.removeCachedDynamicPattern(detail.getRecipeKey());
                    discarded++;
                }
            }
            clearGenerated();
            return discarded;
        }

        private static final class CachedCandidates {
            private final List<PatternCandidate> candidates;
            private final long cachedAtNanos;

            private CachedCandidates(List<PatternCandidate> candidates, long cachedAtNanos) {
                this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
                this.cachedAtNanos = cachedAtNanos;
            }
        }
    }

    /**
     * Immutable output index for one RecipeMap. Chanced keys are retained only so a proven lower-bound adapter can
     * inspect them; normal candidate generation still refuses them by default.
     */
    private static final class RecipeOutputIndex {

        private final int recipeCount;
        private final int outputCount;
        private final Map<AEKey, List<Recipe>> recipesByOutput;
        private final RecipeBindingResolver.RecipeMapSnapshot bindingSnapshot;

        private RecipeOutputIndex(int recipeCount, Map<AEKey, List<Recipe>> recipesByOutput,
                                  RecipeBindingResolver.RecipeMapSnapshot bindingSnapshot) {
            this.recipeCount = recipeCount;
            this.outputCount = recipesByOutput.size();
            this.recipesByOutput = recipesByOutput;
            this.bindingSnapshot = bindingSnapshot;
        }

        private static RecipeOutputIndex create(RecipeMap<?> recipeMap) {
            RecipeBindingResolver.RecipeMapSnapshot bindingSnapshot = RecipeBindingResolver.snapshot(recipeMap);
            Collection<Recipe> recipes = bindingSnapshot.getRecipes();
            Map<AEKey, List<Recipe>> mutableIndex = new HashMap<>();
            for (Recipe recipe : recipes) {
                if (!GridState.cooperateWithCraftingCalculation()) {
                    return null;
                }

                for (ItemStack output : recipe.getOutputs()) {
                    addRecipe(mutableIndex, AEItemKey.of(output), recipe);
                }
                for (FluidStack output : recipe.getFluidOutputs()) {
                    addRecipe(mutableIndex, AEFluidKey.of(output), recipe);
                }
                for (ChancedItemOutput output : recipe.getChancedOutputs().getChancedEntries()) {
                    addRecipe(mutableIndex, AEItemKey.of(output.getIngredient()), recipe);
                }
                for (ChancedFluidOutput output : recipe.getChancedFluidOutputs().getChancedEntries()) {
                    addRecipe(mutableIndex, AEFluidKey.of(output.getIngredient()), recipe);
                }
            }

            Map<AEKey, List<Recipe>> immutableIndex = new HashMap<>(mutableIndex.size());
            for (Map.Entry<AEKey, List<Recipe>> entry : mutableIndex.entrySet()) {
                immutableIndex.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            return new RecipeOutputIndex(recipes.size(), Collections.unmodifiableMap(immutableIndex), bindingSnapshot);
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

        @Nullable
        private NormalizedRecipe normalize(Recipe recipe) {
            return bindingSnapshot.normalize(recipe);
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
        if (producesGeneralCircuitBoard(recipe)) return null;

        List<GenericStack> inputs = new ArrayList<>();
        List<List<GenericStack>> alternatives = new ArrayList<>();
        List<NonConsumableTokenLayout.Slot> tokenSlots = new ArrayList<>();
        int circuitConfiguration = -1;
        for (GTRecipeInput input : recipe.getInputs()) {
            if (input instanceof IntCircuitIngredient || input.isNonConsumable()) {
                NonConsumableTokenEncoding token = encodeNonConsumableItem(input, storedItems,
                        programmableCircuitFactory);
                if (token == null) return null;
                for (int tokenIndex = 0; tokenIndex < input.getAmount(); tokenIndex++) {
                    int inputIndex = inputs.size();
                    inputs.add(token.programmableOptions.get(0));
                    alternatives.add(token.programmableOptions);
                    tokenSlots.add(new NonConsumableTokenLayout.Slot(inputIndex, token.originalChoices));
                }
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
        if (inputs.isEmpty() || outputs.isEmpty() || inputs.size() > 81) return null;
        return new EncodedRecipe(inputs, alternatives, outputs, circuitConfiguration,
                new NonConsumableTokenLayout(tokenSlots));
    }

    /**
     * Converts one non-consumable item requirement into the corresponding programmable circuit.
     * A multi-count requirement expands into separate token inputs at the caller so every required virtual slot is
     * represented explicitly to AE2 and to the isolated execution buffer.
     */
    private static NonConsumableTokenEncoding encodeNonConsumableItem(GTRecipeInput input, KeyCounter storedItems,
                                                                       Function<ItemStack, ItemStack> programmableCircuitFactory) {
        if (input.getInputFluidStack() != null || input.getAmount() <= 0 || programmableCircuitFactory == null) {
            return null;
        }

        ItemStack[] choices = input.getInputStacks();
        if (choices == null || choices.length == 0) return null;
        if (containsExternalOreInput(choices)) return null;

        List<ItemStack> originalChoices = new ArrayList<>();
        List<GenericStack> programmableOptions = new ArrayList<>();
        for (ItemStack choice : prioritizeItemChoices(choices, storedItems)) {
            ItemStack original = choice.copy();
            original.setCount(1);
            ItemStack programmable = programmableCircuitFactory.apply(choice);
            if (programmable == null || programmable.isEmpty()) return null;
            GenericStack genericProgrammable = GenericStack.fromItemStack(programmable);
            if (genericProgrammable != null) {
                originalChoices.add(original);
                programmableOptions.add(genericProgrammable);
            }
        }

        return programmableOptions.isEmpty() ? null :
                new NonConsumableTokenEncoding(programmableOptions, originalChoices);
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
        ItemStack original = choice.copy();
        original.setCount(1);
        return ProgrammableCircuit.wrap(original, programmable);
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
            return new RouteCostBudget(getPlanningBudget());
        }

        RouteCostBudget budget = ROUTE_COST_BUDGET.get();
        if (budget == null) {
            budget = new RouteCostBudget(getPlanningBudget());
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

            int normalLimit = getPlanningBudget().getMaxNormalPatternsPerTarget();
            List<IPatternDetails> normal = new ArrayList<>(Math.min(mounted.size(), normalLimit));
            for (IPatternDetails pattern : mounted) {
                if (!(pattern instanceof DynamicRecipePatternDetails)) {
                    normal.add(pattern);
                    if (normal.size() >= normalLimit) break;
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
        private RouteCycleAnalysis cycleAnalysis;
        private boolean loggedCycleAnalysisBudget;
        private boolean loggedRouteCostBudget;
        private int rejectedCycleEdges;

        private RouteCostEstimator(IGrid grid, GridState state, RouteCostBudget budget) {
            this.grid = grid;
            this.state = state;
            this.budget = budget;
            inventory = new InventorySnapshot(grid.getStorageService().getCachedInventory());
        }

        private PlanningBudget getLimits() {
            return budget.getLimits();
        }

        private boolean isIncomplete() {
            return budget.isExhausted() || cycleAnalysis != null && !cycleAnalysis.isComplete();
        }

        private String getIncompleteReason() {
            if (budget.isExhausted()) return budget.getExhaustionReason();
            return cycleAnalysis == null ? "UNKNOWN" : cycleAnalysis.getBudgetReason();
        }

        /** Removes root routes that cannot be part of a seed-reachable SCC before they reach AE2 planning. */
        private int rejectUnsafeRootCandidates(AEKey target, List<PatternCandidate> candidates) {
            prepareCycleAnalysis(target);
            if (cycleAnalysis == null || !cycleAnalysis.isComplete()) return 0;

            int rejected = 0;
            for (int index = candidates.size() - 1; index >= 0; index--) {
                PatternCandidate candidate = candidates.get(index);
                if (!cycleAnalysis.rejects(target, RouteEdge.of(candidate), 0)) continue;
                candidates.remove(index);
                rejected++;
                logCandidateDecision(candidate.source, target, candidate.recipeMap, candidate.normalized,
                        candidate.decision, "rejected", "CYCLE_NO_EXTERNAL_SEED", 0L);
            }
            return rejected;
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
                int optionLimit = Math.min(options.length, getLimits().getMaxInputAlternatives());
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
            rejectedCycleEdges = 0;
            InventoryLedger ledger = new InventoryLedger(inventory);
            Set<AEKey> path = new HashSet<>();
            path.add(target);
            prepareCycleAnalysis(target);
            RouteCost result = estimateEdge(edge, target, 1, ledger, path, 0);
            if (rejectedCycleEdges > 0 && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Recipe-pattern SCC guard rejected {} cycle edge(s) target={} " +
                                "reasonCode=CYCLE_NO_EXTERNAL_SEED",
                        rejectedCycleEdges, target);
            }
            if (budget.isExhausted() && !loggedRouteCostBudget) {
                loggedRouteCostBudget = true;
                ApplyGrayMod.LOGGER.warn("Recipe-pattern route scoring stopped target={} reasonCode=BUDGET_EXHAUSTED " +
                                "budgetReason={} expansions={}",
                        target, budget.getExhaustionReason(), budget.getExpansions());
            }
            return result;
        }

        private void prepareCycleAnalysis(AEKey target) {
            if (cycleAnalysis != null) return;
            cycleAnalysis = RouteCycleAnalysis.analyze(target, this);
            if (!cycleAnalysis.isComplete()) {
                boundedFallbacks++;
                if (!loggedCycleAnalysisBudget) {
                    loggedCycleAnalysisBudget = true;
                    ApplyGrayMod.LOGGER.warn("Recipe-pattern SCC analysis stopped for target={} reasonCode=BUDGET_EXHAUSTED " +
                                    "budgetReason={} nodes={} edges={}",
                            target, cycleAnalysis.getBudgetReason(), cycleAnalysis.getNodeCount(),
                            cycleAnalysis.getEdgeCount());
                }
            }
        }

        private RouteCost estimateEdge(RouteEdge edge, AEKey output, long crafts, InventoryLedger ledger,
                                       Set<AEKey> path, int depth) {
            if (cycleAnalysis != null && cycleAnalysis.rejects(output, edge, depth)) {
                rejectedCycleEdges++;
                return RouteCost.bounded(depth + 1);
            }
            RouteCost total = RouteCost.executions(crafts, depth + 1);
            if (cycleAnalysis != null) {
                long cyclePenalty = cycleAnalysis.getPenalty(output, edge);
                if (cyclePenalty > 0) {
                    total = total.plus(RouteCost.cycleRisk(cyclePenalty, depth + 1));
                }
            }
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                RouteChoice best = null;
                int optionLimit = Math.min(options.length, getLimits().getMaxInputAlternatives());
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

            if (depth >= getLimits().getMaxRouteDepth() ||
                    currentRootExpansions++ >= getLimits().getMaxRouteExpansionsPerTarget() ||
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
                RouteChoice best = null;
                for (RouteEdge edge : edges) {
                    long netOutput = edge.getNetOutput(key);
                    if (netOutput <= 0) continue;

                    long crafts = divideRoundUp(remaining, netOutput);
                    InventoryLedger branch = ledger.copy();
                    RouteCost patternCost = estimateEdge(edge, key, crafts, branch, path, depth);
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
        private final CyclePolicy cyclePolicy;
        private final long cycleRiskPenalty;
        private final boolean normalPattern;

        private RouteEdge(IPatternDetails.IInput[] inputs, List<GenericStack> outputs,
                          @Nullable CandidateRoutePriority routePriority, CyclePolicy cyclePolicy,
                          long cycleRiskPenalty, boolean normalPattern) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.routePriority = routePriority;
            this.cyclePolicy = cyclePolicy == null ? CyclePolicy.BREAK_AT_EXTERNAL_SEED : cyclePolicy;
            this.cycleRiskPenalty = Math.max(0, cycleRiskPenalty);
            this.normalPattern = normalPattern;
        }

        private static RouteEdge of(IPatternDetails pattern) {
            DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
            return new RouteEdge(pattern.getInputs(), pattern.getOutputs(),
                    dynamic == null ? null : dynamic.getRoutePriority(),
                    dynamic == null ? CyclePolicy.EXTERNAL_SEED : dynamic.getCyclePolicy(),
                    dynamic == null ? 0 : dynamic.getCycleRiskPenalty(), dynamic == null);
        }

        private static RouteEdge of(PatternCandidate candidate) {
            return new RouteEdge(DynamicRecipePatternDetails.createScoringInputs(candidate.encoded.inputs,
                    candidate.encoded.alternatives), candidate.encoded.outputs, candidate.cost.routePriority,
                    candidate.decision.getCyclePolicy(), candidate.cost.cycleRiskPenalty, false);
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

    /**
     * A bounded Tarjan pass over the currently reachable dynamic dependency graph. Static AE patterns, stock, and
     * explicitly tagged seed edges terminate graph expansion. The result is deliberately immutable after analysis so
     * recursive route scoring can consult it without touching world state.
     */
    private static final class RouteCycleAnalysis {

        private final RouteCostEstimator estimator;
        private final Map<AEKey, GraphNode> nodes = new HashMap<>();
        private final Deque<GraphNode> tarjanStack = new ArrayDeque<>();
        private final long deadlineNanos;
        private boolean complete = true;
        private String budgetReason = "OK";
        private int edgeCount;
        private int nextTarjanIndex;

        private RouteCycleAnalysis(RouteCostEstimator estimator) {
            this.estimator = estimator;
            this.deadlineNanos = System.nanoTime() + estimator.getLimits().getMaxSccAnalysisNanos();
        }

        private static RouteCycleAnalysis analyze(AEKey root, RouteCostEstimator estimator) {
            RouteCycleAnalysis analysis = new RouteCycleAnalysis(estimator);
            analysis.collect(root);
            if (analysis.complete) {
                analysis.buildComponents();
            }
            return analysis;
        }

        private boolean isComplete() {
            return complete;
        }

        private String getBudgetReason() {
            return budgetReason;
        }

        private int getNodeCount() {
            return nodes.size();
        }

        private int getEdgeCount() {
            return edgeCount;
        }

        private void collect(AEKey key) {
            if (!complete || key == null || nodes.containsKey(key)) return;
            if (!reserveNode()) return;

            GraphNode node = new GraphNode(key);
            nodes.put(key, node);
            if (isExternalSeed(key)) {
                node.directSeed = true;
                return;
            }

            List<RouteEdge> edges = estimator.getEdges(key);
            node.edges = edges;
            for (RouteEdge edge : edges) {
                if (!reserveEdge()) return;
                if (edge.normalPattern || edge.cyclePolicy == CyclePolicy.EXTERNAL_SEED) {
                    node.directSeed = true;
                }
                // Mounted static patterns are explicit AE leaves. Their internals are not part of this dynamic SCC.
                if (edge.normalPattern) continue;

                for (IPatternDetails.IInput input : edge.inputs) {
                    GenericStack[] options = input.possibleInputs();
                    int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                    for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                        GenericStack option = options[optionIndex];
                        if (option == null || option.amount() <= 0) continue;
                        AEKey dependency = option.what();
                        if (isExternalSeed(dependency)) {
                            node.directSeed = true;
                        } else {
                            node.dependencies.add(dependency);
                            collect(dependency);
                            if (!complete) return;
                        }
                    }
                }
            }
        }

        private boolean rejects(AEKey output, RouteEdge edge, int depth) {
            if (!complete || edge.normalPattern || edge.cyclePolicy == CyclePolicy.EXTERNAL_SEED) return false;
            if (edge.cyclePolicy == CyclePolicy.FORBID) return true;
            if (edge.cyclePolicy == CyclePolicy.RECYCLE_ONLY && depth > 0) return true;

            GraphNode node = nodes.get(output);
            boolean cyclic = node != null && node.component != null && node.component.cyclic ||
                    closesCycle(output, edge);
            if (!cyclic) return false;
            // BREAKABLE edges are the explicit, data-driven cycle cut. Keeping one merely because another edge in
            // the component reaches a seed would allow the same dynamic loop to reappear through route scoring.
            if (edge.cyclePolicy == CyclePolicy.BREAKABLE) return true;
            boolean reachesSeed = node != null && node.component != null &&
                    canReachSeed(node.component, new HashSet<>());
            if (!reachesSeed && !canReachSeedThroughEdge(edge)) return true;
            return edge.cyclePolicy == CyclePolicy.ALLOW_NET_POSITIVE &&
                    (edge.getNetOutput(output) <= 0 || !hasImmediateSeedInput(edge));
        }

        private long getPenalty(AEKey output, RouteEdge edge) {
            if (!complete || edge.cyclePolicy != CyclePolicy.PENALIZE) return 0;
            GraphNode node = nodes.get(output);
            return node != null && node.component != null && node.component.cyclic ? edge.cycleRiskPenalty : 0;
        }

        private boolean hasImmediateSeedInput(RouteEdge edge) {
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;
                    if (isExternalSeed(option.what())) return true;
                    GraphNode dependency = nodes.get(option.what());
                    if (dependency != null && dependency.directSeed) return true;
                }
            }
            return false;
        }

        /** Detects a root edge that closes a cycle absent from the cached alternative-route subset. */
        private boolean closesCycle(AEKey output, RouteEdge edge) {
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;
                    AEKey dependency = option.what();
                    if (output.equals(dependency)) return true;
                    GraphNode dependencyNode = nodes.get(dependency);
                    if (dependencyNode != null && reaches(dependencyNode, output, new HashSet<>())) return true;
                }
            }
            return false;
        }

        /** Allows a cycle only when this candidate can prove an external or graph-reachable seed branch. */
        private boolean canReachSeedThroughEdge(RouteEdge edge) {
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;
                    if (isExternalSeed(option.what())) return true;
                    GraphNode dependency = nodes.get(option.what());
                    if (dependency != null && dependency.component != null &&
                            canReachSeed(dependency.component, new HashSet<>())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean reaches(GraphNode current, AEKey target, Set<GraphNode> visiting) {
            if (current.key.equals(target)) return true;
            if (!visiting.add(current)) return false;
            try {
                for (AEKey dependencyKey : current.dependencies) {
                    GraphNode dependency = nodes.get(dependencyKey);
                    if (dependency != null && reaches(dependency, target, visiting)) return true;
                }
                return false;
            } finally {
                visiting.remove(current);
            }
        }

        private boolean isExternalSeed(AEKey key) {
            return key != null && (estimator.inventory.get(key) > 0 || isExternalOreInput(key) ||
                    isElementalDust(key));
        }

        private boolean reserveNode() {
            if (nodes.size() >= estimator.getLimits().getMaxSccNodes()) {
                exhaust("SCC_NODE_LIMIT");
                return false;
            }
            if (System.nanoTime() >= deadlineNanos) {
                exhaust("SCC_TIME_LIMIT");
                return false;
            }
            return true;
        }

        private boolean reserveEdge() {
            if (++edgeCount > estimator.getLimits().getMaxSccEdges()) {
                exhaust("SCC_EDGE_LIMIT");
                return false;
            }
            if (System.nanoTime() >= deadlineNanos) {
                exhaust("SCC_TIME_LIMIT");
                return false;
            }
            return true;
        }

        private void exhaust(String reason) {
            if (!complete) return;
            complete = false;
            budgetReason = reason;
            PLANNING_METRICS.recordBudgetExhaustion();
        }

        private void buildComponents() {
            for (GraphNode node : nodes.values()) {
                if (node.tarjanIndex < 0) strongConnect(node);
            }
            for (GraphNode node : nodes.values()) {
                Component component = node.component;
                if (component == null) continue;
                if (node.directSeed) component.directSeed = true;
                for (AEKey dependencyKey : node.dependencies) {
                    GraphNode dependency = nodes.get(dependencyKey);
                    if (dependency == null || dependency.component == null) continue;
                    if (dependency.component == component) {
                        component.cyclic |= dependency == node;
                    } else {
                        component.dependencies.add(dependency.component);
                    }
                }
            }
        }

        private void strongConnect(GraphNode node) {
            node.tarjanIndex = nextTarjanIndex;
            node.lowLink = nextTarjanIndex++;
            tarjanStack.push(node);
            node.onTarjanStack = true;

            for (AEKey dependencyKey : node.dependencies) {
                GraphNode dependency = nodes.get(dependencyKey);
                if (dependency == null) continue;
                if (dependency.tarjanIndex < 0) {
                    strongConnect(dependency);
                    node.lowLink = Math.min(node.lowLink, dependency.lowLink);
                } else if (dependency.onTarjanStack) {
                    node.lowLink = Math.min(node.lowLink, dependency.tarjanIndex);
                }
            }

            if (node.lowLink != node.tarjanIndex) return;
            Component component = new Component();
            GraphNode member;
            do {
                member = tarjanStack.pop();
                member.onTarjanStack = false;
                member.component = component;
                component.members.add(member);
            } while (member != node);
            component.cyclic = component.members.size() > 1;
        }

        private static boolean canReachSeed(Component component, Set<Component> visiting) {
            if (component.reachesSeed != null) return component.reachesSeed;
            if (component.directSeed) {
                component.reachesSeed = true;
                return true;
            }
            if (!visiting.add(component)) return false;
            try {
                for (Component dependency : component.dependencies) {
                    if (canReachSeed(dependency, visiting)) {
                        component.reachesSeed = true;
                        return true;
                    }
                }
                component.reachesSeed = false;
                return false;
            } finally {
                visiting.remove(component);
            }
        }

        private static final class GraphNode {
            private final AEKey key;
            private final Set<AEKey> dependencies = new HashSet<>();
            private List<RouteEdge> edges = Collections.emptyList();
            private boolean directSeed;
            private int tarjanIndex = -1;
            private int lowLink;
            private boolean onTarjanStack;
            private Component component;

            private GraphNode(AEKey key) {
                this.key = key;
            }
        }

        private static final class Component {
            private final List<GraphNode> members = new ArrayList<>();
            private final Set<Component> dependencies = new HashSet<>();
            private boolean directSeed;
            private boolean cyclic;
            @Nullable private Boolean reachesSeed;
        }
    }

    /** Hard lifetime bound for all recursive route scoring performed by one AE calculation. */
    private static final class RouteCostBudget {

        private final PlanningBudget limits;
        private final long deadlineNanos;
        private int expansions;
        private boolean exhausted;
        private String exhaustionReason = "OK";

        private RouteCostBudget(PlanningBudget limits) {
            this.limits = limits == null ? PlanningBudget.DEFAULT : limits;
            this.deadlineNanos = System.nanoTime() + this.limits.getMaxRouteCalculationNanos();
        }

        private boolean tryExpansion() {
            if (expansions >= limits.getMaxRouteExpansionsPerCalculation()) {
                exhaust("ROUTE_EXPANSION_LIMIT");
                return false;
            }
            if (System.nanoTime() >= deadlineNanos) {
                exhaust("ROUTE_TIME_LIMIT");
                return false;
            }
            expansions++;
            return true;
        }

        private void exhaust(String reason) {
            if (exhausted) return;
            exhausted = true;
            exhaustionReason = reason;
            PLANNING_METRICS.recordBudgetExhaustion();
        }

        private boolean isExhausted() {
            return exhausted;
        }

        private String getExhaustionReason() {
            return exhaustionReason;
        }

        private int getExpansions() {
            return expansions;
        }

        private PlanningBudget getLimits() {
            return limits;
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
        private final long cycleRisk;

        private RouteCost(long missingMaterials, int maxDepth, long executions,
                          long consumedStockMaterials, int boundedFallbacks, long cycleRisk) {
            this.missingMaterials = missingMaterials;
            this.maxDepth = maxDepth;
            this.executions = executions;
            this.consumedStockMaterials = consumedStockMaterials;
            this.boundedFallbacks = boundedFallbacks;
            this.cycleRisk = cycleRisk;
        }

        private static RouteCost executions(long executions, int depth) {
            return new RouteCost(0, depth, executions, 0, 0, 0);
        }

        private static RouteCost stock(long materials) {
            return new RouteCost(0, 0, 0, materials, 0, 0);
        }

        private static RouteCost missing(AEKey key, long amount, int depth) {
            return new RouteCost(estimateKeyMaterialAmount(key, amount), depth, 0, 0, 0, 0);
        }

        private static RouteCost bounded(int depth) {
            return new RouteCost(BOUNDED_ROUTE_COST_PENALTY, depth, 0, 0, 1, 0);
        }

        private static RouteCost cycleRisk(long risk, int depth) {
            return new RouteCost(0, depth, 0, 0, 0, Math.max(0, risk));
        }

        private RouteCost plus(RouteCost other) {
            return new RouteCost(addSaturated(missingMaterials, other.missingMaterials),
                    Math.max(maxDepth, other.maxDepth), addSaturated(executions, other.executions),
                    addSaturated(consumedStockMaterials, other.consumedStockMaterials),
                    boundedFallbacks + other.boundedFallbacks, addSaturated(cycleRisk, other.cycleRisk));
        }

        @Override
        public int compareTo(RouteCost other) {
            int missing = Long.compare(missingMaterials, other.missingMaterials);
            if (missing != 0) return missing;
            int bounded = Integer.compare(boundedFallbacks, other.boundedFallbacks);
            if (bounded != 0) return bounded;
            int cycle = Long.compare(cycleRisk, other.cycleRisk);
            if (cycle != 0) return cycle;
            int depth = Integer.compare(maxDepth, other.maxDepth);
            if (depth != 0) return depth;
            int executionCount = Long.compare(executions, other.executions);
            if (executionCount != 0) return executionCount;
            return Long.compare(consumedStockMaterials, other.consumedStockMaterials);
        }

        @Override
        public String toString() {
            return "[missing=" + missingMaterials + ", depth=" + maxDepth + ", executions=" + executions +
                    ", stock=" + consumedStockMaterials + ", bounded=" + boundedFallbacks +
                    ", cycle=" + cycleRisk + ']';
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
        private final NonConsumableTokenLayout tokenLayout;

        private EncodedRecipe(List<GenericStack> inputs, List<List<GenericStack>> alternatives,
                              List<GenericStack> outputs,
                              int circuitConfiguration, NonConsumableTokenLayout tokenLayout) {
            this.inputs = inputs;
            this.alternatives = alternatives;
            this.outputs = outputs;
            this.circuitConfiguration = circuitConfiguration;
            this.tokenLayout = tokenLayout;
        }

        private EncodedRecipe withOutputs(List<GenericStack> patternOutputs) {
            return new EncodedRecipe(inputs, alternatives, patternOutputs, circuitConfiguration, tokenLayout);
        }
    }

    private static final class NonConsumableTokenEncoding {

        private final List<GenericStack> programmableOptions;
        private final List<ItemStack> originalChoices;

        private NonConsumableTokenEncoding(List<GenericStack> programmableOptions, List<ItemStack> originalChoices) {
            this.programmableOptions = Collections.unmodifiableList(new ArrayList<>(programmableOptions));
            this.originalChoices = Collections.unmodifiableList(new ArrayList<>(originalChoices));
        }
    }

    private static final class PatternCandidate {
        private final ProviderSnapshot source;
        private final RecipeMap<?> recipeMap;
        private final NormalizedRecipe normalized;
        private final EncodedRecipe encoded;
        private final TargetedRecipe targeted;
        private final RuleDecision decision;
        private final Cost cost;
        private final String recipeKey;

        private PatternCandidate(ProviderSnapshot source, RecipeMap<?> recipeMap, NormalizedRecipe normalized,
                                 AEKey target, EncodedRecipe encoded, TargetedRecipe targeted,
                                 RuleDecision decision, Cost cost) {
            this.source = source;
            this.recipeMap = recipeMap;
            this.normalized = normalized;
            this.encoded = encoded;
            this.targeted = targeted;
            this.decision = decision;
            this.cost = cost;
            String baseRecipeKey = source.providerId + ':' + recipeMap.getUnlocalizedName() + ':' +
                    normalized.getRecipeFingerprint();
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

    private static final class Cost {
        private final long rawMaterials;
        private final long netOutput;
        private final int steps;
        private final CandidateRoutePriority routePriority;
        private final long ruleRoutePriority;
        private final long hiddenOutputPenalty;
        private final long cycleRiskPenalty;
        private final long energyCost;
        private final long throughputCost;
        private final PlanningMode planningMode;

        private Cost(long rawMaterials, long netOutput, int steps, CandidateRoutePriority routePriority,
                     long ruleRoutePriority, long hiddenOutputPenalty, long cycleRiskPenalty, long energyCost,
                     long throughputCost, PlanningMode planningMode) {
            this.rawMaterials = rawMaterials;
            this.netOutput = netOutput;
            this.steps = steps;
            this.routePriority = routePriority;
            this.ruleRoutePriority = ruleRoutePriority;
            this.hiddenOutputPenalty = hiddenOutputPenalty;
            this.cycleRiskPenalty = cycleRiskPenalty;
            this.energyCost = energyCost;
            this.throughputCost = throughputCost;
            this.planningMode = planningMode == null ? PlanningMode.STOCK_FIRST : planningMode;
        }

        private static Cost fallback(Recipe recipe, MachineCapabilityProfile machine, KeyCounter storedItems,
                                     long netOutput, CandidateRoutePriority routePriority, PlanningMode planningMode,
                                     RuleDecision decision) {
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
            int parallel = machine == null ? 1 : Math.max(1, machine.getParallelLimit());
            long duration = Math.max(1, recipe.getDuration());
            long energy = divideRoundUp(multiplySaturated(Math.max(0, recipe.getEUt()), duration), parallel);
            long throughput = divideRoundUp(duration, parallel);
            long hidden = Math.max(0, -decision.getScore("hiddenOutputWaste"));
            long cycle = Math.max(0, -decision.getScore("cycleRisk"));
            return new Cost(raw, netOutput, 1, routePriority, decision.getScore("routePriority"), hidden, cycle,
                    scoreAdjustedCost(energy, decision.getScore("energy")),
                    scoreAdjustedCost(throughput, decision.getScore("throughput")), planningMode);
        }

        private int compareRoutePolicy(Cost other) {
            return Long.compare(other.ruleRoutePriority, ruleRoutePriority);
        }

        private int compareTo(Cost other) {
            return compareTo(other, PlanningMode.STOCK_FIRST);
        }

        private int compareTo(Cost other, PlanningMode mode) {
            PlanningMode effectiveMode = mode == null ? PlanningMode.STOCK_FIRST : mode;
            int comparison;
            switch (effectiveMode) {
                case RESOURCE_FIRST -> {
                    comparison = compareInputOutputEfficiency(rawMaterials, netOutput,
                            other.rawMaterials, other.netOutput);
                    if (comparison != 0) return comparison;
                    comparison = Long.compare(energyCost, other.energyCost);
                    if (comparison != 0) return comparison;
                    comparison = compareSafety(other);
                    if (comparison != 0) return comparison;
                }
                case THROUGHPUT_FIRST -> {
                    comparison = Long.compare(throughputCost, other.throughputCost);
                    if (comparison != 0) return comparison;
                    comparison = Long.compare(energyCost, other.energyCost);
                    if (comparison != 0) return comparison;
                    comparison = Integer.compare(steps, other.steps);
                    if (comparison != 0) return comparison;
                }
                case SAFE_FIRST -> {
                    comparison = compareSafety(other);
                    if (comparison != 0) return comparison;
                    comparison = compareInputOutputEfficiency(rawMaterials, netOutput,
                            other.rawMaterials, other.netOutput);
                    if (comparison != 0) return comparison;
                }
                case PINNED, STOCK_FIRST -> {
                    comparison = compareRoutePolicy(other);
                    if (comparison != 0) return comparison;
                    comparison = compareSafety(other);
                    if (comparison != 0) return comparison;
                }
            }
            comparison = compareInputOutputEfficiency(rawMaterials, netOutput,
                    other.rawMaterials, other.netOutput);
            if (comparison != 0) return comparison;
            comparison = Long.compare(energyCost, other.energyCost);
            if (comparison != 0) return comparison;
            comparison = Long.compare(throughputCost, other.throughputCost);
            if (comparison != 0) return comparison;
            comparison = compareRoutePolicy(other);
            return comparison != 0 ? comparison : Integer.compare(steps, other.steps);
        }

        private int compareSafety(Cost other) {
            int hidden = Long.compare(hiddenOutputPenalty, other.hiddenOutputPenalty);
            if (hidden != 0) return hidden;
            return Long.compare(cycleRiskPenalty, other.cycleRiskPenalty);
        }

        private static long scoreAdjustedCost(long value, long score) {
            if (score >= 0) return value <= score ? 0 : value - score;
            return score == Long.MIN_VALUE ? Long.MAX_VALUE : addSaturated(value, -score);
        }
    }
}
