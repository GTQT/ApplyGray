package applygray.integration.ae2;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.planning.AndOrRoutePlanner;
import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.NonConsumableTokenLayout;
import applygray.integration.ae2.recipe.NormalizedRecipe;
import applygray.integration.ae2.recipe.RecipeBinding;
import applygray.integration.ae2.recipe.RecipeFingerprint;
import applygray.integration.ae2.recipe.RecipeBindingResolver;
import applygray.integration.ae2.recipe.TargetedRecipe;
import applygray.integration.ae2.rules.BudgetExhaustionPolicy;
import applygray.integration.ae2.rules.CycleSafetyExhaustionPolicy;
import applygray.integration.ae2.rules.CyclePolicy;
import applygray.integration.ae2.rules.OutputPolicy;
import applygray.integration.ae2.rules.PlanningBudget;
import applygray.integration.ae2.rules.PlanningMode;
import applygray.integration.ae2.rules.RecipePatternRules;
import applygray.integration.ae2.rules.RuleContext;
import applygray.integration.ae2.rules.RuleDecision;

import gregtech.api.GTValues;
import gregtech.api.GregTechAPI;
import gregtech.api.fluids.store.FluidStorageKeys;
import gregtech.api.unification.FluidUnifier;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.properties.IngotProperty;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
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
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.integration.data.CraftingTreeStackRegistry;
import ae2.integration.data.LiteCraftTreeNode;
import ae2.integration.data.LiteCraftTreeProc;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Consumer;

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
    /** Logs only aggregate cache rebuilds that are large enough to affect a server tick. */
    private static final long SLOW_PROVIDER_CACHE_REBUILD_NANOS = 10_000_000L;
    private static final long SLOW_PROVIDER_CACHE_REBUILD_LOG_COOLDOWN_NANOS = 5_000_000_000L;
    private static final ThreadLocal<Boolean> RECURSIVE_CYCLE_RECOVERY_REQUIRED = new ThreadLocal<>();
    /** Rejections learned from AE2's exact recursion stack and scoped to one submitted crafting task. */
    private static final ThreadLocal<CraftingRecoverySession> CRAFTING_RECOVERY_SESSION = new ThreadLocal<>();
    private static final ThreadLocal<CraftingCalculation> ACTIVE_CRAFTING_CALCULATION = new ThreadLocal<>();
    /** Request amount visible while AE2 builds one node's candidate processes. */
    private static final ThreadLocal<LargePatternSelection> ACTIVE_LARGE_PATTERN_SELECTION = new ThreadLocal<>();
    /** One aggregate record for the large-pattern decisions made during an AE2 calculation. */
    private static final ThreadLocal<LargePatternCalculationSummary> LARGE_PATTERN_CALCULATION_SUMMARY =
            new ThreadLocal<>();
    /** Nesting frames used to separate a process request's own work from recursive child requests. */
    private static final ThreadLocal<Deque<CraftingProcessRequestTiming>> CRAFTING_PROCESS_REQUEST_TIMINGS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final long SLOW_LARGE_PATTERN_CALCULATION_NANOS = 1_000_000_000L;
    private static final int MAX_LARGE_PATTERN_PROCESS_HOTSPOTS = 6;
    private static final int MAX_LARGE_PATTERN_PROCESS_EDGES = 8;
    private static final int MAX_NON_DYNAMIC_MAXIMUM_PREVIEW_HOTSPOTS = 6;
    /**
     * Identifies the worker calculation launched by the explicit rebuild action. A grid can have several concurrent
     * calculations, so only the calculation for this request may consume the pending full rebuild.
     */
    private static final ThreadLocal<OptimalRebuildRequest> ACTIVE_OPTIMAL_REBUILD_REQUEST = new ThreadLocal<>();
    /** Present only for the crafting calculation started by ApplyGray's explicit optimal rebuild action. */
    private static final ThreadLocal<OptimalRebuildContext> ACTIVE_OPTIMAL_REBUILD = new ThreadLocal<>();
    /**
     * AE2 asks for the root pattern while constructing {@link CraftingCalculation}, before its worker-thread run
     * context exists. Dynamic results from that probe would be cached before an optimal rebuild can refresh them.
     */
    private static final ThreadLocal<Integer> CRAFTING_CALCULATION_CONSTRUCTION_DEPTH = new ThreadLocal<>();
    /** Prevents a normal-pattern probe used by route costing from recursively appending dynamic patterns. */
    private static final ThreadLocal<Boolean> NORMAL_PATTERN_COST_LOOKUP = new ThreadLocal<>();
    /** Set only while the standalone tree task refreshes and materializes an optimal route. */
    private static final ThreadLocal<Boolean> OPTIMAL_ROUTE_GENERATION = new ThreadLocal<>();
    /** Generation is explicitly requested from the UI and must never occupy a server tick. */
    private static final ExecutorService PATTERN_GENERATION_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ApplyGray Pattern Generator");
        thread.setDaemon(true);
        return thread;
    });
    /** One standalone generation emits at most one bounded unresolved-leaf diagnostic. */
    private static final int MAX_PATTERN_GENERATION_NO_PATTERN_LEAF_SAMPLES = 32;
    /** A retry must reject at least one selected dynamic edge; this is only a final hard stop. */
    private static final int MAX_PATTERN_GENERATION_CYCLE_RECOVERY_ATTEMPTS = 32;
    /** A larger quota that exposes no new route frontier is a replay, not useful additional route exploration. */
    private static final int MAX_IDENTICAL_ROUTE_REFINEMENT_REPLAYS = 2;

    private static String abbreviateLargePatternRecipeKey(String recipeKey) {
        if (recipeKey == null) return "<none>";
        return recipeKey.length() <= 72 ? recipeKey :
                recipeKey.substring(0, 32) + "..." + recipeKey.substring(recipeKey.length() - 32);
    }

    /** Produces a compact item-aware key for low-frequency planner diagnostics. */
    private static String describeLargePatternDiagnosticKey(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            ItemStack stack = itemKey.getReadOnlyStack();
            String itemId = String.valueOf(stack.getItem().getRegistryName());
            String keyHash = RecipeFingerprint.sha256(RecipeFingerprint.describeKey(key)).substring(0, 12);
            return itemId + '@' + stack.getMetadata() + '#' + keyHash;
        }
        return abbreviateLargePatternRecipeKey(RecipeFingerprint.describeKey(key));
    }

    private static String describeLargePatternDiagnosticInputs(IPatternDetails details) {
        StringBuilder result = new StringBuilder("[");
        IPatternDetails.IInput[] inputs = details.getInputs();
        for (int index = 0; index < inputs.length; index++) {
            if (index > 0) result.append(',');
            GenericStack[] options = inputs[index].possibleInputs();
            result.append(options.length == 0 ? "<none>" : describeLargePatternDiagnosticKey(options[0].what()))
                    .append('x').append(inputs[index].getMultiplier());
        }
        return result.append(']').toString();
    }

    /**
     * The standalone deadline bounds recursive scoring only. Once it expires, the already-reachable selected tree
     * still needs its direct routes materialized so an intermediate never becomes an accidental leaf.
     */
    enum StandaloneTreeMaterializationStep {
        REFINED,
        FAST_CONTINUATION,
        STOP
    }

    /** Details persisted by one standalone tree and the providers whose AE2 snapshots must be republished. */
    private record StandalonePatternMaterialization(int targetCount, int patternCount, int stalePatternCount,
                                                    Set<String> affectedProviderIds) {

        private StandalonePatternMaterialization {
            affectedProviderIds = affectedProviderIds == null || affectedProviderIds.isEmpty() ?
                    Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(affectedProviderIds));
        }
    }

    private record StandalonePatternPublication(int providerCount, int refreshedProviderCount) {
    }

    /** Scoped to one {@code CraftingTreeNode.buildChildPatterns()} invocation on the AE2 calculation thread. */
    private record LargePatternSelection(Object node, AEKey target, long requestedAmount) {
    }

    private static final class CraftingProcessRequestTiming {

        private final Object process;
        private final IPatternDetails details;
        private final long requestedPatternRuns;
        private final boolean limitsQuantity;
        private final long startedAtNanos;
        private long childNanos;

        private CraftingProcessRequestTiming(Object process, IPatternDetails details, long requestedPatternRuns,
                                             boolean limitsQuantity) {
            this.process = process;
            this.details = details;
            this.requestedPatternRuns = requestedPatternRuns;
            this.limitsQuantity = limitsQuantity;
            this.startedAtNanos = System.nanoTime();
        }
    }

    /**
     * Aggregates ordinary AE2 patterns whose generic availability preview still re-enters a dynamic RecipeMap tree.
     * This is emitted only with the slow-calculation diagnostic, never once per recursive preview.
     */
    private static final class NonDynamicMaximumPreviewProfile {

        private final String detailType;
        private final String definitionFingerprint;
        private final String primaryOutput;
        private final String inputSignature;
        private final int inputCount;
        private final int outputCount;
        private final Map<Object, Boolean> processInstances = new IdentityHashMap<>();
        private int calls;
        private int limitedCalls;
        private long elapsedNanos;
        private long smallestRequestedRuns = Long.MAX_VALUE;
        private long largestRequestedRuns;

        private NonDynamicMaximumPreviewProfile(IPatternDetails details) {
            String simpleName = details.getClass().getSimpleName();
            this.detailType = simpleName.isEmpty() ? details.getClass().getName() : simpleName;
            this.definitionFingerprint = Integer.toUnsignedString(details.getDefinition().hashCode(), 16);
            this.primaryOutput = describeLargePatternDiagnosticKey(details.getPrimaryOutput().what()) + 'x' +
                    details.getPrimaryOutput().amount();
            this.inputSignature = describeLargePatternDiagnosticInputs(details);
            this.inputCount = details.getInputs().length;
            this.outputCount = details.getOutputs().size();
        }

        private void record(Object process, long requestedPatternRuns, boolean limitsQuantity, long elapsedNanos) {
            if (process != null) processInstances.put(process, Boolean.TRUE);
            calls++;
            if (limitsQuantity) limitedCalls++;
            this.elapsedNanos += Math.max(0L, elapsedNanos);
            if (requestedPatternRuns > 0) {
                smallestRequestedRuns = Math.min(smallestRequestedRuns, requestedPatternRuns);
                largestRequestedRuns = Math.max(largestRequestedRuns, requestedPatternRuns);
            }
        }

        private String describe() {
            String runRange = smallestRequestedRuns == Long.MAX_VALUE ? "n/a" :
                    smallestRequestedRuns == largestRequestedRuns ? Long.toString(smallestRequestedRuns) :
                            smallestRequestedRuns + ".." + largestRequestedRuns;
            return "{type=" + detailType + " definition=" + definitionFingerprint + " output=" + primaryOutput +
                    " inputs=" + inputCount + " outputs=" + outputCount + " processes=" + processInstances.size() +
                    " calls=" + calls + " limited=" + limitedCalls + " previewMs=" + elapsedNanos / 1_000_000L +
                    " requestedRuns=" + runRange + " inputKeys=" + inputSignature + '}';
        }
    }

    /** Aggregates hot temporary-pattern branches without emitting one record for every recursive AE2 request. */
    private static final class LargePatternProcessProfile {

        private final String recipeMapName;
        private final String recipeKey;
        private final int multiplier;
        private int requestCalls;
        private int failedRequestCalls;
        private int oneRunRequestCalls;
        private int limitedRequestCalls;
        private long requestSelfNanos;
        private int maximumPreviewCalls;
        private int limitedMaximumPreviewCalls;
        private long maximumPreviewNanos;
        private int maximumPreviewBypasses;
        private int limitedMaximumPreviewBypasses;
        private final Map<Object, Boolean> processInstances = new IdentityHashMap<>();
        private long smallestRequestedRuns = Long.MAX_VALUE;
        private long largestRequestedRuns;

        private LargePatternProcessProfile(DynamicRecipePatternDetails details) {
            this.recipeMapName = details.getRecipeMapName();
            this.recipeKey = details.getRecipeKey();
            this.multiplier = details.getRecipeRunsPerPattern();
        }

        private void recordRequest(Object process, long requestedPatternRuns, boolean limitsQuantity, boolean completed,
                                   long selfNanos) {
            recordProcessInstance(process);
            requestCalls++;
            if (!completed) failedRequestCalls++;
            if (requestedPatternRuns == 1) oneRunRequestCalls++;
            if (limitsQuantity) limitedRequestCalls++;
            requestSelfNanos += Math.max(0L, selfNanos);
            recordRequestedRuns(requestedPatternRuns);
        }

        private void recordMaximumPreview(Object process, long requestedPatternRuns, boolean limitsQuantity,
                                          long elapsedNanos) {
            recordProcessInstance(process);
            maximumPreviewCalls++;
            if (limitsQuantity) limitedMaximumPreviewCalls++;
            maximumPreviewNanos += Math.max(0L, elapsedNanos);
            recordRequestedRuns(requestedPatternRuns);
        }

        private void recordMaximumPreviewBypass(Object process, long requestedPatternRuns, boolean limitsQuantity) {
            recordProcessInstance(process);
            maximumPreviewBypasses++;
            if (limitsQuantity) limitedMaximumPreviewBypasses++;
            recordRequestedRuns(requestedPatternRuns);
        }

        private void recordRequestedRuns(long requestedPatternRuns) {
            if (requestedPatternRuns <= 0) return;
            smallestRequestedRuns = Math.min(smallestRequestedRuns, requestedPatternRuns);
            largestRequestedRuns = Math.max(largestRequestedRuns, requestedPatternRuns);
        }

        private void recordProcessInstance(Object process) {
            if (process != null) processInstances.put(process, Boolean.TRUE);
        }

        private String describe() {
            String key = abbreviateLargePatternRecipeKey(recipeKey);
            String runRange = smallestRequestedRuns == Long.MAX_VALUE ? "n/a" :
                    smallestRequestedRuns == largestRequestedRuns ? Long.toString(smallestRequestedRuns) :
                            smallestRequestedRuns + ".." + largestRequestedRuns;
            return "{map=" + recipeMapName + " batch=" + multiplier + " key=" + key +
                    " processes=" + processInstances.size() + " requests=" + requestCalls + " failed=" + failedRequestCalls +
                    " oneRun=" + oneRunRequestCalls + " limited=" + limitedRequestCalls +
                    " requestSelfMs=" + requestSelfNanos / 1_000_000L +
                    " previewCalls=" + maximumPreviewCalls +
                    " previewLimited=" + limitedMaximumPreviewCalls +
                    " previewMs=" + maximumPreviewNanos / 1_000_000L +
                    " previewBypasses=" + maximumPreviewBypasses +
                    " bypassLimited=" + limitedMaximumPreviewBypasses +
                    " requestedRuns=" + runRange + '}';
        }

        private String describeNode() {
            return recipeMapName + "#batch=" + multiplier + '[' + abbreviateLargePatternRecipeKey(recipeKey) + ']';
        }
    }

    /** One nested direct-process edge seen while AE2 expands a temporary large pattern. */
    private record LargePatternProcessEdge(String parentRecipeKey, String childRecipeKey) {
    }

    private static final class LargePatternCalculationSummary {

        private final AEKey rootTarget;
        private final long startedAtNanos = System.nanoTime();
        private int candidateNodes;
        private int totalCandidates;
        private int dynamicCandidates;
        private int replacedCandidates;
        private long largestOrdinaryRunCount;
        private int largestMultiplier;
        private int exactDynamicInputTemplateBypasses;
        private int exactDynamicInputCacheBypasses;
        private int maximumCraftablePreviewBypasses;
        private int ordinaryMaximumCraftablePreviewBypasses;
        private int candidateExpansionCalls;
        private long candidateExpansionNanos;
        private int exactDynamicInputExtractionCalls;
        private long exactDynamicInputExtractionNanos;
        private int maximumCraftablePreviewCalls;
        private long maximumCraftablePreviewNanos;
        private int dynamicMaximumCraftablePreviewCalls;
        private long dynamicMaximumCraftablePreviewNanos;
        private int craftingProcessRequestCalls;
        private long craftingProcessRequestSelfNanos;
        private int dynamicCraftingProcessRequestCalls;
        private long dynamicCraftingProcessRequestSelfNanos;
        private int largeCraftingProcessRequestCalls;
        private long largeCraftingProcessRequestSelfNanos;
        private final Map<String, LargePatternProcessProfile> largePatternProcessProfiles = new HashMap<>();
        /** Identity keys preserve distinct encoded patterns that happen to share one visible output. */
        private final Map<IPatternDetails, NonDynamicMaximumPreviewProfile> nonDynamicMaximumPreviewProfiles =
                new IdentityHashMap<>();
        private final Map<LargePatternProcessEdge, Integer> largePatternProcessEdges = new HashMap<>();
        private int maximumCraftingProcessRequestDepth;
        private int reentrantCraftingProcessRequestCalls;
        private boolean slowProgressLogged;

        private LargePatternCalculationSummary(AEKey rootTarget) {
            this.rootTarget = rootTarget;
        }

        private void recordCandidateNode(int candidateCount, int dynamicCandidateCount) {
            candidateNodes++;
            totalCandidates += Math.max(0, candidateCount);
            dynamicCandidates += Math.max(0, dynamicCandidateCount);
        }

        private boolean recordReplacement(long ordinaryRuns, int multiplier) {
            boolean firstReplacement = replacedCandidates == 0;
            replacedCandidates++;
            largestOrdinaryRunCount = Math.max(largestOrdinaryRunCount, ordinaryRuns);
            largestMultiplier = Math.max(largestMultiplier, multiplier);
            return firstReplacement;
        }

        private void recordExactDynamicInputTemplateBypass() {
            exactDynamicInputTemplateBypasses++;
        }

        private void recordExactDynamicInputCacheBypass() {
            exactDynamicInputCacheBypasses++;
        }

        private void recordMaximumCraftablePreviewBypass(Object process, IPatternDetails details, long requestedPatternRuns,
                                                         boolean limitsQuantity) {
            maximumCraftablePreviewBypasses++;
            LargePatternProcessProfile profile = getLargePatternProcessProfile(details);
            if (profile != null) profile.recordMaximumPreviewBypass(process, requestedPatternRuns, limitsQuantity);
        }

        private void recordOrdinaryMaximumCraftablePreviewBypass() {
            ordinaryMaximumCraftablePreviewBypasses++;
        }

        private void recordCandidateExpansion(long elapsedNanos) {
            candidateExpansionCalls++;
            candidateExpansionNanos += Math.max(0L, elapsedNanos);
        }

        private void recordExactDynamicInputExtraction(long elapsedNanos) {
            exactDynamicInputExtractionCalls++;
            exactDynamicInputExtractionNanos += Math.max(0L, elapsedNanos);
        }

        private void recordMaximumCraftablePreview(Object process, IPatternDetails details, long requestedPatternRuns,
                                                   boolean limitsQuantity, long elapsedNanos) {
            long elapsed = Math.max(0L, elapsedNanos);
            maximumCraftablePreviewCalls++;
            maximumCraftablePreviewNanos += elapsed;
            if (details instanceof DynamicRecipePatternDetails) {
                dynamicMaximumCraftablePreviewCalls++;
                dynamicMaximumCraftablePreviewNanos += elapsed;
            } else {
                nonDynamicMaximumPreviewProfiles.computeIfAbsent(details, NonDynamicMaximumPreviewProfile::new)
                        .record(process, requestedPatternRuns, limitsQuantity, elapsed);
            }
            LargePatternProcessProfile profile = getLargePatternProcessProfile(details);
            if (profile != null) profile.recordMaximumPreview(process, requestedPatternRuns, limitsQuantity, elapsed);
        }

        private void recordCraftingProcessRequest(Object process, IPatternDetails details, long requestedPatternRuns,
                                                  boolean limitsQuantity, boolean completed, long selfNanos) {
            long self = Math.max(0L, selfNanos);
            craftingProcessRequestCalls++;
            craftingProcessRequestSelfNanos += self;
            if (!(details instanceof DynamicRecipePatternDetails dynamic)) return;

            dynamicCraftingProcessRequestCalls++;
            dynamicCraftingProcessRequestSelfNanos += self;
            if (dynamic.isLargePattern()) {
                largeCraftingProcessRequestCalls++;
                largeCraftingProcessRequestSelfNanos += self;
                getLargePatternProcessProfile(dynamic).recordRequest(process, requestedPatternRuns, limitsQuantity,
                        completed, self);
            }
        }

        private void recordCraftingProcessRequestStart(CraftingProcessRequestTiming parent, IPatternDetails details,
                                                       int requestDepth, boolean reentrant) {
            maximumCraftingProcessRequestDepth = Math.max(maximumCraftingProcessRequestDepth, requestDepth);
            if (reentrant) reentrantCraftingProcessRequestCalls++;
            if (parent == null) return;

            DynamicRecipePatternDetails parentDynamic = getLargeDynamicPattern(parent.details);
            DynamicRecipePatternDetails childDynamic = getLargeDynamicPattern(details);
            if (parentDynamic == null || childDynamic == null) return;

            LargePatternProcessEdge edge = new LargePatternProcessEdge(parentDynamic.getRecipeKey(),
                    childDynamic.getRecipeKey());
            largePatternProcessEdges.merge(edge, 1, Integer::sum);
        }

        private LargePatternProcessProfile getLargePatternProcessProfile(IPatternDetails details) {
            DynamicRecipePatternDetails dynamic = getLargeDynamicPattern(details);
            return dynamic == null ? null : getLargePatternProcessProfile(dynamic);
        }

        @Nullable
        private DynamicRecipePatternDetails getLargeDynamicPattern(IPatternDetails details) {
            return details instanceof DynamicRecipePatternDetails dynamic && dynamic.isLargePattern() ? dynamic : null;
        }

        private LargePatternProcessProfile getLargePatternProcessProfile(DynamicRecipePatternDetails details) {
            return largePatternProcessProfiles.computeIfAbsent(details.getRecipeKey(),
                    ignored -> new LargePatternProcessProfile(details));
        }

        private boolean hasLargePatternProcessProfiles() {
            return !largePatternProcessProfiles.isEmpty();
        }

        private boolean hasNonDynamicMaximumPreviewProfiles() {
            return !nonDynamicMaximumPreviewProfiles.isEmpty();
        }

        private String describeLargePatternProcessHotspots() {
            List<LargePatternProcessProfile> profiles = new ArrayList<>(largePatternProcessProfiles.values());
            profiles.sort((left, right) -> {
                int comparison = Integer.compare(right.requestCalls, left.requestCalls);
                return comparison != 0 ? comparison : Long.compare(right.requestSelfNanos, left.requestSelfNanos);
            });

            StringBuilder description = new StringBuilder("unique=").append(profiles.size()).append(" top=[");
            int limit = Math.min(MAX_LARGE_PATTERN_PROCESS_HOTSPOTS, profiles.size());
            for (int index = 0; index < limit; index++) {
                if (index > 0) description.append(", ");
                description.append(profiles.get(index).describe());
            }
            return description.append(']').toString();
        }

        private String describeLargePatternProcessGraph() {
            List<Map.Entry<LargePatternProcessEdge, Integer>> edges = new ArrayList<>(largePatternProcessEdges.entrySet());
            edges.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));

            StringBuilder description = new StringBuilder("maxDepth=")
                    .append(maximumCraftingProcessRequestDepth)
                    .append(" reentrantCalls=").append(reentrantCraftingProcessRequestCalls)
                    .append(" edges=[");
            int limit = Math.min(MAX_LARGE_PATTERN_PROCESS_EDGES, edges.size());
            for (int index = 0; index < limit; index++) {
                if (index > 0) description.append(", ");
                Map.Entry<LargePatternProcessEdge, Integer> entry = edges.get(index);
                LargePatternProcessEdge edge = entry.getKey();
                description.append(describeLargePatternProcessNode(edge.parentRecipeKey()))
                        .append(" -> ").append(describeLargePatternProcessNode(edge.childRecipeKey()))
                        .append(" calls=").append(entry.getValue());
            }
            return description.append(']').toString();
        }

        private String describeNonDynamicMaximumPreviewHotspots() {
            List<NonDynamicMaximumPreviewProfile> profiles =
                    new ArrayList<>(nonDynamicMaximumPreviewProfiles.values());
            profiles.sort((left, right) -> {
                int comparison = Long.compare(right.elapsedNanos, left.elapsedNanos);
                return comparison != 0 ? comparison : Integer.compare(right.calls, left.calls);
            });

            StringBuilder description = new StringBuilder("unique=").append(profiles.size()).append(" top=[");
            int limit = Math.min(MAX_NON_DYNAMIC_MAXIMUM_PREVIEW_HOTSPOTS, profiles.size());
            for (int index = 0; index < limit; index++) {
                if (index > 0) description.append(", ");
                description.append(profiles.get(index).describe());
            }
            return description.append(']').toString();
        }

        private String describeLargePatternProcessNode(String recipeKey) {
            LargePatternProcessProfile profile = largePatternProcessProfiles.get(recipeKey);
            return profile == null ? abbreviateLargePatternRecipeKey(recipeKey) : profile.describeNode();
        }

        private boolean shouldLogSlowProgress() {
            if (slowProgressLogged || elapsedNanos() < SLOW_LARGE_PATTERN_CALCULATION_NANOS) return false;
            slowProgressLogged = true;
            return true;
        }

        private long elapsedNanos() {
            return System.nanoTime() - startedAtNanos;
        }
    }

    private DynamicRecipePatternRegistry() {}

    /** Marks the short constructor-time pattern probe so dynamic routes can be evaluated after the calculation starts. */
    public static void beginCraftingCalculationConstruction() {
        Integer depth = CRAFTING_CALCULATION_CONSTRUCTION_DEPTH.get();
        CRAFTING_CALCULATION_CONSTRUCTION_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    /** Ends the constructor-time pattern probe. */
    public static void endCraftingCalculationConstruction() {
        Integer depth = CRAFTING_CALCULATION_CONSTRUCTION_DEPTH.get();
        if (depth == null || depth <= 1) {
            CRAFTING_CALCULATION_CONSTRUCTION_DEPTH.remove();
        } else {
            CRAFTING_CALCULATION_CONSTRUCTION_DEPTH.set(depth - 1);
        }
    }

    /** Returns whether AE2 is prefetching root patterns during {@link CraftingCalculation} construction. */
    public static boolean isCraftingCalculationConstruction() {
        Integer depth = CRAFTING_CALCULATION_CONSTRUCTION_DEPTH.get();
        return depth != null && depth > 0;
    }

    /** Starts the rejection scope shared by an initial calculation and its bounded recovery attempts. */
    public static void beginCraftingCalculationSession(IGrid grid, AEKey rootTarget, long amount,
                                                       boolean optimalRebuild, World world) {
        CRAFTING_RECOVERY_SESSION.set(new CraftingRecoverySession(grid, rootTarget, amount, optimalRebuild,
                CycleMemoryStore.forWorld(world)));
    }

    /**
     * Builds the bounded, task-local candidate graph before AE2 starts traversing it.
     *
     * <p>These details are deliberately not registered with a provider yet. A cancelled or failed calculation must
     * not leave the first recursively visited route in the persistent pattern cache.</p>
     */
    public static void prepareCraftingCalculationPatterns() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session == null) return;
        GridState state = GRIDS.get(session.grid);
        if (state != null) state.prepareTransientPatternGraph(session);
    }

    /** True only after this task has frozen its dynamic candidate graph for AE2 to consume. */
    public static boolean hasPreparedCraftingCalculationPatterns() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        return session != null && session.isTransientGraphPrepared();
    }

    /** Records successful completion for the shared, low-frequency cycle diagnostic. */
    private static void markStandalonePatternGenerationSucceeded() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session != null) session.succeeded = true;
    }

    /** Clears one frozen generation graph while retaining the dynamic cycle edges rejected by that graph. */
    private static void resetStandalonePatternGenerationAfterCycleRecovery() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session != null) session.resetStandaloneSelectionAfterCycleRecovery();
    }

    /** Marks the calculation currently performing a lazy pattern lookup on this thread. */
    public static void enterCraftingCalculation(CraftingCalculation calculation) {
        if (calculation != null) {
            ACTIVE_CRAFTING_CALCULATION.set(calculation);
        }
    }

    /** Starts the low-frequency large-pattern diagnostic for one AE2 calculation. */
    public static void beginLargePatternCalculation(CraftingCalculation calculation) {
        DynamicRecipeInputPreview.clearCalculationState();
        CRAFTING_PROCESS_REQUEST_TIMINGS.remove();
        if (calculation == null) return;
        LARGE_PATTERN_CALCULATION_SUMMARY.set(new LargePatternCalculationSummary(calculation.getOutput()));
    }

    /** Emits one aggregate large-pattern decision record after an AE2 calculation completes. */
    public static void finishLargePatternCalculation(CraftingCalculation calculation) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        LARGE_PATTERN_CALCULATION_SUMMARY.remove();
        CRAFTING_PROCESS_REQUEST_TIMINGS.remove();
        DynamicRecipeInputPreview.clearCalculationState();
        if (summary == null) return;

        long elapsedNanos = summary.elapsedNanos();
        if (summary.replacedCandidates == 0 && elapsedNanos < SLOW_LARGE_PATTERN_CALCULATION_NANOS) return;

        ApplyGrayMod.LOGGER.info("RecipeMap large-pattern calculation root={} elapsedMs={} candidateNodes={} " +
                        "candidates={} dynamicCandidates={} replacements={} largestOrdinaryRuns={} " +
                        "largestMultiplier={} exactInputTemplateBypasses={} exactInputCacheBypasses={} " +
                        "maximumCraftablePreviewBypasses={} ordinaryMaximumCraftablePreviewBypasses={} " +
                        "candidateExpansionMs={} candidateExpansionCalls={} exactInputExtractionMs={} " +
                        "exactInputExtractionCalls={} maximumPreviewMs={} " +
                        "maximumPreviewCalls={} dynamicPreviewMs={} dynamicPreviewCalls={} processSelfMs={} " +
                        "processCalls={} dynamicProcessSelfMs={} dynamicProcessCalls={} largeProcessSelfMs={} " +
                        "largeProcessCalls={}",
                summary.rootTarget, elapsedNanos / 1_000_000L, summary.candidateNodes,
                summary.totalCandidates, summary.dynamicCandidates, summary.replacedCandidates,
                summary.largestOrdinaryRunCount, summary.largestMultiplier,
                summary.exactDynamicInputTemplateBypasses, summary.exactDynamicInputCacheBypasses,
                summary.maximumCraftablePreviewBypasses, summary.ordinaryMaximumCraftablePreviewBypasses, summary.candidateExpansionNanos / 1_000_000L,
                summary.candidateExpansionCalls, summary.exactDynamicInputExtractionNanos / 1_000_000L,
                summary.exactDynamicInputExtractionCalls, summary.maximumCraftablePreviewNanos / 1_000_000L,
                summary.maximumCraftablePreviewCalls,
                summary.dynamicMaximumCraftablePreviewNanos / 1_000_000L,
                summary.dynamicMaximumCraftablePreviewCalls, summary.craftingProcessRequestSelfNanos / 1_000_000L,
                summary.craftingProcessRequestCalls,
                summary.dynamicCraftingProcessRequestSelfNanos / 1_000_000L,
                summary.dynamicCraftingProcessRequestCalls,
                summary.largeCraftingProcessRequestSelfNanos / 1_000_000L,
                summary.largeCraftingProcessRequestCalls);
        if (elapsedNanos >= SLOW_LARGE_PATTERN_CALCULATION_NANOS && summary.hasLargePatternProcessProfiles()) {
            ApplyGrayMod.LOGGER.info("RecipeMap large-pattern process hotspots root={} {}", summary.rootTarget,
                    summary.describeLargePatternProcessHotspots());
            ApplyGrayMod.LOGGER.info("RecipeMap large-pattern process graph root={} {}", summary.rootTarget,
                    summary.describeLargePatternProcessGraph());
        }
        if (elapsedNanos >= SLOW_LARGE_PATTERN_CALCULATION_NANOS &&
                summary.hasNonDynamicMaximumPreviewProfiles()) {
            ApplyGrayMod.LOGGER.info("RecipeMap large-pattern non-dynamic maximum-preview hotspots root={} {}",
                    summary.rootTarget, summary.describeNonDynamicMaximumPreviewHotspots());
        }
    }

    /** Counts the low-level fuzzy-template bypasses used by frozen dynamic RecipeMap inputs. */
    public static void recordExactDynamicInputTemplateBypass() {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) summary.recordExactDynamicInputTemplateBypass();
    }

    /** True while the AE2 planner owns the task-local exact-input cache state. */
    public static boolean isLargePatternCalculationActive() {
        return LARGE_PATTERN_CALCULATION_SUMMARY.get() != null;
    }

    /** Counts exact cache loads that avoid scanning every fuzzy variant in the simulated inventory. */
    public static void recordExactDynamicInputCacheBypass() {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) summary.recordExactDynamicInputCacheBypass();
    }

    /**
     * A dynamic RecipeMap detail representing exactly one AE2 execution can use the normal branch request itself as
     * the availability test. AE2 already performs that request in an isolated child simulation state and commits it
     * only after it produced output. More than one execution must retain AE2's partial-availability calculation.
     */
    public static boolean canBypassDynamicPatternMaximumCraftablePreview(IPatternDetails details,
                                                                           long requestedPatternRuns) {
        return requestedPatternRuns == 1 && details instanceof DynamicRecipePatternDetails;
    }

    /**
     * Whether the maximum-craftable preview may be skipped for this process.
     *
     * <p>One-run dynamic patterns are atomic and verify availability during the request itself. Ordinary processing
     * patterns decoded by an ApplyGray provider may also skip the preview when every input can only be satisfied by
     * direct stock or by atomic dynamic patterns: the request then performs exactly the recursive work the preview
     * would have duplicated, and a request failure falls back to the node's other candidates just like an
     * underestimated preview would. Patterns whose input subtree contains other ordinary patterns keep AE2's accurate
     * (recursive) preview because they can legally consume a partial allocation across several candidates.</p>
     */
    public static boolean canBypassPatternMaximumCraftablePreview(IPatternDetails details, long requestedPatternRuns,
                                                                  List<List<IPatternDetails>> candidatesPerInput) {
        if (canBypassDynamicPatternMaximumCraftablePreview(details, requestedPatternRuns)) {
            return true;
        }
        if (!(details instanceof AEProcessingPattern) || !ExactPatternInputRegistry.isRegisteredPattern(details)) {
            return false;
        }
        return isOrdinaryPatternAtomicSafe(candidatesPerInput);
    }

    /** True when every input slot of the pattern can be satisfied without any non-dynamic child pattern. */
    static boolean isOrdinaryPatternAtomicSafe(List<List<IPatternDetails>> candidatesPerInput) {
        if (candidatesPerInput == null) return false;
        for (List<IPatternDetails> candidates : candidatesPerInput) {
            if (candidates == null) return false;
            for (IPatternDetails candidate : candidates) {
                if (!(candidate instanceof DynamicRecipePatternDetails)) return false;
            }
        }
        return true;
    }

    /** Counts one skipped preview for an ApplyGray-hosted ordinary processing pattern. */
    public static void recordOrdinaryPatternMaximumCraftablePreviewBypass() {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) summary.recordOrdinaryMaximumCraftablePreviewBypass();
    }

    /** Counts the bounded atomic-attempt path once per branch, for the calculation-level diagnostic only. */
    public static void recordDynamicPatternMaximumCraftablePreviewBypass(Object process, IPatternDetails details,
                                                                          long requestedPatternRuns,
                                                                          boolean limitsQuantity) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) {
            summary.recordMaximumCraftablePreviewBypass(process, details, requestedPatternRuns, limitsQuantity);
        }
    }

    /** Records the aggregate time spent replacing ordinary candidates with task-local large patterns. */
    public static void recordLargePatternCandidateExpansion(long elapsedNanos) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) summary.recordCandidateExpansion(elapsedNanos);
    }

    /** Records one direct inventory extraction for a frozen dynamic RecipeMap input. */
    public static void recordExactDynamicInputExtraction(long elapsedNanos) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) summary.recordExactDynamicInputExtraction(elapsedNanos);
    }

    /** Records an AE2 maximum-craftable preview that was not replaced by the atomic dynamic-pattern path. */
    public static void recordMaximumCraftablePreview(Object process, IPatternDetails details, long requestedPatternRuns,
                                                      boolean limitsQuantity, long elapsedNanos) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null) {
            summary.recordMaximumCraftablePreview(process, details, requestedPatternRuns, limitsQuantity, elapsedNanos);
        }
    }

    /** Starts one nested AE2 process-request timing scope only while this calculation's aggregate is active. */
    public static boolean beginCraftingProcessRequest(Object process, IPatternDetails details, long requestedPatternRuns,
                                                      boolean limitsQuantity) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary == null) return false;
        Deque<CraftingProcessRequestTiming> timings = CRAFTING_PROCESS_REQUEST_TIMINGS.get();
        boolean reentrant = false;
        for (CraftingProcessRequestTiming timing : timings) {
            if (timing.process == process) {
                reentrant = true;
                break;
            }
        }
        summary.recordCraftingProcessRequestStart(timings.peekFirst(), details, timings.size() + 1, reentrant);
        timings.addFirst(new CraftingProcessRequestTiming(process, details,
                requestedPatternRuns, limitsQuantity));
        return true;
    }

    /** Finishes a process request and attributes only its non-recursive work to the current detail type. */
    public static void finishCraftingProcessRequest(boolean completed) {
        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        Deque<CraftingProcessRequestTiming> timings = CRAFTING_PROCESS_REQUEST_TIMINGS.get();
        if (summary == null || timings.isEmpty()) {
            CRAFTING_PROCESS_REQUEST_TIMINGS.remove();
            return;
        }

        CraftingProcessRequestTiming timing = timings.removeFirst();
        long elapsedNanos = Math.max(0L, System.nanoTime() - timing.startedAtNanos);
        if (!timings.isEmpty()) timings.peekFirst().childNanos += elapsedNanos;
        summary.recordCraftingProcessRequest(timing.process, timing.details, timing.requestedPatternRuns, timing.limitsQuantity,
                completed, elapsedNanos - timing.childNanos);
        if (timings.isEmpty()) CRAFTING_PROCESS_REQUEST_TIMINGS.remove();
    }

    /** Removes the current calculation's cooperative lookup context. */
    public static void leaveCraftingCalculation(CraftingCalculation calculation) {
        CraftingRecoverySession recovery = CRAFTING_RECOVERY_SESSION.get();
        if (recovery != null) {
            recovery.lastCalculation = calculation;
        }
        if (ACTIVE_CRAFTING_CALCULATION.get() == calculation) {
            ACTIVE_CRAFTING_CALCULATION.remove();
        }
    }

    /** Ends one CraftingService task after all recursive-cycle recovery attempts have either succeeded or failed. */
    public static void finishCraftingCalculationSession() {
        RECURSIVE_CYCLE_RECOVERY_REQUIRED.remove();
        CraftingRecoverySession recovery = CRAFTING_RECOVERY_SESSION.get();
        CRAFTING_RECOVERY_SESSION.remove();
        if (recovery != null) {
            recovery.logSummary();
            recovery.flushCycleMemory();
        }
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
                        "leaves [elemental dust={}, elemental fluid={}]; inventory route scoring " +
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
                optimalRebuild.elementalDustLeaves.size(), optimalRebuild.elementalFluidLeaves.size(),
                optimalRebuild.inventoryScoredTargets.size(),
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

    /**
     * Resolves dynamic source routes only when every regular alternative is a same-material form conversion.
     *
     * <p>The registry owns both the normal-form classification and the rule-driven dynamic selection so AE2 callers
     * cannot combine stale cache entries with the legacy priority helpers.</p>
     */
    public static List<IPatternDetails> findDynamicPatternsForMaterialFormFallback(
            IGrid grid, AEKey target, Collection<? extends IPatternDetails> normalPatterns) {
        if (grid == null || target == null || normalPatterns == null || normalPatterns.isEmpty() ||
                !areOnlyMaterialFormChanges(target, normalPatterns)) {
            return Collections.emptyList();
        }

        List<IPatternDetails> dynamicPatterns = new ArrayList<>(findPatterns(grid, target));
        if (!containsDirectMaterialSource(dynamicPatterns)) return Collections.emptyList();
        sortPatternsForCrafting(grid, target, dynamicPatterns);
        return dynamicPatterns;
    }

    private static boolean areOnlyMaterialFormChanges(AEKey target,
                                                       Collection<? extends IPatternDetails> patterns) {
        for (IPatternDetails pattern : patterns) {
            if (!isMaterialFormChangePattern(target, pattern)) return false;
        }
        return true;
    }

    private static boolean containsDirectMaterialSource(Collection<? extends IPatternDetails> patterns) {
        for (IPatternDetails pattern : patterns) {
            DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
            if (dynamic == null) continue;
            CandidateRoutePriority priority = dynamic.getRoutePriority();
            if (priority == CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS ||
                    priority == CandidateRoutePriority.DUST_OR_FLUID_INPUT ||
                    priority == CandidateRoutePriority.INGOT_INPUT) {
                return true;
            }
        }
        return false;
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

    public static boolean refreshProvider(MetaTileEntityMERecipeMapPatternProvider provider) {
        return refreshProvider(provider, false,
                MetaTileEntityMERecipeMapPatternProvider.DynamicSnapshotPurpose.PERSISTED_PATTERN_PUBLICATION);
    }

    /** Ensures an active provider is registered without re-sampling an unchanged provider on every AE state event. */
    public static boolean refreshProviderAfterStateChange(MetaTileEntityMERecipeMapPatternProvider provider) {
        if (hasRegisteredProviderSnapshot(provider)) return false;
        return refreshProvider(provider, false,
                MetaTileEntityMERecipeMapPatternProvider.DynamicSnapshotPurpose.PERSISTED_PATTERN_PUBLICATION);
    }

    /** Replaces a provider snapshot immediately after an administrator changes its planning configuration. */
    public static void refreshProviderImmediately(MetaTileEntityMERecipeMapPatternProvider provider) {
        refreshProvider(provider, true,
                MetaTileEntityMERecipeMapPatternProvider.DynamicSnapshotPurpose.PERSISTED_PATTERN_PUBLICATION);
    }

    /**
     * Registers a provider after AE2 has moved its node to another grid.
     */
    public static boolean refreshProviderAfterGridChange(MetaTileEntityMERecipeMapPatternProvider provider) {
        // A state callback registers only providers without a snapshot. Avoid rebuilding an existing definition;
        // a real move can transfer it, while a first registration still falls back to a forced snapshot.
        if (isRegisteredOnCurrentGrid(provider)) return false;
        // The provider definition is independent from its grid. Moving the existing immutable snapshot avoids
        // capturing every controller's capabilities synchronously when a cable merges two large networks.
        if (relocateRegisteredProviderToCurrentGrid(provider)) return false;
        return refreshProvider(provider, true,
                MetaTileEntityMERecipeMapPatternProvider.DynamicSnapshotPurpose.PERSISTED_PATTERN_PUBLICATION);
    }

    /** Captures worker-safe controller state only for an explicitly requested standalone pattern generation. */
    private static void refreshProvidersForPatternGeneration(IGrid grid) {
        if (grid == null) return;

        for (MetaTileEntityMERecipeMapPatternProvider provider :
                grid.getMachines(MetaTileEntityMERecipeMapPatternProvider.class)) {
            refreshProvider(provider, true,
                    MetaTileEntityMERecipeMapPatternProvider.DynamicSnapshotPurpose.PATTERN_GENERATION);
        }
    }

    private static boolean refreshProvider(MetaTileEntityMERecipeMapPatternProvider provider, boolean force,
                                           MetaTileEntityMERecipeMapPatternProvider.DynamicSnapshotPurpose purpose) {
        String providerId = provider.getDynamicProviderId();
        long now = System.nanoTime();
        Long lastRefresh = LAST_REFRESH_NANOS.get(providerId);
        if (!force && lastRefresh != null && (now - lastRefresh) < REFRESH_DEBOUNCE_NANOS) {
            return false;
        }
        LAST_REFRESH_NANOS.put(providerId, now);

        ProviderSnapshot snapshot = provider.createDynamicSnapshot(purpose);
        IGrid oldGrid = PROVIDER_GRIDS.get(providerId);
        GridState oldState = oldGrid == null ? null : GRIDS.get(oldGrid);
        ProviderSnapshot previousSnapshot = oldState == null ? null : oldState.getProviderSnapshot(providerId);

        if (snapshot == null) {
            if (oldGrid != null) unregister(provider);
            return false;
        }

        // Grid.add mounts the native provider before GridNode invokes its listener. Refresh only when the published
        // definition changed, not merely because a provider crossed into another grid.
        boolean requiresNativePatternRefresh = previousSnapshot == null || !snapshot.sameDefinition(previousSnapshot);

        if (oldGrid != null && oldGrid != snapshot.grid) {
            if (oldState != null && oldState.removeProvider(providerId)) {
                GRIDS.remove(oldGrid, oldState);
            }
        }

        GridState state = GRIDS.computeIfAbsent(snapshot.grid, ignored -> new GridState());
        state.putProvider(snapshot);
        PROVIDER_GRIDS.put(providerId, snapshot.grid);
        return requiresNativePatternRefresh;
    }

    /** Checks the cheap node/grid identity before taking a full controller and RecipeMap snapshot. */
    private static boolean isRegisteredOnCurrentGrid(MetaTileEntityMERecipeMapPatternProvider provider) {
        IGrid registeredGrid = PROVIDER_GRIDS.get(provider.getDynamicProviderId());
        if (registeredGrid == null) return false;
        try {
            return provider.getMainNode().getGrid() == registeredGrid;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /** Returns whether a lifecycle callback can retain its existing snapshot until the grid-change callback moves it. */
    public static boolean hasRegisteredProviderSnapshot(MetaTileEntityMERecipeMapPatternProvider provider) {
        String providerId = provider.getDynamicProviderId();
        IGrid grid = PROVIDER_GRIDS.get(providerId);
        GridState state = grid == null ? null : GRIDS.get(grid);
        return state != null && state.getProviderSnapshot(providerId) != null;
    }

    /** Returns whether the provider snapshot belongs to the node's current AE2 grid. */
    public static boolean hasRegisteredProviderSnapshotOnCurrentGrid(
            MetaTileEntityMERecipeMapPatternProvider provider) {
        return isRegisteredOnCurrentGrid(provider) && hasRegisteredProviderSnapshot(provider);
    }

    /** Transfers a completed provider definition without touching the controller or its exposed RecipeMaps. */
    private static boolean relocateRegisteredProviderToCurrentGrid(
            MetaTileEntityMERecipeMapPatternProvider provider) {
        String providerId = provider.getDynamicProviderId();
        IGrid oldGrid = PROVIDER_GRIDS.get(providerId);
        if (oldGrid == null) return false;

        IGrid currentGrid;
        try {
            currentGrid = provider.getMainNode().getGrid();
        } catch (IllegalStateException ignored) {
            return false;
        }
        if (currentGrid == null || currentGrid == oldGrid) return false;

        GridState oldState = GRIDS.get(oldGrid);
        ProviderSnapshot existing = oldState == null ? null : oldState.getProviderSnapshot(providerId);
        if (existing == null) return false;

        if (oldState.removeProviderForGridTransfer(providerId)) {
            GRIDS.remove(oldGrid, oldState);
        }
        GridState currentState = GRIDS.computeIfAbsent(currentGrid, ignored -> new GridState());
        currentState.putMigratedProvider(existing.relocatedTo(currentGrid));
        PROVIDER_GRIDS.put(providerId, currentGrid);
        return true;
    }

    public static void unregister(MetaTileEntityMERecipeMapPatternProvider provider) {
        String providerId = provider.getDynamicProviderId();
        PROVIDER_DIAGNOSTICS.remove(providerId);
        // A later activation starts a new lifecycle. Retaining the old debounce point can suppress the only
        // snapshot that makes its persisted details visible to AE2 again.
        LAST_REFRESH_NANOS.remove(providerId);
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

    /**
     * Moves the route selected by the latest standalone generation ahead of competing patterns for the same output.
     * Competing physical and dynamic patterns remain available as fallbacks; only their evaluation order changes.
     */
    public static Collection<IPatternDetails> prioritizeStandalonePatterns(IGrid grid, AEKey target,
                                                                            Collection<IPatternDetails> patterns) {
        if (grid == null || target == null || patterns == null || patterns.size() < 2) return patterns;
        GridState state = GRIDS.get(grid);
        return state == null ? patterns : state.prioritizeStandalonePatterns(target, patterns);
    }

    /**
     * Materializes the selected dynamic route in the background for the standalone pattern tree.
     * This only prepares RecipeMap patterns; it deliberately does not create an AE2 crafting plan.
     */
    public static void generatePatternTreeAsync(@Nullable IGrid grid, @Nullable World world, @Nullable AEKey target, long amount,
                                                Consumer<PatternGenerationTreeData> completion) {
        if (grid == null || target == null || amount <= 0) {
            completion.accept(PatternGenerationTreeData.unavailable());
            return;
        }
        // The GUI action arrives on the server thread; freeze controller state before this work moves off-thread.
        refreshProvidersForPatternGeneration(grid);
        GridState state = GRIDS.get(grid);
        if (state == null) {
            completion.accept(PatternGenerationTreeData.unavailable());
            return;
        }

        PATTERN_GENERATION_EXECUTOR.execute(() -> {
            OPTIMAL_ROUTE_GENERATION.set(Boolean.TRUE);
            try {
                state.prepareOptimalRouteGeneration();
                // The standalone view must freeze the complete selected dependency tree before it walks any node.
                // Otherwise traversal order can materialize a provisional child route that differs from the route
                // eventually persisted, or can expose an intermediate as a leaf before its route was selected.
                beginCraftingCalculationSession(grid, target, amount, true, world);
                LiteCraftTreeNode root = null;
                for (int attempt = 0; attempt < MAX_PATTERN_GENERATION_CYCLE_RECOVERY_ATTEMPTS; attempt++) {
                    prepareCraftingCalculationPatterns();
                    PatternGenerationTreeBuilder tree = state.generatePatternTree(target, amount);
                    root = tree.getRoot();
                    tree.verifyFrozenDynamicRoutes();
                    if (!tree.rejectObservedDynamicCycles()) break;
                    if (attempt + 1 >= MAX_PATTERN_GENERATION_CYCLE_RECOVERY_ATTEMPTS) {
                        ApplyGrayMod.LOGGER.warn("Standalone RecipeMap pattern generation exhausted dynamic cycle " +
                                "recovery attempts root={} amount={} attempts={}", target, amount, attempt + 1);
                        completion.accept(PatternGenerationTreeData.failed());
                        return;
                    }
                    resetStandalonePatternGenerationAfterCycleRecovery();
                    ApplyGrayMod.LOGGER.info("Retrying standalone RecipeMap pattern generation root={} amount={} " +
                                    "after dynamic cycle recovery attempt={}", target, amount, attempt + 1);
                }
                if (root == null) {
                    completion.accept(PatternGenerationTreeData.failed());
                    return;
                }
                StandalonePatternMaterialization materialization = state.materializePreparedTransientPatterns();
                markStandalonePatternGenerationSucceeded();
                publishStandalonePatternGeneration(world, state, target, amount, root, materialization, completion);
            } catch (RuntimeException exception) {
                ApplyGrayMod.LOGGER.warn("RecipeMap pattern tree generation failed target={} amount={}",
                        target, amount, exception);
                completion.accept(PatternGenerationTreeData.failed());
            } finally {
                finishCraftingCalculationSession();
                OPTIMAL_ROUTE_GENERATION.remove();
            }
        });
    }

    /** Publishes the materialized details through the same deferred provider update used by normal pattern changes. */
    private static void publishStandalonePatternGeneration(@Nullable World world, GridState state, AEKey target,
                                                            long amount, LiteCraftTreeNode root,
                                                            StandalonePatternMaterialization materialization,
                                                            Consumer<PatternGenerationTreeData> completion) {
        if (world == null || world.isRemote) {
            ApplyGrayMod.LOGGER.warn("Could not publish standalone RecipeMap patterns without a server world root={} " +
                    "amount={}", target, amount);
            completion.accept(PatternGenerationTreeData.failed());
            return;
        }
        MinecraftServer server = world.getMinecraftServer();
        if (server == null) {
            ApplyGrayMod.LOGGER.warn("Could not publish standalone RecipeMap patterns because the server is unavailable " +
                    "root={} amount={}", target, amount);
            completion.accept(PatternGenerationTreeData.failed());
            return;
        }

        try {
            server.addScheduledTask(() -> {
                try {
                    StandalonePatternPublication publication = state.publishStandalonePatternGeneration(
                            materialization.affectedProviderIds());
                    if (publication.providerCount() > 0 && publication.refreshedProviderCount() == 0) {
                        ApplyGrayMod.LOGGER.warn("Could not publish standalone RecipeMap pattern generation root={} " +
                                        "amount={} materializedTargets={} materializedPatterns={} stalePatterns={} " +
                                        "refreshedProviders=0/{}",
                                target, amount, materialization.targetCount(), materialization.patternCount(),
                                materialization.stalePatternCount(), publication.providerCount());
                        completion.accept(PatternGenerationTreeData.failed());
                        return;
                    }
                    ApplyGrayMod.LOGGER.info("Published standalone RecipeMap pattern generation root={} amount={} " +
                                    "materializedTargets={} materializedPatterns={} stalePatterns={} " +
                                    "refreshedAffectedProviders={}/{}",
                            target, amount, materialization.targetCount(), materialization.patternCount(),
                            materialization.stalePatternCount(), publication.refreshedProviderCount(),
                            publication.providerCount());
                    completion.accept(PatternGenerationTreeData.ready(root));
                } catch (RuntimeException exception) {
                    ApplyGrayMod.LOGGER.warn("Could not publish standalone RecipeMap patterns root={} amount={}",
                            target, amount, exception);
                    completion.accept(PatternGenerationTreeData.failed());
                }
            });
        } catch (RuntimeException exception) {
            ApplyGrayMod.LOGGER.warn("Could not schedule standalone RecipeMap pattern publication root={} amount={}",
                    target, amount, exception);
            completion.accept(PatternGenerationTreeData.failed());
        }
    }

    public static ICraftingProvider getProvider(IPatternDetails details) {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session != null) {
            ICraftingProvider provider = session.getTransientProvider(details);
            if (provider != null) return provider;
        }
        for (GridState state : GRIDS.values()) {
            ICraftingProvider provider = state.providersByPattern.get(details);
            if (provider != null) return provider;
        }
        return null;
    }

    public static DynamicRecipePatternDetails getDynamicPattern(IPatternDetails details) {
        return details instanceof DynamicRecipePatternDetails ? (DynamicRecipePatternDetails) details : null;
    }

    /** Opens the remaining-demand scope used to replace one node's ordinary dynamic details with large details. */
    public static void beginLargePatternSelection(Object node, AEKey target, long requestedAmount) {
        if (node == null || target == null || requestedAmount <= 0) {
            ACTIVE_LARGE_PATTERN_SELECTION.remove();
            return;
        }
        ACTIVE_LARGE_PATTERN_SELECTION.set(new LargePatternSelection(node, target, requestedAmount));
    }

    /** Drops a selection leaked by an aborted build before it can affect a different tree node. */
    public static void clearStaleLargePatternSelection(Object node) {
        LargePatternSelection selection = ACTIVE_LARGE_PATTERN_SELECTION.get();
        if (selection != null && selection.node() != node) {
            ACTIVE_LARGE_PATTERN_SELECTION.remove();
        }
    }

    /** Returns whether the current node owns the active remaining-demand scaling scope. */
    public static boolean hasLargePatternSelection(Object node, AEKey target) {
        LargePatternSelection selection = ACTIVE_LARGE_PATTERN_SELECTION.get();
        return selection != null && selection.node() == node && selection.target().equals(target);
    }

    /** Closes this node's selection scope without affecting a nested or later node. */
    public static void finishLargePatternSelection(Object node) {
        LargePatternSelection selection = ACTIVE_LARGE_PATTERN_SELECTION.get();
        if (selection != null && selection.node() == node) {
            ACTIVE_LARGE_PATTERN_SELECTION.remove();
        }
    }

    /**
     * Replaces an eligible dynamic candidate with one large detail before AE2 constructs its processes.
     * AE2 preflights every candidate recursively, so retaining the ordinary detail beside the large one preserves
     * the full expensive branch and defeats batching. A large detail can cover the final partial batch by rounding
     * up and returning ordinary crafting surplus.
     */
    public static Collection<IPatternDetails> expandLargePatternCandidatesForCurrentSelection(
            Object node, AEKey target, Collection<IPatternDetails> candidates) {
        if (!hasLargePatternSelection(node, target) || candidates == null || candidates.isEmpty()) {
            return candidates == null ? Collections.emptyList() : candidates;
        }

        long startedAtNanos = System.nanoTime();
        try {
            List<IPatternDetails> expanded = new ArrayList<>(candidates.size());
            boolean changed = false;
            int dynamicCandidateCount = 0;
            for (IPatternDetails candidate : candidates) {
                DynamicRecipePatternDetails dynamic = getDynamicPattern(candidate);
                if (dynamic != null) dynamicCandidateCount++;
                DynamicRecipePatternDetails large = dynamic == null ? null :
                        createLargePatternForCurrentSelection(node, target, dynamic);
                if (large != null) {
                    expanded.add(large);
                    changed = true;
                    continue;
                }
                expanded.add(candidate);
            }
            LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
            if (summary != null) {
                summary.recordCandidateNode(candidates.size(), dynamicCandidateCount);
                if (summary.shouldLogSlowProgress()) {
                    ApplyGrayMod.LOGGER.info("RecipeMap large-pattern calculation still running root={} candidateNodes={} " +
                                    "candidates={} dynamicCandidates={} replacements={} largestOrdinaryRuns={} " +
                                    "largestMultiplier={} exactInputTemplateBypasses={} exactInputCacheBypasses={} " +
                                    "maximumCraftablePreviewBypasses={} ordinaryMaximumCraftablePreviewBypasses={} " +
                                    "candidateExpansionMs={} " +
                                    "candidateExpansionCalls={} exactInputExtractionMs={} " +
                                    "exactInputExtractionCalls={} maximumPreviewMs={} maximumPreviewCalls={} " +
                                    "dynamicPreviewMs={} dynamicPreviewCalls={} processSelfMs={} processCalls={} " +
                                    "dynamicProcessSelfMs={} dynamicProcessCalls={} largeProcessSelfMs={} " +
                                    "largeProcessCalls={}",
                            summary.rootTarget, summary.candidateNodes, summary.totalCandidates,
                            summary.dynamicCandidates, summary.replacedCandidates, summary.largestOrdinaryRunCount,
                            summary.largestMultiplier, summary.exactDynamicInputTemplateBypasses,
                            summary.exactDynamicInputCacheBypasses, summary.maximumCraftablePreviewBypasses,
                            summary.ordinaryMaximumCraftablePreviewBypasses,
                            summary.candidateExpansionNanos / 1_000_000L, summary.candidateExpansionCalls,
                            summary.exactDynamicInputExtractionNanos / 1_000_000L,
                            summary.exactDynamicInputExtractionCalls,
                            summary.maximumCraftablePreviewNanos / 1_000_000L,
                            summary.maximumCraftablePreviewCalls,
                            summary.dynamicMaximumCraftablePreviewNanos / 1_000_000L,
                            summary.dynamicMaximumCraftablePreviewCalls,
                            summary.craftingProcessRequestSelfNanos / 1_000_000L,
                            summary.craftingProcessRequestCalls,
                            summary.dynamicCraftingProcessRequestSelfNanos / 1_000_000L,
                            summary.dynamicCraftingProcessRequestCalls,
                            summary.largeCraftingProcessRequestSelfNanos / 1_000_000L,
                            summary.largeCraftingProcessRequestCalls);
                }
            }
            return changed ? Collections.unmodifiableList(expanded) : candidates;
        } finally {
            recordLargePatternCandidateExpansion(System.nanoTime() - startedAtNanos);
        }
    }

    /**
     * Builds a task-local large detail for the active node when it keeps this individual pattern within the bounded
     * AE2 execution range. The last operation may round up, but its scaled inputs are planned recursively.
     */
    @Nullable
    public static DynamicRecipePatternDetails createLargePatternForCurrentSelection(
            Object node, AEKey target, DynamicRecipePatternDetails source) {
        LargePatternSelection selection = ACTIVE_LARGE_PATTERN_SELECTION.get();
        if (selection == null || selection.node() != node || !selection.target().equals(target) ||
                source == null || source.isLargePattern() || source.consumes(target)) {
            return null;
        }

        long outputPerRun = source.getNetOutputAmount(target);
        if (outputPerRun <= 0) return null;
        long ordinaryRuns = divideCeil(selection.requestedAmount(), outputPerRun);
        int multiplier = LargePatternMultiplier.chooseMultiplier(ordinaryRuns,
                source.getMaximumLargePatternMultiplier());
        if (multiplier <= 1) return null;

        DynamicRecipePatternDetails large = source.createLargePattern(multiplier);
        if (large == null || !retainLargePatternProvider(source, large)) return null;

        LargePatternCalculationSummary summary = LARGE_PATTERN_CALCULATION_SUMMARY.get();
        if (summary != null && summary.recordReplacement(ordinaryRuns, multiplier)) {
            ApplyGrayMod.LOGGER.info("RecipeMap temporary large pattern activated root={} target={} ordinaryRuns={} " +
                            "multiplier={} plannedRuns={}",
                    summary.rootTarget, target, ordinaryRuns, multiplier,
                    LargePatternMultiplier.getPlannedRuns(ordinaryRuns, multiplier));
        }
        return large;
    }

    private static long divideCeil(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) return 0;
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : quotient + 1;
    }

    /**
     * Keeps a task-local large detail resolvable by AE2's CPU without publishing it to future crafting lookups.
     * Weak ownership entries disappear after the submitted plan and CPU no longer reference the detail.
     */
    private static boolean retainLargePatternProvider(IPatternDetails source, IPatternDetails large) {
        ICraftingProvider provider = getProvider(source);
        if (provider == null) return false;

        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session != null) {
            GridState state = GRIDS.get(session.grid);
            if (state != null) {
                state.providersByPattern.put(large, provider);
                return true;
            }
        }
        for (GridState state : GRIDS.values()) {
            if (state.providersByPattern.get(source) == provider) {
                state.providersByPattern.put(large, provider);
                return true;
            }
        }
        return false;
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

    /** Returns whether this exact persisted detail is registered to an active dynamic route set. */
    public static boolean isPublishedDynamicPattern(DynamicRecipePatternDetails detail,
                                                    MetaTileEntityMERecipeMapPatternProvider provider) {
        if (detail == null || provider == null) return false;
        IGrid grid = PROVIDER_GRIDS.get(provider.getDynamicProviderId());
        GridState state = grid == null ? null : GRIDS.get(grid);
        return state != null && state.isPublishedDynamicPattern(detail, provider, grid);
    }

    /**
     * Reports why restored entries are still absent from this provider's published registry view.
     *
     * <p>This is intentionally a recovery-time diagnostic: it never captures runtime furnace fallbacks and does not
     * generate new patterns. The exact binding resolver remains the authority for a saved recipe's identity; rule and
     * machine-profile versions describe the old planning context and are not a second persistence version gate.</p>
     */
    public static PersistedPatternPublicationStatus inspectPersistedPatternPublication(
            MetaTileEntityMERecipeMapPatternProvider provider) {
        if (provider == null) {
            return PersistedPatternPublicationStatus.empty();
        }

        int cachedPatternCount = provider.getCachedDynamicPatterns().size();
        String providerId = provider.getDynamicProviderId();
        IGrid grid = PROVIDER_GRIDS.get(providerId);
        GridState state = grid == null ? null : GRIDS.get(grid);
        if (state == null) {
            return PersistedPatternPublicationStatus.awaitingSnapshot(cachedPatternCount);
        }
        return state.inspectPersistedPatternPublication(provider, providerId, cachedPatternCount);
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

    /** Clears dynamic patterns from a recursive segment only while an explicit optimal rebuild recalculates it. */
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
     * Records the exact non-productive cycle observed by AE2 and rejects matching dynamic edges for this task only.
     * The target and pattern lists describe the aligned request/process stack slice beginning at the repeated key.
     */
    public static int rejectRecursiveCycle(AEKey repeatedTarget, List<AEKey> cycleTargets,
                                           List<? extends IPatternDetails> cyclePatterns) {
        if (repeatedTarget == null || cycleTargets == null || cyclePatterns == null || cycleTargets.isEmpty() ||
                cycleTargets.size() != cyclePatterns.size()) {
            return 0;
        }

        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session == null) return 0;
        GridState state = GRIDS.get(session.grid);
        if (state == null) return 0;

        int progress = session.rejectCycle(state, repeatedTarget, cycleTargets, cyclePatterns);
        int removed = 0;
        if (session.optimalRebuild) {
            for (int index = 0; index < cycleTargets.size(); index++) {
                AEKey target = cycleTargets.get(index);
                IPatternDetails pattern = cyclePatterns.get(index);
                if (target != null && pattern != null) {
                    removed += state.invalidateRecursiveCycleForOptimalRebuild(target, List.of(pattern));
                }
            }
        }
        if (progress > 0 || removed > 0) {
            RECURSIVE_CYCLE_RECOVERY_REQUIRED.set(Boolean.TRUE);
        }
        return progress + removed;
    }

    /** Clears the current crafting thread's recursive-cycle recovery signal. */
    public static void clearRecursiveCycleRecovery() {
        RECURSIVE_CYCLE_RECOVERY_REQUIRED.remove();
    }

    /** Returns whether the current calculation learned a new recursive-cycle filter, then clears the signal. */
    public static boolean consumeRecursiveCycleRecovery() {
        boolean required = Boolean.TRUE.equals(RECURSIVE_CYCLE_RECOVERY_REQUIRED.get());
        RECURSIVE_CYCLE_RECOVERY_REQUIRED.remove();
        return required;
    }

    /**
     * Materializes only candidates missing at targets affected by this ordinary calculation's cycle filters.
     * Explicit optimal rebuilds deliberately leave expansion to the next root traversal.
     */
    public static CraftingCalculation prepareIncrementalCraftingRecovery() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session == null || session.optimalRebuild) return null;
        Set<AEKey> changedTargets = session.drainPendingExpansions();
        if (changedTargets.isEmpty()) return null;
        GridState state = GRIDS.get(session.grid);
        if (state != null) {
            if (session.isTransientGraphPrepared()) {
                state.refreshTransientPatterns(session, changedTargets);
            } else {
                state.materializePendingRecoveryPatterns(session, changedTargets);
            }
        }
        CraftingCalculation calculation = session.lastCalculation;
        if (calculation instanceof IncrementalCraftingCalculation incremental) {
            incremental.applygray$prepareIncrementalRetry(changedTargets);
            return calculation;
        }
        return null;
    }

    /** Records a retry only after the preceding cycle observation added a new session rejection. */
    public static void recordRecursiveCycleRecoveryRetry() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session != null) {
            session.recoveryAttempts++;
        }
    }

    /** Marks the enclosing task successful so its single recovery summary can report the final outcome. */
    public static void recordCraftingCalculationSucceeded(ICraftingPlan plan) {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (session != null) {
            session.succeeded = true;
            GridState state = GRIDS.get(session.grid);
            if (state != null) state.commitTransientPlanPatterns(session, plan);
        }
    }

    /**
     * Validates a dynamic detail retrieved from AE2's own crafting cache.
     *
     * <p>AE2 can retain a detail after an optimal rebuild has evicted it from this registry. Such a detail must not
     * suppress a fresh RecipeMap scan, or the rebuild will reuse its old route instead of considering new candidates.</p>
     */
    public static boolean isRegisteredPatternAvailableFor(IGrid grid, AEKey target, IPatternDetails details) {
        DynamicRecipePatternDetails dynamic = getDynamicPattern(details);
        if (dynamic == null) return true;
        if (grid == null || target == null || !dynamic.netProduces(target)) return false;

        GridState state = GRIDS.get(grid);
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        boolean available = state != null && (session != null && session.matches(state) &&
                session.isTransientPattern(target, dynamic) || state.isRegisteredPatternAvailableFor(target, dynamic));
        if (available && session != null && session.matches(state)) {
            session.recordExposed(target, dynamic);
        }
        return available;
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

    private static int compareCycleMemoryHint(RecipeBinding left, RecipeBinding right) {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        return session == null ? 0 : session.compareCycleMemoryHint(left, right);
    }

    /**
     * Reorders dynamic candidates using current network stock and already-mounted patterns.
     *
     * <p>A direct-input pass first removes obvious losers without expanding RecipeMap dependencies. If direct stock
     * does not decide the route, one representative of every distinct dependency shape receives bounded recursive
     * scoring before duplicate shapes consume remaining refinement slots. Inventory-dependent values live only for
     * this lookup and are never persisted in pattern NBT.</p>
     */
    public static void sortPatternsForCrafting(IGrid grid, AEKey requested, List<IPatternDetails> patterns) {
        if (!isOptimalRebuildCalculation() || requested == null || patterns.size() < 2) return;
        GridState state = GRIDS.get(grid);
        if (state == null) return;

        long startedAt = System.nanoTime();
        RouteCostEstimator estimator = new RouteCostEstimator(grid, state, newRouteCostBudget());
        PlanningMode planningMode = resolveDetailPlanningMode(patterns);
        Map<IPatternDetails, DirectRouteCost> quickCosts = new IdentityHashMap<>();
        for (IPatternDetails pattern : patterns) {
            quickCosts.put(pattern, estimator.estimateDirect(pattern, requested));
        }
        patterns.sort((left, right) -> {
            DynamicRecipePatternDetails leftDynamic = (DynamicRecipePatternDetails) left;
            DynamicRecipePatternDetails rightDynamic = (DynamicRecipePatternDetails) right;
            int memoryHint = compareCycleMemoryHint(leftDynamic.getRecipeBinding(), rightDynamic.getRecipeBinding());
            if (memoryHint != 0) return memoryHint;
            int staticCost = compareDynamicPatternPriority(requested, leftDynamic, rightDynamic, planningMode);
            int quickCost = quickCosts.get(left).compareTo(quickCosts.get(right));
            return compareRouteAndStaticCost(planningMode, quickCost, staticCost);
        });

        boolean stockOnlySelection = quickCosts.get(patterns.get(0)).isFullyStocked();
        Map<IPatternDetails, RouteCost> refinedCosts = new IdentityHashMap<>();
        if (!stockOnlySelection) {
            List<IPatternDetails> refined = selectDiverseCandidates(patterns,
                    getPlanningBudget().getMaxRefinedCandidates(),
                    DynamicRecipePatternRegistry::dependencyOptions);
            Map<IPatternDetails, RouteScoringProgress> refinedProgress = new IdentityHashMap<>();
            scoreRefinedRoutes(refined, refinedCosts, refinedProgress, estimator,
                    (pattern, quota) -> estimator.estimateRootWithQuota(pattern, requested, quota));
            refined.sort((left, right) -> {
                DynamicRecipePatternDetails leftDynamic = (DynamicRecipePatternDetails) left;
                DynamicRecipePatternDetails rightDynamic = (DynamicRecipePatternDetails) right;
                int memoryHint = compareCycleMemoryHint(leftDynamic.getRecipeBinding(), rightDynamic.getRecipeBinding());
                if (memoryHint != 0) return memoryHint;
                int staticCost = compareDynamicPatternPriority(requested, leftDynamic, rightDynamic, planningMode);
                int routeCost = refinedCosts.get(left).compareTo(refinedCosts.get(right));
                return compareRouteAndStaticCost(planningMode, routeCost, staticCost);
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

    /** Resource and stock planning compare the dependency tree before machine-local recipe preferences. */
    static int compareRouteAndStaticCost(PlanningMode mode, int routeCost, int staticCost) {
        if (mode == PlanningMode.STOCK_FIRST || mode == PlanningMode.RESOURCE_FIRST) {
            return routeCost != 0 ? routeCost : staticCost;
        }
        return staticCost != 0 ? staticCost : routeCost;
    }

    /**
     * The direct-input pass may skip recursive scoring when a route is already stocked. Keep the same solid-form
     * hierarchy here so a stocked generic polymer form cannot bypass its direct chemical-production route.
     */
    static int compareDirectRouteCost(int leftUnresolvedInputs, long leftMaterialFormCost,
                                      int leftDependentInputs, long leftMissingMaterials,
                                      long leftConsumedStockMaterials, int rightUnresolvedInputs,
                                      long rightMaterialFormCost, int rightDependentInputs,
                                      long rightMissingMaterials, long rightConsumedStockMaterials) {
        int unresolved = Integer.compare(leftUnresolvedInputs, rightUnresolvedInputs);
        if (unresolved != 0) return unresolved;
        int forms = Long.compare(leftMaterialFormCost, rightMaterialFormCost);
        if (forms != 0) return forms;
        int dependencies = Integer.compare(leftDependentInputs, rightDependentInputs);
        if (dependencies != 0) return dependencies;
        int missing = Long.compare(leftMissingMaterials, rightMissingMaterials);
        return missing != 0 ? missing : Long.compare(leftConsumedStockMaterials, rightConsumedStockMaterials);
    }

    /** A direct elemental synthesis is a stable source, not a byproduct-recovery shortcut. */
    static int compareStandalonePrimaryCompoundSynthesis(boolean leftPrimary, boolean rightPrimary) {
        return Boolean.compare(rightPrimary, leftPrimary);
    }

    /** A recovered machine or component is a fallback behind an equally direct material source in a saved tree. */
    static int compareStandaloneRecyclingRoute(boolean leftRecycling, boolean rightRecycling) {
        return Boolean.compare(leftRecycling, rightRecycling);
    }

    /**
     * A saved standalone tree needs a stable material source before it can compare recursive dependency cost.
     * Polymer fluids made directly in a chemical route must not collapse into an automatic extraction from their
     * own dust, while solid outputs retain their canonical dust-to-ingot or ingot-to-shape source form.
     */
    static int compareStandaloneSourcePreference(boolean solidMaterialTarget, int leftMaterialFormCost,
                                                 int rightMaterialFormCost, boolean polymerFluidTarget,
                                                 boolean leftDirectChemicalSynthesis,
                                                 boolean rightDirectChemicalSynthesis) {
        int chemical = compareStandaloneDirectChemicalSynthesis(polymerFluidTarget,
                leftDirectChemicalSynthesis, rightDirectChemicalSynthesis);
        if (chemical != 0) return chemical;
        return solidMaterialTarget ? Integer.compare(leftMaterialFormCost, rightMaterialFormCost) : 0;
    }

    /** A direct polymer reaction is a source; extracting the same polymer from powder is only a form conversion. */
    static int compareStandaloneDirectChemicalSynthesis(boolean polymerFluidTarget,
                                                         boolean leftDirectChemicalSynthesis,
                                                         boolean rightDirectChemicalSynthesis) {
        if (!polymerFluidTarget) return 0;
        return Boolean.compare(rightDirectChemicalSynthesis, leftDirectChemicalSynthesis);
    }

    private static int compareStandaloneSourcePreference(AEKey target, PatternCandidate left,
                                                         PatternCandidate right) {
        int chemical = compareStandaloneDirectChemicalSynthesis(isChemicalProductFluidTarget(target),
                isDirectChemicalPolymerSynthesis(target, left),
                isDirectChemicalPolymerSynthesis(target, right));
        if (chemical != 0) return chemical;

        int ingotTransformation = compareStandaloneDeclaredIngotTransformation(
                isIngotPrefix(getOrePrefixForKey(target)),
                isDeclaredIngotTransformationCandidate(target, left),
                isDeclaredIngotTransformationCandidate(target, right));
        if (ingotTransformation != 0) return ingotTransformation;

        return compareStandaloneCanonicalSolidForm(isSolidMaterialTarget(target),
                getCandidateSolidMaterialInputFormCost(target, left),
                getCandidateSolidMaterialInputFormCost(target, right));
    }

    /** A material's declared furnace or polarizer transition is its canonical ingot source. */
    static int compareStandaloneDeclaredIngotTransformation(boolean ingotTarget,
                                                             boolean leftDeclaredTransformation,
                                                             boolean rightDeclaredTransformation) {
        if (!ingotTarget) return 0;
        return Boolean.compare(rightDeclaredTransformation, leftDeclaredTransformation);
    }

    private static int compareStandaloneCanonicalSolidForm(boolean solidMaterialTarget, int leftMaterialFormCost,
                                                            int rightMaterialFormCost) {
        return solidMaterialTarget ? Integer.compare(leftMaterialFormCost, rightMaterialFormCost) : 0;
    }

    private static int getCandidateSolidMaterialInputFormCost(AEKey target, PatternCandidate candidate) {
        return getSolidMaterialInputFormCost(target, DynamicRecipePatternDetails.createScoringInputs(
                candidate.encoded.inputs, candidate.encoded.alternatives));
    }

    private static boolean isDirectChemicalPolymerSynthesis(AEKey target, PatternCandidate candidate) {
        if (!isChemicalProductFluidTarget(target) || !isChemicalSynthesisRecipeMap(candidate.recipeMap)) {
            return false;
        }

        Material targetMaterial = getMaterialForKey(target);
        boolean hasNonTargetMaterialInput = false;
        for (GenericStack input : candidate.encoded.inputs) {
            Material inputMaterial = getMaterialForKey(input.what());
            if (inputMaterial == null) continue;
            if (targetMaterial.equals(inputMaterial)) return false;
            hasNonTargetMaterialInput = true;
        }
        return hasNonTargetMaterialInput;
    }

    private static boolean isDeclaredIngotTransformationCandidate(AEKey target, PatternCandidate candidate) {
        return findDeclaredIngotTransformationInput(target, candidate) != null;
    }

    @Nullable
    private static Material findDeclaredIngotTransformationInput(AEKey target, PatternCandidate candidate) {
        if (!isIngotPrefix(getOrePrefixForKey(target))) return null;
        Material targetMaterial = getMaterialForKey(target);
        if (targetMaterial == null) return null;

        for (IPatternDetails.IInput input : DynamicRecipePatternDetails.createScoringInputs(
                candidate.encoded.inputs, candidate.encoded.alternatives)) {
            for (GenericStack option : input.possibleInputs()) {
                if (option == null || option.amount() <= 0) continue;
                AEKey inputKey = option.what();
                if (!isIngotPrefix(getOrePrefixForKey(inputKey))) continue;
                Material inputMaterial = getMaterialForKey(inputKey);
                if (inputMaterial == null || targetMaterial.equals(inputMaterial)) continue;
                IngotProperty property = inputMaterial.getProperty(PropertyKey.INGOT);
                if (property != null && (targetMaterial.equals(property.getSmeltingInto()) ||
                        targetMaterial.equals(property.getArcSmeltInto()) ||
                        targetMaterial.equals(property.getMagneticMaterial()))) {
                    return inputMaterial;
                }
            }
        }
        return null;
    }

    private static boolean isChemicalSynthesisRecipeMap(RecipeMap<?> recipeMap) {
        if (recipeMap == null) return false;
        String recipeMapName = recipeMap.getUnlocalizedName();
        return "polymerization_tank".equals(recipeMapName) ||
                (recipeMapName != null && recipeMapName.contains("chemical"));
    }

    /**
     * A chemical-bath route for a processed polymer solid must not lose only because its current fair probe stopped
     * at a temporary frontier. The next fair grant may reveal its synthesis chain, whereas a completed powder-form
     * conversion has already exhausted its route information.
     */
    static int compareStandaloneIncompleteChemicalBath(boolean chemicalProductSolid,
                                                       boolean leftChemicalBath, boolean leftQuotaLimited,
                                                       boolean leftBounded, boolean rightChemicalBath,
                                                       boolean rightQuotaLimited, boolean rightBounded) {
        if (!chemicalProductSolid) return 0;
        boolean leftDeferredBath = leftChemicalBath && leftQuotaLimited && leftBounded;
        boolean rightDeferredBath = rightChemicalBath && rightQuotaLimited && rightBounded;
        return Boolean.compare(rightDeferredBath, leftDeferredBath);
    }

    static boolean shouldContinueFairRouteRefinement(boolean quotaLimited, int identicalReplayCount) {
        return quotaLimited && identicalReplayCount < MAX_IDENTICAL_ROUTE_REFINEMENT_REPLAYS;
    }

    /** A deadline changes route selection mode; only the explicit selected-tree node limit stops materialization. */
    static StandaloneTreeMaterializationStep selectStandaloneTreeMaterializationStep(int selectedTargets,
                                                                                       int routeExpansionLimit,
                                                                                       boolean scoringDeadlineReached) {
        if (selectedTargets >= routeExpansionLimit) return StandaloneTreeMaterializationStep.STOP;
        return scoringDeadlineReached ? StandaloneTreeMaterializationStep.FAST_CONTINUATION :
                StandaloneTreeMaterializationStep.REFINED;
    }

    static int directInputUnresolvedPenalty(long remaining, boolean hasNormalPattern, boolean rawMaterialLeaf) {
        return remaining > 0 && !hasNormalPattern && !rawMaterialLeaf ? 1 : 0;
    }

    static int compareRouteCompletenessAndMaterials(int leftBoundedFallbacks, long leftMissingMaterials,
                                                     int rightBoundedFallbacks, long rightMissingMaterials) {
        int bounded = Integer.compare(leftBoundedFallbacks, rightBoundedFallbacks);
        return bounded != 0 ? bounded : Long.compare(leftMissingMaterials, rightMissingMaterials);
    }

    /**
     * A raw-material leaf is an intentional external dependency. An unexpanded processed form is not: it means the
     * provider graph has no way to make that dependency. Keep those cases distinct before comparing material amount.
     */
    static int compareRouteCompletenessAndMaterials(int leftBoundedFallbacks, int leftUnresolvedIntermediates,
                                                     long leftMissingMaterials, int rightBoundedFallbacks,
                                                     int rightUnresolvedIntermediates, long rightMissingMaterials) {
        int bounded = Integer.compare(leftBoundedFallbacks, rightBoundedFallbacks);
        if (bounded != 0) return bounded;
        int unresolved = Integer.compare(leftUnresolvedIntermediates, rightUnresolvedIntermediates);
        return unresolved != 0 ? unresolved : Long.compare(leftMissingMaterials, rightMissingMaterials);
    }

    /**
     * Keeps incomplete routes behind complete ones, then evaluates cycle safety and the canonical solid-form source
     * hierarchy before comparing raw-material quantities. This prevents a recyclable container with an undercounted
     * material value from displacing a direct dust-to-ingot or ingot-to-processed-form route.
     */
    static int compareRouteCompletenessSafetyFormAndMaterials(int leftBoundedFallbacks,
                                                               int leftUnresolvedIntermediates, long leftCycleRisk,
                                                               long leftMaterialFormCost, long leftMissingMaterials,
                                                               int rightBoundedFallbacks,
                                                               int rightUnresolvedIntermediates, long rightCycleRisk,
                                                               long rightMaterialFormCost, long rightMissingMaterials) {
        int bounded = Integer.compare(leftBoundedFallbacks, rightBoundedFallbacks);
        if (bounded != 0) return bounded;
        int unresolved = Integer.compare(leftUnresolvedIntermediates, rightUnresolvedIntermediates);
        if (unresolved != 0) return unresolved;
        int cycle = Long.compare(leftCycleRisk, rightCycleRisk);
        if (cycle != 0) return cycle;
        int forms = Long.compare(leftMaterialFormCost, rightMaterialFormCost);
        return forms != 0 ? forms : Long.compare(leftMissingMaterials, rightMissingMaterials);
    }

    static <T> boolean sameDependencyOptions(List<? extends List<T>> left, List<? extends List<T>> right) {
        if (left.size() != right.size()) return false;
        for (int inputIndex = 0; inputIndex < left.size(); inputIndex++) {
            List<T> leftOptions = left.get(inputIndex);
            List<T> rightOptions = right.get(inputIndex);
            if (leftOptions == null || rightOptions == null || !leftOptions.equals(rightOptions)) return false;
        }
        return true;
    }

    /** Assigns the bounded scan slots in rounds so every non-empty RecipeMap is reached before repeats. */
    static List<Integer> fairRecipeScanBucketOrder(List<Integer> candidateCounts, int limit) {
        if (candidateCounts == null || candidateCounts.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        int[] remaining = new int[candidateCounts.size()];
        for (int index = 0; index < candidateCounts.size(); index++) {
            Integer count = candidateCounts.get(index);
            remaining[index] = count == null ? 0 : Math.max(0, count);
        }

        List<Integer> order = new ArrayList<>(limit);
        while (order.size() < limit) {
            boolean advanced = false;
            for (int index = 0; index < remaining.length; index++) {
                if (remaining[index] <= 0) continue;
                remaining[index]--;
                order.add(index);
                advanced = true;
                if (order.size() >= limit) break;
            }
            if (!advanced) break;
        }
        return Collections.unmodifiableList(order);
    }

    /** Selects route representatives before equivalent machine variants can consume every refinement slot. */
    static <T, S> List<T> selectDiverseCandidates(List<T> rankedCandidates, int limit,
                                                   Function<? super T, ? extends S> shapeExtractor) {
        int selectedLimit = Math.min(Math.max(0, limit), rankedCandidates.size());
        if (selectedLimit == 0) return Collections.emptyList();

        List<T> selected = new ArrayList<>(selectedLimit);
        List<S> selectedShapes = new ArrayList<>(selectedLimit);
        Set<T> included = Collections.newSetFromMap(new IdentityHashMap<>());
        for (T candidate : rankedCandidates) {
            S shape = shapeExtractor.apply(candidate);
            if (selectedShapes.contains(shape)) continue;
            selected.add(candidate);
            selectedShapes.add(shape);
            included.add(candidate);
            if (selected.size() >= selectedLimit) return selected;
        }
        for (T candidate : rankedCandidates) {
            if (!included.add(candidate)) continue;
            selected.add(candidate);
            if (selected.size() >= selectedLimit) break;
        }
        return selected;
    }

    /**
     * Gives every candidate a small first probe, then deepens the routes that actually reached that probe in fair
     * rounds. A completed route is not replayed, while still-limited routes receive equal reservations before any
     * one of them can borrow the rest of the calculation.
     */
    private static <T> void scoreRefinedRoutes(List<T> refined, Map<T, RouteCost> refinedCosts,
                                               Map<T, RouteScoringProgress> refinedProgress,
                                               RouteCostEstimator estimator,
                                               java.util.function.BiFunction<? super T, Integer, RouteCost> scorer) {
        if (refined.isEmpty()) return;

        boolean standalone = isStandalonePatternGeneration();
        int initialQuota = routeInitialExpansionQuota(standalone ?
                        estimator.getFairExpansionAllowance(refined.size()) : estimator.getRemainingExpansions(),
                getPlanningBudget().getMaxRouteExpansionsPerTarget(), refined.size());
        for (T candidate : refined) {
            int available = standalone ? estimator.getFairExpansionAllowance(refined.size()) :
                    estimator.getRemainingExpansions();
            int quota = Math.max(1, Math.min(initialQuota, available));
            RouteCost cost = scorer.apply(candidate, quota);
            refinedCosts.put(candidate, cost);
            refinedProgress.put(candidate, estimator.getLastRootScoringProgress().withAdaptiveHistory(null, cost));
        }

        while (!estimator.isBudgetExhausted() && estimator.hasRemainingTime()) {
            List<T> limited = new ArrayList<>();
            for (T candidate : refined) {
                RouteScoringProgress previous = refinedProgress.get(candidate);
                if (previous != null && previous.shouldReceiveAnotherFairGrant()) {
                    limited.add(candidate);
                }
            }
            if (limited.isEmpty()) return;

            // Replaying a route is not resumable because each branch carries an inventory ledger. Reserve the same
            // absolute quota for every incomplete candidate so list order cannot decide which route gets deeper.
            // Standalone generation has no expansion-count ceiling: its fair allowance is derived from the remaining
            // shared deadline. This lets a genuinely deep selected route keep growing without allowing an unchanged
            // recursive frontier to replay until the entire eight-second task window is gone.
            int fairQuota = standalone ? estimator.getFairExpansionAllowance(limited.size()) :
                    estimator.getRemainingExpansions() / limited.size();
            if (fairQuota <= 0) return;
            boolean refinedAnyLimitedRoute = false;
            for (T candidate : limited) {
                RouteScoringProgress previous = refinedProgress.get(candidate);
                int nextQuota = Math.min(routeNextExpansionQuota(fairQuota,
                        previous.getQuota()), fairQuota);
                if (nextQuota <= previous.getQuota()) continue;

                refinedAnyLimitedRoute = true;
                RouteCost cost = scorer.apply(candidate, nextQuota);
                refinedCosts.put(candidate, cost);
                RouteScoringProgress progress = estimator.getLastRootScoringProgress()
                        .withAdaptiveHistory(previous, cost);
                refinedProgress.put(candidate, progress);
                if (progress.becameStalled()) estimator.recordStalledRefinement();
                if (estimator.isBudgetExhausted()) return;
            }
            if (!refinedAnyLimitedRoute) return;
        }
    }

    private static List<List<GenericStack>> dependencyOptions(IPatternDetails pattern) {
        return dependencyOptions(pattern.getInputs());
    }

    private static List<List<GenericStack>> dependencyOptions(IPatternDetails.IInput[] inputs) {
        List<List<GenericStack>> result = new ArrayList<>(inputs.length);
        for (IPatternDetails.IInput input : inputs) {
            GenericStack[] possibleInputs = input.possibleInputs();
            List<GenericStack> options = new ArrayList<>(possibleInputs.length);
            for (GenericStack option : possibleInputs) {
                if (option == null || option.amount() <= 0) continue;
                options.add(new GenericStack(option.what(), input.getMultiplier()));
            }
            result.add(options);
        }
        return result;
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
        if (isOptimalRebuildCalculation() || Boolean.TRUE.equals(OPTIMAL_ROUTE_GENERATION.get())) {
            return PlanningMode.RESOURCE_FIRST;
        }
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
        boolean usesPriorityDust = false;
        boolean usesPriorityFluid = false;
        boolean usesElementalFluid = false;
        boolean usesIngot = false;
        boolean usesPriorityIngot = false;
        boolean usesPolymerDust = false;
        boolean hasMaterialInput = false;
        boolean hasTargetMaterialInput = false;
        boolean hasNonTargetMaterialInput = false;
        boolean onlyTargetMaterialInputs = targetMaterial != null;
        boolean onlyElementalOrBasicFluidInputs = true;
        Set<String> inputMaterials = new HashSet<>();
        Set<String> elementalInputMaterials = new HashSet<>();
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
                    boolean dustInput = isDustPrefix(prefixName);
                    usesDust |= dustInput;
                    usesPolymerDust |= dustInput && entry.material != null &&
                            entry.material.hasProperty(PropertyKey.POLYMER);
                    boolean ingotInput = isIngotPrefix(prefixName);
                    usesIngot |= ingotInput;
                    boolean targetMaterialInput = targetMaterial != null && targetMaterial.equals(inputMaterial);
                    usesPriorityDust |= isPrioritySolidMaterialInput(dustInput, targetMaterialInput);
                    usesPriorityIngot |= isPrioritySolidMaterialInput(ingotInput, targetMaterialInput);
                }
            }

            if (inputMaterial != null) {
                inputMaterials.add(inputMaterial.getName());
                hasMaterialInput = true;
                if (isElementalMaterial(inputMaterial)) {
                    elementalInputMaterials.add(inputMaterial.getName());
                } else {
                    if (!isBasicFluidLeaf(inputKey)) {
                        onlyElementalOrBasicFluidInputs = false;
                    }
                }
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
        boolean recyclingCategory = isRecyclingRecipeCategory(recipe);
        boolean recyclingDataInput = hasRecyclingDataInput(recipe);
        boolean recycling = isRecyclingRecipe(recyclingCategory, isMaterialRecoveryRecipeMap(recipeMap),
                targetMaterial != null, recyclingDataInput);
        facts.put("recycling", recycling);
        facts.put("recyclingCategory", recyclingCategory);
        facts.put("recyclingDataInput", recyclingDataInput);
        facts.put("targetIsFluid", target instanceof AEFluidKey);
        facts.put("targetIsPolymer", targetMaterial != null && targetMaterial.hasProperty(PropertyKey.POLYMER));
        facts.put("primaryCompoundSynthesis", isPrimaryCompoundSynthesis(target instanceof AEFluidKey,
                isElementalMaterial(targetMaterial), hasTargetMaterialInput, hasMaterialInput,
                onlyElementalOrBasicFluidInputs, elementalInputMaterials.size()));
        facts.put("targetMaterial", targetMaterial == null ? "" : targetMaterial.getName());
        facts.put("targetOrePrefix", getOrePrefixForKey(target));
        facts.put("inputMaterials", Collections.unmodifiableSet(inputMaterials));
        facts.put("inputOrePrefixes", Collections.unmodifiableSet(inputOrePrefixes));
        facts.put("deterministicOutputMaterials", Collections.unmodifiableSet(deterministicOutputMaterials));
        facts.put("chancedOutputMaterials", Collections.unmodifiableSet(chancedOutputMaterials));
        facts.put("groovyRecipe", recipe.isGroovyRecipe());
        facts.put("usesDustInput", usesDust);
        facts.put("usesPriorityDustInput", usesPriorityDust);
        facts.put("usesPolymerDustInput", usesPolymerDust);
        facts.put("usesPriorityFluidInput", usesPriorityFluid);
        facts.put("usesIngotInput", usesIngot);
        facts.put("usesPriorityIngotInput", usesPriorityIngot);
        facts.put("hasMaterialInput", hasMaterialInput);
        facts.put("hasTargetMaterialInput", hasTargetMaterialInput);
        facts.put("hasNonTargetMaterialInput", hasNonTargetMaterialInput);
        facts.put("onlyTargetMaterialInputs", onlyTargetMaterialInputs);
        // These aliases retain neutral material facts for pack rules without affecting Java-side candidate ordering.
        facts.put("dustOrFluidInput", usesDust || usesPriorityFluid);
        facts.put("ingotInput", usesIngot);
        facts.put("priorityDustOrFluidInput", usesPriorityDust || usesPriorityFluid);
        facts.put("priorityIngotInput", usesPriorityIngot);
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

    /** A basic leaf fluid such as water may supply atoms without turning a direct elemental synthesis into a detour. */
    static boolean isPrimaryCompoundSynthesis(boolean targetIsFluid, boolean targetIsElement,
                                               boolean hasTargetMaterialInput, boolean hasMaterialInput,
                                               boolean onlyElementalOrBasicFluidInputs, int distinctElements) {
        return targetIsFluid && !targetIsElement && !hasTargetMaterialInput && hasMaterialInput &&
                onlyElementalOrBasicFluidInputs && distinctElements >= 1;
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

    /**
     * GregTech's generated recovery recipes use a dedicated category, but some pack recipes deliberately reuse a
     * normal extractor category. A registered recycling input in a material-recovery map is the same semantic route.
     */
    static boolean isRecyclingRecipe(boolean recyclingCategory, boolean materialRecoveryMap,
                                     boolean materialTarget, boolean recyclingDataInput) {
        return recyclingCategory || materialRecoveryMap && materialTarget && recyclingDataInput;
    }

    private static boolean isRecyclingRecipeCategory(Recipe recipe) {
        return recipe != null && recipe.getRecipeCategory() != null &&
                isRecyclingRecipeCategoryName(recipe.getRecipeCategory().getName());
    }

    private static boolean isMaterialRecoveryRecipeMap(RecipeMap<?> recipeMap) {
        if (recipeMap == null) return false;
        String recipeMapId = recipeMap.getUnlocalizedName();
        return "arc_furnace".equals(recipeMapId) || "extractor".equals(recipeMapId) ||
                "macerator".equals(recipeMapId);
    }

    private static boolean hasRecyclingDataInput(Recipe recipe) {
        if (recipe == null) return false;
        for (GTRecipeInput input : recipe.getInputs()) {
            if (input == null || input.isNonConsumable() || input instanceof IntCircuitIngredient) continue;
            ItemStack[] choices = input.getInputStacks();
            if (choices == null) continue;
            for (ItemStack choice : choices) {
                if (choice != null && !choice.isEmpty() &&
                        GregTechAPI.RECYCLING_MANAGER.getRecyclingData(choice) != null) {
                    return true;
                }
            }
        }
        return false;
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

    /** Only the base ingot and its hot form are direct dust-smelting outputs. */
    static boolean isDirectIngotOrHotIngotPrefix(String prefixName) {
        return "ingot".equals(prefixName) || "ingotHot".equals(prefixName);
    }

    /**
     * An elemental same-material dust input to a direct ingot transition is a terminal material seed. Compound
     * powders must stay expandable because they can have an upstream alloying or mixing recipe.
     */
    static boolean isCanonicalSameMaterialDustToIngotTransition(String outputPrefix, boolean materialIsElement,
                                                                  boolean hasSameMaterialDustInput,
                                                                  boolean hasOtherSameMaterialInput) {
        return materialIsElement && isDirectIngotOrHotIngotPrefix(outputPrefix) && hasSameMaterialDustInput &&
                !hasOtherSameMaterialInput;
    }

    private static boolean isCanonicalSameMaterialDustToIngotTransition(AEKey output, RouteEdge edge) {
        if (output == null || edge == null) return false;
        Material outputMaterial = getMaterialForKey(output);
        if (outputMaterial == null) return false;

        boolean hasSameMaterialDust = false;
        boolean hasOtherSameMaterialInput = false;
        for (IPatternDetails.IInput input : edge.inputs) {
            for (GenericStack option : input.possibleInputs()) {
                if (option == null || option.amount() <= 0) continue;
                AEKey inputKey = option.what();
                if (!outputMaterial.equals(getMaterialForKey(inputKey))) continue;
                if (isDustPrefix(getOrePrefixForKey(inputKey))) {
                    hasSameMaterialDust = true;
                } else {
                    hasOtherSameMaterialInput = true;
                }
            }
        }
        return isCanonicalSameMaterialDustToIngotTransition(getOrePrefixForKey(output),
                isElementalMaterial(outputMaterial), hasSameMaterialDust, hasOtherSameMaterialInput);
    }

    private static boolean isCanonicalSameMaterialDustInput(AEKey output, AEKey input) {
        if (output == null || input == null || !isDustPrefix(getOrePrefixForKey(input))) return false;
        Material outputMaterial = getMaterialForKey(output);
        return outputMaterial != null && isElementalMaterial(outputMaterial) &&
                outputMaterial.equals(getMaterialForKey(input));
    }

    /** Same-material powder and ingot inputs are form conversions, not upstream material sources. */
    static boolean isPrioritySolidMaterialInput(boolean solidMaterialInput, boolean targetMaterialInput) {
        return solidMaterialInput && !targetMaterialInput;
    }

    static boolean isProcessedSolidMaterialForm(boolean itemKey, boolean hasMaterial, String prefixName) {
        return itemKey && hasMaterial && prefixName != null && !isOreInputPrefix(prefixName) &&
                !isDustPrefix(prefixName) && !isIngotPrefix(prefixName);
    }

    private static final int INDIRECT_SOLID_MATERIAL_SOURCE_COST = 3;
    /**
     * Polymer materials automatically receive several equivalent-looking form recipes from GregTech. Keep direct
     * chemical synthesis ahead of all of them, but retain a stable downstream chain once a solid form is needed:
     * polymer fluid, then ingot, then dust. Without that ordering, recipe registration order can select a dust-fed
     * extruder even though the selected chemical route only produces the polymer fluid.
     */
    private static final int CHEMICAL_PRODUCT_SOLID_FLUID_FORM_COST =
            INDIRECT_SOLID_MATERIAL_SOURCE_COST + 1;
    private static final int CHEMICAL_PRODUCT_SOLID_INGOT_FORM_COST =
            CHEMICAL_PRODUCT_SOLID_FLUID_FORM_COST + 1;
    private static final int CHEMICAL_PRODUCT_SOLID_DUST_FORM_COST =
            CHEMICAL_PRODUCT_SOLID_INGOT_FORM_COST + 1;
    private static final int CHEMICAL_PRODUCT_SOLID_OTHER_FORM_COST =
            CHEMICAL_PRODUCT_SOLID_DUST_FORM_COST + 1;

    /**
     * Assigns a generic source-form cost for solid material outputs. Powder is the canonical source for ingots;
     * ingots are the canonical source for processed metal shapes such as plates and foils. Polymer material-form
     * conversions stay behind a direct chemical-production recipe, but have an explicit fluid-to-ingot-to-dust order
     * so they can continue that chemical route rather than inventing a powder demand. Molten material otherwise adds
     * a form transition.
     */
    static int solidMaterialInputFormCost(boolean solidTarget, boolean ingotTarget,
                                          boolean chemicallySynthesizedTarget, boolean targetMaterialInput,
                                          boolean fluidInput, String prefixName) {
        if (!solidTarget) return 0;
        if (!targetMaterialInput) return INDIRECT_SOLID_MATERIAL_SOURCE_COST;
        if (chemicallySynthesizedTarget) {
            if (fluidInput) return CHEMICAL_PRODUCT_SOLID_FLUID_FORM_COST;
            if (isIngotPrefix(prefixName)) return CHEMICAL_PRODUCT_SOLID_INGOT_FORM_COST;
            if (isDustPrefix(prefixName)) return CHEMICAL_PRODUCT_SOLID_DUST_FORM_COST;
            return CHEMICAL_PRODUCT_SOLID_OTHER_FORM_COST;
        }
        if (ingotTarget) {
            if (isDustPrefix(prefixName)) return 0;
            if (fluidInput || isIngotPrefix(prefixName)) return 1;
            return 2;
        }
        if (isIngotPrefix(prefixName)) {
            return 0;
        }
        if (fluidInput) {
            return 1;
        }
        if (isDustPrefix(prefixName)) {
            return 1;
        }
        return 2;
    }

    private static boolean isSolidMaterialTarget(AEKey target) {
        if (!(target instanceof AEItemKey) || getMaterialForKey(target) == null) return false;
        String prefixName = getOrePrefixForKey(target);
        return isIngotPrefix(prefixName) || isProcessedSolidMaterialForm(true, true, prefixName);
    }

    private static boolean isChemicalProductSolidTarget(AEKey target) {
        if (!isSolidMaterialTarget(target)) return false;
        Material material = getMaterialForKey(target);
        return material != null && material.hasProperty(PropertyKey.POLYMER);
    }

    private static boolean isChemicalProductProcessedSolidTarget(AEKey target) {
        return isChemicalProductSolidTarget(target) &&
                isProcessedSolidMaterialForm(true, true, getOrePrefixForKey(target));
    }

    private static boolean isChemicalProductFluidTarget(AEKey target) {
        if (!(target instanceof AEFluidKey)) return false;
        Material material = getMaterialForKey(target);
        return material != null && material.hasProperty(PropertyKey.POLYMER);
    }

    private static int getSolidMaterialInputFormCost(AEKey output, IPatternDetails.IInput[] inputs) {
        if (!isSolidMaterialTarget(output) || inputs == null) return 0;
        Material outputMaterial = getMaterialForKey(output);
        boolean ingotTarget = isIngotPrefix(getOrePrefixForKey(output));
        boolean chemicallySynthesizedTarget = outputMaterial.hasProperty(PropertyKey.POLYMER);
        int lowestCost = Integer.MAX_VALUE;
        boolean hasTargetMaterialInput = false;
        for (IPatternDetails.IInput input : inputs) {
            for (GenericStack option : input.possibleInputs()) {
                if (option == null || option.amount() <= 0) continue;
                AEKey inputKey = option.what();
                if (!outputMaterial.equals(getMaterialForKey(inputKey))) continue;
                hasTargetMaterialInput = true;
                int cost = solidMaterialInputFormCost(true, ingotTarget, chemicallySynthesizedTarget, true,
                        inputKey instanceof AEFluidKey, getOrePrefixForKey(inputKey));
                lowestCost = Math.min(lowestCost, cost);
            }
        }
        return hasTargetMaterialInput ? lowestCost : INDIRECT_SOLID_MATERIAL_SOURCE_COST;
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

    private static boolean isMaterialFormChangePattern(AEKey target, IPatternDetails pattern) {
        Material targetMaterial = getMaterialForKey(target);
        if (targetMaterial == null || pattern == null) return false;

        boolean hasTargetMaterialInput = false;
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            for (GenericStack option : input.possibleInputs()) {
                Material inputMaterial = getMaterialForKey(option.what());
                if (inputMaterial == null) continue;
                if (!targetMaterial.equals(inputMaterial)) return false;
                hasTargetMaterialInput = true;
            }
        }
        return hasTargetMaterialInput;
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

    static boolean isElementalFluidLeaf(boolean fluidKey, boolean materialIsElement,
                                        boolean hasSolidMaterialForm, boolean moltenMaterialFluid) {
        return fluidKey && materialIsElement && !hasSolidMaterialForm && !moltenMaterialFluid;
    }

    static boolean isBasicFluidLeaf(boolean fluidKey, String fluidName) {
        return fluidKey && "water".equals(fluidName);
    }

    /** Pack-level raw inputs that deliberately terminate RecipeMap dependency expansion. */
    static boolean isConfiguredFluidLeaf(boolean fluidKey, String fluidName) {
        return fluidKey && "benzene".equals(fluidName);
    }

    static boolean isBasicFluidLeaf(AEKey key) {
        return key instanceof AEFluidKey fluidKey &&
                isBasicFluidLeaf(true, fluidKey.getFluid().getName());
    }

    private static boolean isConfiguredFluidLeaf(AEKey key) {
        return key instanceof AEFluidKey fluidKey &&
                isConfiguredFluidLeaf(true, fluidKey.getFluid().getName());
    }

    private static boolean isElementalFluidLeaf(AEKey key) {
        if (!(key instanceof AEFluidKey fluidKey)) return false;
        Material material = getMaterialForKey(key);
        boolean hasSolidMaterialForm = material != null &&
                (material.hasProperty(PropertyKey.DUST) || material.hasProperty(PropertyKey.INGOT));
        boolean moltenMaterialFluid = material != null && material.hasFluid() &&
                material.getFluid(FluidStorageKeys.MOLTEN) == fluidKey.getFluid();
        return isElementalFluidLeaf(true, isElementalMaterial(material), hasSolidMaterialForm, moltenMaterialFluid);
    }

    static boolean isRawMaterialLeaf(boolean externalOreInput, boolean elementalDust, boolean elementalFluid,
                                     boolean basicFluid) {
        return externalOreInput || elementalDust || elementalFluid || basicFluid;
    }

    static <T, K> List<T> appendMissingByKey(List<T> existing, List<T> generated,
                                              Function<? super T, ? extends K> keyExtractor) {
        if (generated == null || generated.isEmpty()) {
            return existing == null ? Collections.emptyList() : existing;
        }
        if (existing == null || existing.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(generated));
        }

        Set<K> existingKeys = new HashSet<>(existing.size() + generated.size());
        for (T value : existing) existingKeys.add(keyExtractor.apply(value));
        List<T> merged = null;
        for (T value : generated) {
            if (!existingKeys.add(keyExtractor.apply(value))) continue;
            if (merged == null) merged = new ArrayList<>(existing);
            merged.add(value);
        }
        return merged == null ? existing : Collections.unmodifiableList(merged);
    }

    private static boolean isRawMaterialLeaf(AEKey key) {
        return key != null && (isExternalOreInput(key) || isElementalDust(key) || isElementalFluidLeaf(key) ||
                isBasicFluidLeaf(key) || isConfiguredFluidLeaf(key));
    }

    static boolean isRouteDependencyLeaf(boolean rawMaterialLeaf, boolean nonConsumableControlToken) {
        return rawMaterialLeaf || nonConsumableControlToken;
    }

    private static boolean isRouteDependencyLeaf(AEKey key) {
        return isRouteDependencyLeaf(isRawMaterialLeaf(key), isNonConsumableControlToken(key));
    }

    /** Programmable wrappers represent machine configuration, not material that the dependency tree must synthesize. */
    private static boolean isNonConsumableControlToken(AEKey key) {
        if (!(key instanceof AEItemKey itemKey)) return false;
        ItemStack stack = itemKey.toStack(1);
        return ProgrammableCircuit.getInstanceFor(stack) != null && ProgrammableCircuit.hasWrappedItem(stack);
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

        private ProviderSnapshot(ProviderSnapshot source, IGrid grid) {
            this.grid = grid;
            this.providerId = source.providerId;
            this.epoch = source.epoch;
            this.recipeMaps = Arrays.copyOf(source.recipeMaps, source.recipeMaps.length);
            this.machineProfile = source.machineProfile;
            this.ruleSetVersion = source.ruleSetVersion;
            this.planningMode = source.planningMode;
            this.pinnedRouteGroup = source.pinnedRouteGroup;
            this.position = source.position;
            this.dimension = source.dimension;
            this.provider = source.provider;
        }

        private ProviderSnapshot relocatedTo(IGrid grid) {
            return this.grid == grid ? this : new ProviderSnapshot(this, grid);
        }

        private boolean sameDefinition(ProviderSnapshot other) {
            return other != null && epoch == other.epoch && Arrays.equals(recipeMaps, other.recipeMaps) &&
                    machineProfile.getVersion().equals(other.machineProfile.getVersion()) &&
                    ruleSetVersion.equals(other.ruleSetVersion) && planningMode == other.planningMode &&
                    pinnedRouteGroup.equals(other.pinnedRouteGroup) && provider == other.provider;
        }
    }

    /** Low-frequency recovery result for one provider's saved dynamic pattern cache. */
    public static final class PersistedPatternPublicationStatus {

        private final int cachedPatternCount;
        private final int registeredPatternCount;
        private final Map<String, Integer> rejectedCounts;

        private PersistedPatternPublicationStatus(int cachedPatternCount, int registeredPatternCount,
                                                  Map<String, Integer> rejectedCounts) {
            this.cachedPatternCount = Math.max(0, cachedPatternCount);
            this.registeredPatternCount = Math.max(0, registeredPatternCount);
            this.rejectedCounts = Collections.unmodifiableMap(new LinkedHashMap<>(rejectedCounts));
        }

        private static PersistedPatternPublicationStatus empty() {
            return new PersistedPatternPublicationStatus(0, 0, Collections.emptyMap());
        }

        private static PersistedPatternPublicationStatus awaitingSnapshot(int cachedPatternCount) {
            Map<String, Integer> rejected = new LinkedHashMap<>();
            if (cachedPatternCount > 0) {
                rejected.put("PROVIDER_SNAPSHOT_UNAVAILABLE", cachedPatternCount);
            }
            return new PersistedPatternPublicationStatus(cachedPatternCount, 0, rejected);
        }

        public int getCachedPatternCount() {
            return cachedPatternCount;
        }

        public int getRegisteredPatternCount() {
            return registeredPatternCount;
        }

        /** True once every saved detail has a current registry owner; AE2 acknowledgement is checked separately. */
        public boolean hasRegisteredAllCachedPatterns() {
            return registeredPatternCount >= cachedPatternCount;
        }

        /** Only registry transfer/binding races merit a bounded timer retry; all other cases wait for lifecycle work. */
        public boolean shouldRetryWithoutLifecycleChange() {
            return rejectedCounts.containsKey("PROVIDER_SNAPSHOT_UNAVAILABLE") ||
                    rejectedCounts.containsKey("REGISTRY_BINDING_PENDING");
        }

        public String summarize() {
            return "registered=" + registeredPatternCount + '/' + cachedPatternCount +
                    (rejectedCounts.isEmpty() ? "" : " rejected=" + rejectedCounts);
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
        invalidatePreparedRecipeMapContents(recipeMap, "changed");
    }

    /**
     * Invalidates generated routes after a main-thread adapter has replaced a lookup-only recipe snapshot.
     * The resolver already owns the replacement snapshot, so this path must not discard it before async indexing.
     */
    public static void invalidatePreparedRecipeMapContents(RecipeMap<?> recipeMap) {
        if (recipeMap == null) return;
        invalidatePreparedRecipeMapContents(recipeMap, "runtime fallback snapshot changed");
    }

    /** Marks Vanilla furnace fallback bindings stale until an active provider captures their replacement snapshot. */
    public static void invalidateRuntimeFurnaceRecipeContents() {
        for (RecipeMap<?> recipeMap : RecipeBindingResolver.invalidateRuntimeFurnaceFallbacks()) {
            invalidatePreparedRecipeMapContents(recipeMap, "Vanilla furnace recipes changed");
        }
    }

    private static void invalidatePreparedRecipeMapContents(RecipeMap<?> recipeMap, String change) {
        int discardedCachedPatterns = 0;
        for (GridState state : GRIDS.values()) {
            discardedCachedPatterns += state.invalidateRecipeMapContents(recipeMap);
        }
        if (discardedCachedPatterns > 0) {
            ApplyGrayMod.LOGGER.info("Invalidated dynamic RecipeMap pattern indexes after {} {}; discarded {} " +
                            "stale provider cache entries",
                    recipeMap.getUnlocalizedName(), change, discardedCachedPatterns);
        }
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
        /** Exact recipe keys selected by the last standalone tree, grouped by their produced output. */
        private final Map<AEKey, Set<String>> standaloneRecipeKeysByTarget = new ConcurrentHashMap<>();
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
        /** Native details already mounted by AE2 while a provider is being transferred between grids. */
        private final Set<String> deferredNativePublicationProviderIds = ConcurrentHashMap.newKeySet();
        /**
         * A grid merge moves providers one node at a time. The provider set must become visible immediately, but
         * rebuilding every persisted-pattern index for every moved node makes a large cable connection quadratic.
         * This gate coalesces that work until a caller actually needs the published pattern cache.
         */
        private final ProviderCacheRebuildGate providerCacheRebuildGate = new ProviderCacheRebuildGate();
        /** Prevents an index built before an explicit rebuild from being written back after it was cleared. */
        private volatile long recipeOutputIndexEpoch;
        /** Zero means no explicit full index rebuild is pending. Guarded by {@code this}. */
        private long pendingFullRecipeOutputIndexEpoch;
        /** Guards one eager index rebuild at a time. Guarded by {@code this}. */
        private boolean fullRecipeOutputIndexRebuildInProgress;
        /** The root request allowed to consume {@link #pendingFullRecipeOutputIndexEpoch}. Guarded by {@code this}. */
        private OptimalRebuildRequest pendingOptimalRebuild;
        /** Guarded by {@code this}; prevents a topology burst from producing one warning per planning request. */
        private long lastSlowProviderCacheRebuildLogNanos;

        private synchronized void putProvider(ProviderSnapshot snapshot) {
            putProvider(snapshot, false);
        }

        /** Keeps native publication cheap after GridNode has already mounted the provider from its old registry. */
        private synchronized void putMigratedProvider(ProviderSnapshot snapshot) {
            putProvider(snapshot, true);
        }

        private void putProvider(ProviderSnapshot snapshot, boolean deferNativePublication) {
            ProviderSnapshot existing = providers.put(snapshot.providerId, snapshot);
            boolean definitionChanged = !snapshot.sameDefinition(existing);
            if (definitionChanged) {
                invalidateGeneratedForProviderChange();
            }
            if (deferNativePublication) {
                deferredNativePublicationProviderIds.add(snapshot.providerId);
            } else {
                deferredNativePublicationProviderIds.remove(snapshot.providerId);
            }
            if (!providerCacheRebuildGate.isPending()) {
                bindCachedPatterns(snapshot);
            } else {
                retainCachedPatternOwnership(snapshot);
            }
        }

        @Nullable
        private synchronized ProviderSnapshot getProviderSnapshot(String providerId) {
            return providers.get(providerId);
        }

        private void invalidateGeneratedForProviderChange() {
            if (providerCacheRebuildGate.invalidate()) {
                clearGenerated();
            }
        }

        private synchronized void ensureProviderCacheBindings() {
            if (providerCacheRebuildGate.beginRebuild()) {
                long startedAt = System.nanoTime();
                bindAllCachedPatterns();
                deferredNativePublicationProviderIds.clear();
                logSlowProviderCacheRebuild(startedAt);
            }
        }

        private void logSlowProviderCacheRebuild(long startedAt) {
            long elapsedNanos = System.nanoTime() - startedAt;
            if (elapsedNanos < SLOW_PROVIDER_CACHE_REBUILD_NANOS) return;

            long now = System.nanoTime();
            if (lastSlowProviderCacheRebuildLogNanos != 0 &&
                    now - lastSlowProviderCacheRebuildLogNanos < SLOW_PROVIDER_CACHE_REBUILD_LOG_COOLDOWN_NANOS) {
                return;
            }
            lastSlowProviderCacheRebuildLogNanos = now;
            ApplyGrayMod.LOGGER.warn("Slow RecipeMap provider-cache rebuild providers={} elapsed={}ms; " +
                            "this work was deferred until a real dynamic pattern lookup after grid topology changed",
                    providers.size(), elapsedNanos / 1_000_000L);
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
                if (snapshot.provider.isFrozenStandalonePattern(detail.getRecipeKey())) {
                    bindStandalonePatternOutputs(detail);
                }
            }
        }

        private PersistedPatternPublicationStatus inspectPersistedPatternPublication(
                MetaTileEntityMERecipeMapPatternProvider provider, String providerId, int cachedPatternCount) {
            ProviderSnapshot snapshot = providers.get(providerId);
            if (snapshot == null || snapshot.provider != provider) {
                return PersistedPatternPublicationStatus.awaitingSnapshot(cachedPatternCount);
            }

            // The first native mount can happen while this grid is still transferring providers. Resolve that one
            // coalesced cache rebuild here rather than asking AE2 to remount repeatedly just to discover a rejection.
            ensureProviderCacheBindings();

            List<DynamicRecipePatternDetails> details = provider.getCachedDynamicPatterns();
            details.sort((left, right) -> left.getRecipeKey().compareTo(right.getRecipeKey()));
            Map<String, Integer> rejected = new LinkedHashMap<>();
            int registered = 0;
            for (DynamicRecipePatternDetails detail : details) {
                if (patternsByRecipe.get(detail.getRecipeKey()) == detail &&
                        providersByPattern.get(detail) == provider) {
                    registered++;
                    continue;
                }
                String reason = getPersistedPatternPublicationRejection(snapshot, detail);
                rejected.merge(reason, 1, Integer::sum);
            }
            return new PersistedPatternPublicationStatus(details.size(), registered, rejected);
        }

        /** Restores completed standalone graphs without exposing unrelated lazy cache entries as fresh routes. */
        private void bindFrozenStandalonePatterns() {
            for (ProviderSnapshot snapshot : providers.values()) {
                for (DynamicRecipePatternDetails detail : snapshot.provider.getCachedDynamicPatterns()) {
                    if (!snapshot.provider.isFrozenStandalonePattern(detail.getRecipeKey()) ||
                            !isRecipeMapAvailable(snapshot, detail)) {
                        continue;
                    }
                    patternsByRecipe.put(detail.getRecipeKey(), detail);
                    providersByPattern.put(detail, snapshot.provider);
                    bindCachedPatternOutputs(detail);
                    bindStandalonePatternOutputs(detail);
                }
            }
        }

        /**
         * Keeps active plans resolvable while a grid move has deferred the expensive published-cache rebuild.
         * Ownership is intentionally much cheaper than rebuilding outputs: it does not inspect recipe maps or alter
         * which details are visible to new pattern lookups.
         */
        private void retainCachedPatternOwnership(ProviderSnapshot snapshot) {
            for (DynamicRecipePatternDetails detail : snapshot.provider.getCachedDynamicPatterns()) {
                providersByPattern.put(detail, snapshot.provider);
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
            if (getPersistedDetailValidationFailure(detail) != null) return false;
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
                deferredNativePublicationProviderIds.remove(providerId);
                invalidateGeneratedForProviderChange();
                removeProviderBindings(removed.provider);
            }
            return providers.isEmpty();
        }

        /**
         * Moves a provider to another AE2 grid without repeatedly scanning the old grid's full weak ownership map.
         * Existing plans retain the same provider object, and the target state immediately records its ownership.
         * Normal removal still uses {@link #removeProvider(String)} to release those entries eagerly.
         */
        private synchronized boolean removeProviderForGridTransfer(String providerId) {
            ProviderSnapshot removed = providers.remove(providerId);
            if (removed != null) {
                deferredNativePublicationProviderIds.remove(providerId);
                invalidateGeneratedForProviderChange();
            }
            return providers.isEmpty();
        }

        private List<IPatternDetails> findPatterns(AEKey target) {
            ensureProviderCacheBindings();
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session != null && session.matches(this)) {
                List<DynamicRecipePatternDetails> prepared = session.findTransientPatterns(target);
                if (prepared != null) {
                    return availableTransientPatterns(session, target, prepared);
                }
                // The standalone generator has already selected every reachable dynamic branch. Do not let UI-tree
                // traversal create a late, fast-ranked route after the generation phase has frozen its decision.
                if (session.isStandaloneSelectionFrozen()) {
                    return Collections.emptyList();
                }
                // Fuzzy substitutions can reveal a key that is not represented by any direct recipe input. Keep the
                // result task-local as well; this is a bounded extension of the prebuilt graph, never persistence.
                return availableTransientPatterns(session, target, createTransientPatterns(session, target));
            }
            // Ore inputs, standard water, and elemental dusts/fluids terminate the generated dependency graph.
            // Other compound fluids and processed material forms still expand, so HCl can resolve to hydrogen plus
            // chlorine without trying to manufacture either elemental fluid.
            if (isRawMaterialLeaf(target)) {
                OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
                if (optimalRebuild != null) {
                    if (isElementalDust(target)) {
                        optimalRebuild.elementalDustLeaves.add(target);
                    } else if (isElementalFluidLeaf(target)) {
                        optimalRebuild.elementalFluidLeaves.add(target);
                    }
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

                CraftingRecoverySession recovery = CRAFTING_RECOVERY_SESSION.get();
                boolean expandForRecovery = recovery != null && recovery.matches(this) &&
                        recovery.consumeExpansion(target);
                List<DynamicRecipePatternDetails> existing = patternsByTarget.get(target);
                if (existing == null || expandForRecovery) {
                    if (existing == null) {
                        PLANNING_METRICS.recordTargetCacheMiss();
                    } else {
                        PLANNING_METRICS.recordTargetCacheHit();
                    }
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
                        List<DynamicRecipePatternDetails> current = patternsByTarget.get(target);
                        if (expandForRecovery) {
                            List<DynamicRecipePatternDetails> merged = appendMissingByKey(current, generated,
                                    DynamicRecipePatternDetails::getRecipeKey);
                            int retained = current == null ? 0 : current.size();
                            int appended = merged.size() - retained;
                            existing = merged;
                            if (appended > 0) {
                                patternsByTarget.put(target, merged);
                            }
                            recovery.recordCacheExpansion(target, retained, appended);
                        } else if (current == null) {
                            existing = generated;
                            if (!hasSessionFilters(target)) patternsByTarget.put(target, generated);
                        } else {
                            existing = current;
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
                        CraftingRecoverySession exposureSession = CRAFTING_RECOVERY_SESSION.get();
                        if (exposureSession != null && exposureSession.matches(this)) {
                            exposureSession.recordExposed(target, detail);
                        }
                    }
                }
                return available;
            } finally {
                PLANNING_METRICS.recordPlanningDuration(System.nanoTime() - lookupStartedAt);
            }
        }

        /** Supplies bounded dynamic dependency edges without recursively invoking CraftingService. */
        private List<PatternCandidate> getCandidatesForRouteCost(AEKey target) {
            ensureProviderCacheBindings();
            if (target == null || isRawMaterialLeaf(target)) {
                return Collections.emptyList();
            }
            if (hasSessionFilters(target)) {
                return collectPatternCandidates(target, getPlanningBudget().getMaxDynamicCandidatesForCost(), true);
            }
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session != null && session.matches(this)) {
                List<PatternCandidate> sessionCandidates = session.findRouteCandidates(target);
                if (sessionCandidates != null) {
                    PLANNING_METRICS.recordRouteCandidateCacheHit();
                    return sessionCandidates;
                }
            }
            CachedCandidates cached = routeCandidateCache.get(target);
            if (cached != null && (System.nanoTime() - cached.cachedAtNanos) < ROUTE_CANDIDATE_CACHE_TTL_NANOS) {
                PLANNING_METRICS.recordRouteCandidateCacheHit();
                if (session != null && session.matches(this)) {
                    session.rememberRouteCandidates(target, cached.candidates);
                }
                return cached.candidates;
            }
            PLANNING_METRICS.recordRouteCandidateCacheMiss();
            List<PatternCandidate> candidates = collectPatternCandidates(target,
                    getPlanningBudget().getMaxDynamicCandidatesForCost(), false);
            if (candidates.size() > 1 && getActiveOptimalRebuild() != null && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                List<String> recipeMaps = new ArrayList<>(candidates.size());
                for (PatternCandidate candidate : candidates) {
                    recipeMaps.add(candidate.recipeMap.getUnlocalizedName());
                }
                ApplyGrayMod.LOGGER.debug("Retained {} generic RecipeMap dependency route candidates for {}: {}",
                        candidates.size(), target, recipeMaps);
            }
            routeCandidateCache.put(target, new CachedCandidates(candidates, System.nanoTime()));
            if (session != null && session.matches(this)) {
                session.rememberRouteCandidates(target, candidates);
            }
            return candidates;
        }

        private List<DynamicRecipePatternDetails> createPatterns(AEKey target) {
            List<PatternCandidate> candidates = collectPatternCandidates(target,
                    getPlanningBudget().getMaxCandidatesPerTarget(), true);
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

        /** Selects and creates one task-local detail after the relevant candidate set is available. */
        private List<DynamicRecipePatternDetails> createTransientPatterns(CraftingRecoverySession session,
                                                                            AEKey target) {
            return createTransientPatterns(session, target, false);
        }

        private List<DynamicRecipePatternDetails> createTransientPatterns(CraftingRecoverySession session,
                                                                            AEKey target, boolean refineRoute) {
            if (isRawMaterialLeaf(target)) {
                session.rememberTransientPatterns(target, Collections.emptyList());
                return Collections.emptyList();
            }
            List<PatternCandidate> source = session.findRouteCandidates(target);
            if (source == null) {
                source = collectPatternCandidates(target, getPlanningBudget().getMaxCandidatesPerTarget(), true);
                session.rememberRouteCandidates(target, source);
            }
            List<PatternCandidate> candidates = new ArrayList<>(source);
            if (!candidates.isEmpty()) {
                String plannedRecipe = session.findPlannedRecipe(target);
                PatternCandidate plannedCandidate = plannedRecipe == null ? null :
                        findCandidateByRecipeKey(candidates, plannedRecipe);
                if (plannedCandidate != null) {
                    candidates.remove(plannedCandidate);
                    candidates.add(0, plannedCandidate);
                } else if (plannedRecipe != null) {
                    candidates.clear();
                } else if (refineRoute) {
                    if (isStandalonePatternGeneration()) {
                        selectStandaloneTransientCandidate(target, candidates);
                    } else {
                        selectBestCandidate(target, candidates);
                    }
                } else {
                    selectFastTransientCandidate(target, candidates);
                }
            }

            List<DynamicRecipePatternDetails> details = new ArrayList<>(1);
            if (!candidates.isEmpty()) {
                PatternCandidate candidate = candidates.get(0);
                // A saved route must not retain AE2 input alternatives: later inventory changes would otherwise
                // make AE2 choose a different dependency from the one selected and shown in this task.
                RouteCostEstimator inputSelector = new RouteCostEstimator(candidate.source.grid, this,
                        newRouteCostBudget());
                EncodedRecipe frozen = isStandalonePatternGeneration() ?
                        inputSelector.freezeDirectInputs(candidate, target) :
                        inputSelector.freezeRootInputs(candidate, target);
                DynamicRecipePatternDetails detail = createPatternDetail(candidate, frozen);
                if (detail != null && isPatternAvailableFor(target, detail)) {
                    details.add(detail);
                    session.rememberTransientProvider(detail, candidate.source.provider);
                }
            }
            List<DynamicRecipePatternDetails> frozen = Collections.unmodifiableList(details);
            session.rememberTransientPatterns(target, frozen);
            return frozen;
        }

        /** A cached detail is visible to AE2 only after this state has selected and registered it. */
        private boolean isPublishedDynamicPattern(DynamicRecipePatternDetails detail,
                                                  MetaTileEntityMERecipeMapPatternProvider provider,
                                                  IGrid registeredGrid) {
            if (detail != null && provider != null && patternsByRecipe.get(detail.getRecipeKey()) == detail &&
                    providersByPattern.get(detail) == provider) {
                return true;
            }

            // GridNode.setGrid mounts native details before and during the registry transfer. Rebuilding here would
            // scan every remaining provider once per migrated node. Existing ownership is valid for that remount;
            // the next real dynamic lookup performs one coalesced rebuild against the completed topology.
            if (providerCacheRebuildGate.isPending() && providersByPattern.get(detail) == provider &&
                    (deferredNativePublicationProviderIds.contains(provider.getDynamicProviderId()) ||
                            providerHasMovedToAnotherGrid(provider, registeredGrid))) {
                return true;
            }
            ensureProviderCacheBindings();
            return detail != null && provider != null && patternsByRecipe.get(detail.getRecipeKey()) == detail &&
                    providersByPattern.get(detail) == provider;
        }

        private static boolean providerHasMovedToAnotherGrid(MetaTileEntityMERecipeMapPatternProvider provider,
                                                              IGrid registeredGrid) {
            try {
                return registeredGrid != null && provider.getMainNode().getGrid() != registeredGrid;
            } catch (IllegalStateException ignored) {
                return false;
            }
        }

        /** Restores the persisted standalone ordering only for details that still have a valid binding. */
        private void bindStandalonePatternOutputs(DynamicRecipePatternDetails detail) {
            for (GenericStack output : detail.getOutputs()) {
                if (output == null || output.amount() <= 0 || !detail.netProduces(output.what())) continue;
                standaloneRecipeKeysByTarget.compute(output.what(), (ignored, existing) -> {
                    Set<String> keys = existing == null ? new HashSet<>() : new HashSet<>(existing);
                    keys.add(detail.getRecipeKey());
                    return Collections.unmodifiableSet(keys);
                });
            }
        }

        /** Keeps the frozen route first without removing the user's regular alternatives. */
        private Collection<IPatternDetails> prioritizeStandalonePatterns(AEKey target,
                                                                          Collection<IPatternDetails> patterns) {
            Set<String> selectedKeys = standaloneRecipeKeysByTarget.get(target);
            if (selectedKeys == null || selectedKeys.isEmpty()) return patterns;

            List<IPatternDetails> selected = new ArrayList<>();
            List<IPatternDetails> remaining = new ArrayList<>(patterns.size());
            for (IPatternDetails pattern : patterns) {
                DynamicRecipePatternDetails detail = getDynamicPattern(pattern);
                if (detail != null && selectedKeys.contains(detail.getRecipeKey()) &&
                        patternsByRecipe.get(detail.getRecipeKey()) == detail && detail.netProduces(target)) {
                    selected.add(pattern);
                } else {
                    remaining.add(pattern);
                }
            }
            if (selected.isEmpty()) return patterns;

            selected.addAll(remaining);
            return Collections.unmodifiableList(selected);
        }

        /**
         * Selects intermediate graph nodes without starting a second recursive route search for every node.
         * The same generic material-form cost used by full scoring keeps each solid output on its canonical source
         * form, while rule/static costs resolve all other ties deterministically.
         */
        private static void selectFastTransientCandidate(AEKey target, List<PatternCandidate> candidates) {
            PlanningMode planningMode = resolvePlanningMode(candidates);
            candidates.sort((left, right) -> {
                if (isStandalonePatternGeneration()) {
                    int primaryCompound = compareStandalonePrimaryCompoundSynthesis(
                            left.primaryCompoundSynthesis, right.primaryCompoundSynthesis);
                    if (primaryCompound != 0) return primaryCompound;

                    int sourcePreference = compareStandaloneSourcePreference(target, left, right);
                    if (sourcePreference != 0) return sourcePreference;

                    int recycling = compareStandaloneRecyclingRoute(left.recyclingRoute, right.recyclingRoute);
                    if (recycling != 0) return recycling;
                } else {
                    int formCost = Integer.compare(getCandidateSolidMaterialInputFormCost(target, left),
                            getCandidateSolidMaterialInputFormCost(target, right));
                    if (formCost != 0) return formCost;
                }
                int staticCost = left.cost.compareTo(right.cost, planningMode);
                return staticCost != 0 ? staticCost : compareCandidates(left, right);
            });
        }

        /**
         * Standalone generation commits a deterministic dependency tree, not a live AE planning decision. Select
         * one root locally, then let the tree walk only that frozen route. Cycle screening remains separate so this
         * narrower traversal cannot publish an unsafe self-sustaining edge.
         */
        private void selectStandaloneTransientCandidate(AEKey target, List<PatternCandidate> candidates) {
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session != null && session.matches(this) && session.shouldStopTransientGraphBuild()) {
                session.recordStandaloneFastSelection();
                selectFastTransientCandidate(target, candidates);
                return;
            }

            // Compare competing paths before committing this node. Each selected target gets its own bounded
            // refinement allowance, while the enclosing standalone task keeps one shared time deadline.
            selectBestCandidate(target, candidates);
            logStandaloneSinglePathSelection(target, candidates);
        }

        /** Emits one focused record for a standalone route that needs material-source diagnostics. */
        private static void logStandaloneSinglePathSelection(AEKey target, List<PatternCandidate> candidates) {
            if (candidates.isEmpty()) return;

            boolean hasRelevantRoute = isChemicalProductProcessedSolidTarget(target);
            boolean hasRecyclingRoute = false;
            boolean hasDirectChemicalSynthesis = false;
            boolean hasNonChemicalAlternative = false;
            boolean hasSourceFormConflict = false;
            int firstSourceForm = -1;
            boolean solidMaterialTarget = isSolidMaterialTarget(target);
            boolean polymerFluidTarget = isChemicalProductFluidTarget(target);
            for (PatternCandidate candidate : candidates) {
                if (candidate.primaryCompoundSynthesis ||
                        "chemical_bath".equals(candidate.recipeMap.getUnlocalizedName())) {
                    hasRelevantRoute = true;
                }
                hasRecyclingRoute |= candidate.recyclingRoute;
                if (polymerFluidTarget) {
                    if (isDirectChemicalPolymerSynthesis(target, candidate)) {
                        hasDirectChemicalSynthesis = true;
                    } else {
                        hasNonChemicalAlternative = true;
                    }
                }
                if (solidMaterialTarget) {
                    int sourceForm = getCandidateSolidMaterialInputFormCost(target, candidate);
                    if (firstSourceForm < 0) {
                        firstSourceForm = sourceForm;
                    } else if (firstSourceForm != sourceForm) {
                        hasSourceFormConflict = true;
                    }
                }
            }
            boolean hasChemicalSourceConflict = hasDirectChemicalSynthesis && hasNonChemicalAlternative;
            if (!hasRelevantRoute && !hasChemicalSourceConflict &&
                    !(solidMaterialTarget && hasRecyclingRoute)) {
                return;
            }

            boolean describeSource = hasChemicalSourceConflict || isChemicalProductSolidTarget(target) ||
                    (solidMaterialTarget && hasRecyclingRoute && hasSourceFormConflict);

            List<String> ranking = new ArrayList<>(candidates.size());
            for (PatternCandidate candidate : candidates) {
                int formCost = getCandidateSolidMaterialInputFormCost(target, candidate);
                boolean directChemicalSynthesis = isDirectChemicalPolymerSynthesis(target, candidate);
                boolean declaredIngotTransformation = isDeclaredIngotTransformationCandidate(target, candidate);
                ranking.add(candidate.recipeMap.getUnlocalizedName() + '/' + candidate.normalized.getCategory() +
                        "{form=" + formCost +
                        (candidate.primaryCompoundSynthesis ? ", primaryCompound" : "") +
                        (directChemicalSynthesis ? ", directChemicalSynthesis" : "") +
                        (declaredIngotTransformation ? ", declaredIngotTransformation" : "") +
                        (describeSource ?
                                ", source=" + describeCandidateMaterialSource(target, candidate) : "") +
                        (candidate.recyclingRoute ? ", recycling" : "") +
                        (candidate == candidates.get(0) ? ", selected" : "") + '}');
            }
            Material material = getMaterialForKey(target);
            ApplyGrayMod.LOGGER.info("RecipeMap standalone single-path selection material={} prefix={} target={} " +
                            "strategy=SELECTED_ROUTE_ONLY candidates={}",
                    material == null ? "unknown" : material.getName(), getOrePrefixForKey(target), target, ranking);
        }

        private static String describeCandidateMaterialSource(AEKey target, PatternCandidate candidate) {
            Material targetMaterial = getMaterialForKey(target);
            if (targetMaterial == null) return "unknown";

            Material transformationInput = findDeclaredIngotTransformationInput(target, candidate);
            if (transformationInput != null) return "ingot:" + transformationInput.getName();

            List<String> sourceForms = new ArrayList<>();
            for (IPatternDetails.IInput input : DynamicRecipePatternDetails.createScoringInputs(
                    candidate.encoded.inputs, candidate.encoded.alternatives)) {
                for (GenericStack option : input.possibleInputs()) {
                    if (option == null || option.amount() <= 0) continue;
                    AEKey inputKey = option.what();
                    if (!targetMaterial.equals(getMaterialForKey(inputKey))) continue;
                    if (inputKey instanceof AEFluidKey) {
                        sourceForms.add("fluid");
                        continue;
                    }
                    String prefixName = getOrePrefixForKey(inputKey);
                    sourceForms.add(prefixName == null ? "item" : prefixName);
                }
            }
            return sourceForms.isEmpty() ? "compound" : String.join("+", sourceForms);
        }

        private List<IPatternDetails> availableTransientPatterns(CraftingRecoverySession session, AEKey target,
                                                                   List<DynamicRecipePatternDetails> patterns) {
            List<IPatternDetails> available = new ArrayList<>(patterns.size());
            for (DynamicRecipePatternDetails detail : patterns) {
                if (session.isTransientPattern(target, detail) && isPatternAvailableFor(target, detail)) {
                    available.add(detail);
                    session.recordExposed(target, detail);
                }
            }
            return available;
        }

        /**
         * Prepares a stable candidate graph before CraftingCalculation.run() may populate AE2's lookup cache.
         * Normal patterns are inspected only to discover their dependencies; their own ownership remains AE2's.
         */
        private void prepareTransientPatternGraph(CraftingRecoverySession session) {
            if (session.isTransientGraphPrepared()) return;

            PlanningBudget budget = getPlanningBudget();
            Deque<AEKey> pending = new ArrayDeque<>();
            Map<AEKey, Integer> depths = new HashMap<>();
            pending.add(session.rootTarget);
            depths.put(session.rootTarget, 0);
            int expanded = 0;
            long startedAt = System.nanoTime();
            int routeExpansionLimit = getRouteExpansionLimit(budget);

            // A standalone template must be a single selected chain. Building a complete candidate graph first
            // unnecessarily visits inputs belonging to routes that will never be materialized.
            if (isStandalonePatternGeneration()) {
                session.beginTransientGraphBuild(startedAt + getRouteCalculationNanos(budget));
                int selectedTargets;
                try {
                    selectedTargets = materializeSelectedTransientTree(session, budget, routeExpansionLimit);
                } finally {
                    session.endTransientGraphBuild();
                }
                session.markTransientGraphPrepared(selectedTargets, System.nanoTime() - startedAt);
                return;
            }

            session.beginTransientGraphBuild(startedAt + getRouteCalculationNanos(budget));

            try {
                while (!pending.isEmpty()) {
                    if (Thread.currentThread().isInterrupted()) {
                        abortCancelledCalculation();
                        return;
                    }
                    if (expanded >= routeExpansionLimit ||
                            session.shouldStopTransientGraphBuild()) {
                        session.markTransientGraphIncomplete();
                        break;
                    }

                    AEKey target = pending.removeFirst();
                    int depth = depths.getOrDefault(target, 0);
                    if (session.findRouteCandidates(target) != null) continue;
                    expanded++;

                    List<PatternCandidate> dynamic = collectPatternCandidates(target,
                            budget.getMaxCandidatesPerTarget(), true);
                    session.rememberRouteCandidates(target, dynamic);
                    List<IPatternDetails> normal = getNormalPatternsForRouteCost(session.grid, target);
                    if (depth >= budget.getMaxRouteDepth()) continue;

                    for (PatternCandidate candidate : dynamic) {
                        enqueueCandidateInputs(pending, depths, candidate, depth + 1,
                                budget.getMaxInputAlternatives());
                    }
                    for (IPatternDetails detail : normal) {
                        enqueuePatternInputs(pending, depths, detail, depth + 1, budget.getMaxInputAlternatives());
                    }
                }
            } finally {
                session.endTransientGraphBuild();
            }
            if (Boolean.TRUE.equals(OPTIMAL_ROUTE_GENERATION.get())) {
                materializeSelectedTransientTree(session, budget, routeExpansionLimit);
            } else {
                materializeTransientSelections(session);
            }
            session.markTransientGraphPrepared(expanded, System.nanoTime() - startedAt);
        }

        private void materializeTransientSelections(CraftingRecoverySession session) {
            for (AEKey target : session.getTransientCandidateTargets()) {
                if (session.findTransientPatterns(target) == null) {
                    createTransientPatterns(session, target, target.equals(session.rootTarget));
                }
            }
        }

        /**
         * Builds the standalone tree root-first. Every dynamic node selects one SCC-safe route and only its frozen
         * inputs are expanded; ordinary AE2 patterns remain fixed edges whose inputs can lead to generated samples.
         */
        private int materializeSelectedTransientTree(CraftingRecoverySession session, PlanningBudget budget,
                                                     int routeExpansionLimit) {
            Deque<AEKey> pending = new ArrayDeque<>();
            Map<AEKey, Integer> depths = new HashMap<>();
            pending.add(session.rootTarget);
            depths.put(session.rootTarget, 0);
            int selectedTargets = 0;

            while (!pending.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) {
                    abortCancelledCalculation();
                    return selectedTargets;
                }
                StandaloneTreeMaterializationStep step = selectStandaloneTreeMaterializationStep(selectedTargets,
                        routeExpansionLimit, session.shouldStopTransientGraphBuild());
                if (step == StandaloneTreeMaterializationStep.STOP) {
                    session.markTransientGraphIncomplete();
                    break;
                }

                AEKey target = pending.removeFirst();
                int depth = depths.getOrDefault(target, 0);
                if (session.findTransientPatterns(target) != null) continue;
                selectedTargets++;

                List<DynamicRecipePatternDetails> selected = createTransientPatterns(session, target, true);
                if (depth >= budget.getMaxRouteDepth()) continue;
                if (!selected.isEmpty()) {
                    for (DynamicRecipePatternDetails detail : selected) {
                        enqueueSelectedPatternInputsDepthFirst(pending, depths, target, detail, depth + 1,
                                budget.getMaxInputAlternatives());
                    }
                    continue;
                }

                // Normal patterns are fixed graph edges, not leaves. Expand their complete input set so a normal
                // processing pattern can still lead to generated RecipeMap samples further down the same route.
                for (IPatternDetails detail : getNormalPatternsForRouteCost(session.grid, target)) {
                    enqueueSelectedPatternInputsDepthFirst(pending, depths, target, detail, depth + 1,
                            budget.getMaxInputAlternatives());
                }
            }
            session.freezeStandaloneSelection(selectedTargets);
            return selectedTargets;
        }

        /** Persists the already-selected task-local routes only after their complete bounded graph is prepared. */
        private StandalonePatternMaterialization materializePreparedTransientPatterns() {
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session == null || !session.matches(this) || !session.isTransientGraphPrepared()) {
                return new StandalonePatternMaterialization(0, 0, 0, Collections.emptySet());
            }

            Set<AEKey> replacementTargets = getStandaloneReplacementTargets(session);
            Set<String> selectedRecipeKeys = new HashSet<>();
            Map<MetaTileEntityMERecipeMapPatternProvider, Set<String>> selectedKeysByProvider = new HashMap<>();
            Set<String> affectedProviderIds = new HashSet<>();
            // Only outputs selected by this graph are replaced. Frozen routes for unrelated items stay available.
            for (AEKey target : replacementTargets) {
                standaloneRecipeKeysByTarget.remove(target);
            }
            int materializedTargets = 0;
            int materializedPatterns = 0;
            for (AEKey target : session.getTransientCandidateTargets()) {
                List<DynamicRecipePatternDetails> selected = session.findTransientPatterns(target);
                List<PatternCandidate> candidates = session.findRouteCandidates(target);
                if (selected == null || selected.isEmpty() || candidates == null || candidates.isEmpty()) continue;

                List<DynamicRecipePatternDetails> persisted = new ArrayList<>(selected.size());
                Set<String> persistedRecipeKeys = new HashSet<>();
                for (DynamicRecipePatternDetails detail : selected) {
                    PatternCandidate candidate = findCandidateByRecipeKey(candidates, detail.getRecipeKey());
                    if (candidate == null) continue;
                    // A stale definition can be removed inside materializePattern(), so refresh the selected source
                    // even when the candidate ultimately cannot be materialized.
                    affectedProviderIds.add(candidate.source.provider.getDynamicProviderId());
                    DynamicRecipePatternDetails registered = materializePattern(target, candidate,
                            candidate.encoded.withFrozenInputs(detail.getInputs()));
                    if (registered != null) persisted.add(registered);
                    if (registered != null) {
                        persistedRecipeKeys.add(registered.getRecipeKey());
                        selectedKeysByProvider.computeIfAbsent(candidate.source.provider,
                                ignored -> new HashSet<>()).add(registered.getRecipeKey());
                    }
                }
                if (!persisted.isEmpty()) {
                    patternsByTarget.put(target, Collections.unmodifiableList(persisted));
                    standaloneRecipeKeysByTarget.put(target, Collections.unmodifiableSet(persistedRecipeKeys));
                    for (DynamicRecipePatternDetails detail : persisted) {
                        selectedRecipeKeys.add(detail.getRecipeKey());
                    }
                    materializedTargets++;
                    materializedPatterns += persisted.size();
                }
            }
            int stalePatternCount = discardObsoleteStandalonePatterns(replacementTargets, selectedRecipeKeys,
                    affectedProviderIds);
            if (stalePatternCount > 0) {
                ApplyGrayMod.LOGGER.info("Replaced {} conflicting dynamic RecipeMap pattern(s) across {} standalone " +
                                "output(s); unrelated completed standalone routes were retained",
                        stalePatternCount, replacementTargets.size());
            }
            for (ProviderSnapshot snapshot : providers.values()) {
                snapshot.provider.addFrozenStandalonePatternKeys(
                        selectedKeysByProvider.getOrDefault(snapshot.provider, Collections.emptySet()));
            }
            if (ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Materialized {} selected RecipeMap pattern target(s) after standalone " +
                                "generation graph preparation and discarded {} obsolete pattern(s)",
                        materializedTargets, stalePatternCount);
            }
            return new StandalonePatternMaterialization(materializedTargets, materializedPatterns, stalePatternCount,
                    affectedProviderIds);
        }

        /** Replaces only the dynamic details that could alter the frozen standalone dependency tree. */
        private static Set<AEKey> getStandaloneReplacementTargets(CraftingRecoverySession session) {
            Set<AEKey> targets = session.getTransientCandidateTargets();
            for (AEKey output : new ArrayList<>(targets)) {
                List<DynamicRecipePatternDetails> selected = session.findTransientPatterns(output);
                if (selected == null) continue;
                for (DynamicRecipePatternDetails detail : selected) {
                    if (!isCanonicalSameMaterialDustToIngotTransition(output, RouteEdge.of(detail))) continue;
                    for (IPatternDetails.IInput input : detail.getInputs()) {
                        for (GenericStack option : input.possibleInputs()) {
                            if (option != null && option.amount() > 0 &&
                                    isCanonicalSameMaterialDustInput(output, option.what())) {
                                targets.add(option.what());
                            }
                        }
                    }
                }
            }
            return targets;
        }

        private int discardObsoleteStandalonePatterns(Set<AEKey> replacementTargets,
                                                       Set<String> selectedRecipeKeys,
                                                       Set<String> affectedProviderIds) {
            if (replacementTargets.isEmpty()) return 0;

            Set<String> obsoleteRecipeKeys = new HashSet<>();
            for (ProviderSnapshot snapshot : providers.values()) {
                for (DynamicRecipePatternDetails detail : snapshot.provider.getCachedDynamicPatterns()) {
                    if (selectedRecipeKeys.contains(detail.getRecipeKey()) ||
                            !producesStandaloneReplacementTarget(detail, replacementTargets)) {
                        continue;
                    }
                    snapshot.provider.removeCachedDynamicPattern(detail.getRecipeKey());
                    affectedProviderIds.add(snapshot.providerId);
                    obsoleteRecipeKeys.add(detail.getRecipeKey());
                }
            }
            if (obsoleteRecipeKeys.isEmpty()) return 0;

            for (AEKey target : replacementTargets) {
                patternsByTarget.computeIfPresent(target, (ignored, existing) ->
                        removePatternsByRecipeKey(existing, obsoleteRecipeKeys));
                rejectedRecipeKeysByTarget.remove(target);
                routeCandidateCache.remove(target);
            }
            for (String recipeKey : obsoleteRecipeKeys) {
                patternsByRecipe.remove(recipeKey);
            }
            return obsoleteRecipeKeys.size();
        }

        private static boolean producesStandaloneReplacementTarget(DynamicRecipePatternDetails detail,
                                                                    Set<AEKey> replacementTargets) {
            for (GenericStack output : detail.getOutputs()) {
                if (output != null && output.amount() > 0 && replacementTargets.contains(output.what()) &&
                        detail.netProduces(output.what())) {
                    return true;
                }
            }
            return false;
        }

        private static String getPersistedPatternPublicationRejection(ProviderSnapshot snapshot,
                                                                        DynamicRecipePatternDetails detail) {
            String detailFailure = getPersistedDetailValidationFailure(detail);
            if (detailFailure != null) return detailFailure;

            boolean matchingRecipeMap = false;
            boolean enabledRecipeMap = false;
            String resolverFailure = null;
            for (RecipeMap<?> recipeMap : snapshot.recipeMaps) {
                if (!recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName())) continue;
                matchingRecipeMap = true;
                if (!isDynamicRecipeMapEnabled(recipeMap)) continue;
                enabledRecipeMap = true;

                RecipeBindingResolver.Resolution resolution =
                        RecipeBindingResolver.resolve(detail.getRecipeBinding(), recipeMap);
                if (resolution.isResolved()) return "REGISTRY_BINDING_PENDING";
                resolverFailure = resolution.getReasonCode();
            }
            if (!matchingRecipeMap) return "RECIPE_MAP_UNAVAILABLE";
            if (!enabledRecipeMap) return "RECIPE_MAP_DISABLED";
            return resolverFailure == null ? "RECIPE_BINDING_UNAVAILABLE" : resolverFailure;
        }

        /** Verifies that the serialized detail still advertises the exact target protected by its binding. */
        @Nullable
        private static String getPersistedDetailValidationFailure(DynamicRecipePatternDetails detail) {
            if (detail == null || detail.getRecipeBinding() == null) return "MISSING_BINDING";
            RecipeBinding binding = detail.getRecipeBinding();
            if (!binding.isForRecipeMap(detail.getRecipeMapName())) return "DETAIL_RECIPE_MAP_MISMATCH";
            List<GenericStack> outputs = detail.getOutputs();
            if (outputs.size() != 1 || outputs.get(0) == null || outputs.get(0).amount() <= 0 ||
                    !detail.netProduces(outputs.get(0).what()) ||
                    !binding.getTargetKey().equals(RecipeFingerprint.describeKey(outputs.get(0).what()))) {
                return "DETAIL_TARGET_MISMATCH";
            }
            return null;
        }

        @Nullable
        private static List<DynamicRecipePatternDetails> removePatternsByRecipeKey(
                List<DynamicRecipePatternDetails> existing, Set<String> obsoleteRecipeKeys) {
            List<DynamicRecipePatternDetails> retained = new ArrayList<>(existing.size());
            for (DynamicRecipePatternDetails detail : existing) {
                if (!obsoleteRecipeKeys.contains(detail.getRecipeKey())) retained.add(detail);
            }
            if (retained.size() == existing.size()) return existing;
            return retained.isEmpty() ? null : Collections.unmodifiableList(retained);
        }

        /** Runs on the server thread after the selected graph has been published into this state's indexes. */
        private StandalonePatternPublication publishStandalonePatternGeneration(Set<String> affectedProviderIds) {
            if (affectedProviderIds == null || affectedProviderIds.isEmpty()) {
                return new StandalonePatternPublication(0, 0);
            }

            List<ProviderSnapshot> snapshots = new ArrayList<>(affectedProviderIds.size());
            for (String providerId : affectedProviderIds) {
                ProviderSnapshot snapshot = providers.get(providerId);
                if (snapshot != null) snapshots.add(snapshot);
            }
            int refreshedProviders = 0;
            for (ProviderSnapshot snapshot : snapshots) {
                try {
                    if (snapshot.provider.publishCachedPatternsImmediately()) {
                        refreshedProviders++;
                    }
                } catch (RuntimeException exception) {
                    ApplyGrayMod.LOGGER.warn("Could not refresh RecipeMap pattern provider {} after standalone " +
                            "generation", snapshot.providerId, exception);
                }
            }
            return new StandalonePatternPublication(snapshots.size(), refreshedProviders);
        }

        @Nullable
        private static PatternCandidate findCandidateByRecipeKey(List<PatternCandidate> candidates, String recipeKey) {
            for (PatternCandidate candidate : candidates) {
                if (candidate.recipeKey.equals(recipeKey)) return candidate;
            }
            return null;
        }

        /** Builds the UI tree from the same frozen details that AE2 will later query. */
        private PatternGenerationTreeBuilder generatePatternTree(AEKey target, long amount) {
            int nodeLimit = getPatternGenerationTreeNodeLimit(getPlanningBudget());
            PatternGenerationTreeBuilder builder = new PatternGenerationTreeBuilder(this, getGridForPatternGeneration(),
                    target, nodeLimit, getPlanningBudget().getMaxRouteDepth());
            builder.setRoot(builder.build(target, amount, null, 0, new HashSet<>(), new ArrayList<>()));
            builder.logSummary();
            return builder;
        }

        @Nullable
        private IGrid getGridForPatternGeneration() {
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            return session != null && session.matches(this) ? session.grid : null;
        }

        /** Starts a fresh optimal-route materialization without creating an AE2 crafting calculation. */
        private synchronized void prepareOptimalRouteGeneration() {
            clearGenerated();
            // A new root replaces only its overlapping outputs after its graph is complete. Rebind older frozen
            // graphs now so they remain reusable for this root and continue to be published after the refresh.
            bindFrozenStandalonePatterns();
            requestFullRecipeOutputIndexRebuild();
        }

        private static void enqueueCandidateInputs(Deque<AEKey> pending, Map<AEKey, Integer> depths,
                                                   PatternCandidate candidate, int depth, int alternativeLimit) {
            for (IPatternDetails.IInput input : DynamicRecipePatternDetails.createScoringInputs(
                    candidate.encoded.inputs, candidate.encoded.alternatives)) {
                GenericStack[] choices = input.possibleInputs();
                for (int index = 0; index < Math.min(choices.length, alternativeLimit); index++) {
                    GenericStack choice = choices[index];
                    if (choice != null && choice.amount() > 0 && !depths.containsKey(choice.what())) {
                        depths.put(choice.what(), depth);
                        pending.addLast(choice.what());
                    }
                }
            }
        }

        private static void enqueuePatternInputs(Deque<AEKey> pending, Map<AEKey, Integer> depths,
                                                 IPatternDetails detail, int depth, int alternativeLimit) {
            for (IPatternDetails.IInput input : detail.getInputs()) {
                GenericStack[] choices = input.possibleInputs();
                for (int index = 0; index < Math.min(choices.length, alternativeLimit); index++) {
                    GenericStack choice = choices[index];
                    if (choice != null && choice.amount() > 0 && !depths.containsKey(choice.what())) {
                        depths.put(choice.what(), depth);
                        pending.addLast(choice.what());
                    }
                }
            }
        }

        /**
         * A standalone template owns one frozen route, so process one selected branch to completion before unrelated
         * siblings. This keeps a hot ingot's dust-to-ingot step reachable even when another branch is expensive.
         */
        private static void enqueueSelectedPatternInputsDepthFirst(Deque<AEKey> pending,
                                                                    Map<AEKey, Integer> depths, AEKey output,
                                                                    IPatternDetails detail, int depth,
                                                                    int alternativeLimit) {
            List<AEKey> inputs = new ArrayList<>();
            boolean canonicalDustToIngot = isCanonicalSameMaterialDustToIngotTransition(output,
                    RouteEdge.of(detail));
            for (IPatternDetails.IInput input : detail.getInputs()) {
                GenericStack[] choices = input.possibleInputs();
                for (int index = 0; index < Math.min(choices.length, alternativeLimit); index++) {
                    GenericStack choice = choices[index];
                    if (canonicalDustToIngot && choice != null &&
                            isCanonicalSameMaterialDustInput(output, choice.what())) {
                        continue;
                    }
                    if (choice != null && choice.amount() > 0 && !depths.containsKey(choice.what())) {
                        depths.put(choice.what(), depth);
                        inputs.add(choice.what());
                    }
                }
            }
            for (int index = inputs.size() - 1; index >= 0; index--) {
                pending.addFirst(inputs.get(index));
            }
        }

        private boolean isSessionCandidateRejected(AEKey target, PatternCandidate candidate) {
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session == null || !session.matches(this) || !session.hasFilters(target)) return false;
            IPatternDetails.IInput[] inputs = DynamicRecipePatternDetails.createScoringInputs(
                    candidate.encoded.inputs, candidate.encoded.alternatives);
            return session.isRejected(target, candidate.recipeKey, inputs);
        }

        private boolean hasSessionFilters(AEKey target) {
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            return session != null && session.matches(this) && session.hasFilters(target);
        }

        /**
         * Produces a bounded scan in rounds so a broad RecipeMap such as the extractor cannot consume every lookup
         * slot before a direct route in another enabled map is inspected.
         */
        @Nullable
        private RecipeScanPlan collectFairRecipeScanPlan(AEKey target, List<ProviderSnapshot> sources,
                                                          int maximumRecipes) {
            List<RecipeScanCursor> cursors = new ArrayList<>();
            for (ProviderSnapshot source : sources) {
                if (!cooperateWithCraftingCalculation()) return null;
                KeyCounter storedItems = getStoredItems(source);
                for (RecipeMap<?> recipeMap : source.recipeMaps) {
                    if (!isDynamicRecipeMapEnabled(recipeMap)) continue;
                    RecipeOutputIndex outputIndex = getRecipeOutputIndex(recipeMap);
                    if (outputIndex == null) return null;
                    List<Recipe> matchingRecipes = outputIndex.getRecipes(target);
                    if (!matchingRecipes.isEmpty()) {
                        cursors.add(new RecipeScanCursor(source, storedItems, recipeMap, outputIndex,
                                matchingRecipes));
                    }
                }
            }

            int limit = Math.max(1, maximumRecipes);
            List<RecipeScanEntry> entries = new ArrayList<>(limit);
            List<Integer> candidateCounts = new ArrayList<>(cursors.size());
            for (RecipeScanCursor cursor : cursors) {
                candidateCounts.add(cursor.remainingCount());
            }
            for (int cursorIndex : fairRecipeScanBucketOrder(candidateCounts, limit)) {
                RecipeScanEntry entry = cursors.get(cursorIndex).next();
                if (entry != null) entries.add(entry);
            }

            boolean truncated = false;
            for (RecipeScanCursor cursor : cursors) {
                if (cursor.hasRemaining()) {
                    truncated = true;
                    break;
                }
            }
            return new RecipeScanPlan(entries, truncated);
        }

        private List<PatternCandidate> collectPatternCandidates(AEKey target, int candidateLimit,
                                                                 boolean applySessionFilters) {
            List<PatternCandidate> candidates = new ArrayList<>(candidateLimit);
            List<ProviderSnapshot> sources = new ArrayList<>(providers.values());
            Set<String> seenRecipeKeys = new HashSet<>();
            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            PlanningBudget planningBudget = getPlanningBudget();
            RecipeScanPlan scanPlan = collectFairRecipeScanPlan(target, sources,
                    planningBudget.getMaxRecipesPerTarget());
            if (scanPlan == null) return Collections.emptyList();
            long inspectedRecipes = 0;
            boolean cappedRecipeScan = scanPlan.isTruncated();
            boolean stoppedForSession = false;

            for (RecipeScanEntry scanEntry : scanPlan.getEntries()) {
                        ProviderSnapshot source = scanEntry.source;
                        KeyCounter storedItems = scanEntry.storedItems;
                        RecipeMap<?> recipeMap = scanEntry.recipeMap;
                        RecipeOutputIndex outputIndex = scanEntry.outputIndex;
                        Recipe recipe = scanEntry.recipe;
                        long candidateStartedAt = System.nanoTime();
                        if (!cooperateWithCraftingCalculation()) {
                            return Collections.emptyList();
                        }
                        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
                        if (session != null && session.matches(this) && session.shouldStopTransientGraphBuild()) {
                            cappedRecipeScan = true;
                            stoppedForSession = true;
                            break;
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
                        boolean primaryCompoundSynthesis =
                                Boolean.TRUE.equals(facts.get("primaryCompoundSynthesis"));
                        boolean recyclingRoute = Boolean.TRUE.equals(facts.get("recycling"));
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
                                encoded, targeted, decision, cost, primaryCompoundSynthesis, recyclingRoute);
                        if (!seenRecipeKeys.add(candidate.recipeKey)) continue;
                        if (isRejectedFor(target, candidate.recipeKey)) continue;
                        if (applySessionFilters && isSessionCandidateRejected(target, candidate)) continue;
                        keepBestCandidate(candidates, candidate, candidateLimit);
            }

            if (cappedRecipeScan) {
                PLANNING_METRICS.recordBudgetExhaustion();
                String exhaustionReason = stoppedForSession ? "TRANSIENT_GRAPH_LIMIT" : "FAIR_RECIPE_SCAN_LIMIT";
                ApplyGrayMod.LOGGER.warn("Lazy RecipeMap pattern lookup stopped target={} reasonCode=BUDGET_EXHAUSTED " +
                                "budgetReason={} scanStrategy=ROUND_ROBIN_RECIPE_MAPS limit={}",
                        target, exhaustionReason, planningBudget.getMaxRecipesPerTarget());
                if (planningBudget.getExhaustionPolicy() == BudgetExhaustionPolicy.REJECT) {
                    ApplyGrayMod.LOGGER.warn("Rejected incomplete RecipeMap candidates target={} " +
                                    "reasonCode=BUDGET_EXHAUSTED budgetPolicy=REJECT",
                            target);
                    return Collections.emptyList();
                }
            }
            if (candidates.isEmpty() && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("No viable dynamic RecipeMap route target={} inspectedRecipes={} " +
                                "activeProviders={} candidateLimit={} budgetCapped={}",
                        target, inspectedRecipes, sources.size(), candidateLimit, cappedRecipeScan);
            }
            return candidates;
        }

        private static final class RecipeScanPlan {
            private final List<RecipeScanEntry> entries;
            private final boolean truncated;

            private RecipeScanPlan(List<RecipeScanEntry> entries, boolean truncated) {
                this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
                this.truncated = truncated;
            }

            private List<RecipeScanEntry> getEntries() {
                return entries;
            }

            private boolean isTruncated() {
                return truncated;
            }
        }

        private static final class RecipeScanCursor {
            private final ProviderSnapshot source;
            private final KeyCounter storedItems;
            private final RecipeMap<?> recipeMap;
            private final RecipeOutputIndex outputIndex;
            private final List<Recipe> recipes;
            private int nextIndex;

            private RecipeScanCursor(ProviderSnapshot source, KeyCounter storedItems, RecipeMap<?> recipeMap,
                                     RecipeOutputIndex outputIndex, List<Recipe> recipes) {
                this.source = source;
                this.storedItems = storedItems;
                this.recipeMap = recipeMap;
                this.outputIndex = outputIndex;
                this.recipes = recipes;
            }

            @Nullable
            private RecipeScanEntry next() {
                if (nextIndex >= recipes.size()) return null;
                return new RecipeScanEntry(source, storedItems, recipeMap, outputIndex, recipes.get(nextIndex++));
            }

            private boolean hasRemaining() {
                return nextIndex < recipes.size();
            }

            private int remainingCount() {
                return Math.max(0, recipes.size() - nextIndex);
            }
        }

        private static final class RecipeScanEntry {
            private final ProviderSnapshot source;
            private final KeyCounter storedItems;
            private final RecipeMap<?> recipeMap;
            private final RecipeOutputIndex outputIndex;
            private final Recipe recipe;

            private RecipeScanEntry(ProviderSnapshot source, KeyCounter storedItems, RecipeMap<?> recipeMap,
                                    RecipeOutputIndex outputIndex, Recipe recipe) {
                this.source = source;
                this.storedItems = storedItems;
                this.recipeMap = recipeMap;
                this.outputIndex = outputIndex;
                this.recipe = recipe;
            }
        }

        private void selectBestCandidate(AEKey target, List<PatternCandidate> candidates) {
            if (candidates.isEmpty()) return;
            long startedAt = System.nanoTime();
            RouteCostEstimator estimator = new RouteCostEstimator(candidates.get(0).source.grid, this,
                    newRouteCostBudget());
            if (!retainCycleSafeCandidates(target, candidates, estimator)) return;
            if (rejectIncompletePlanning(target, candidates, estimator)) return;
            if (candidates.size() < 2) {
                estimator.commitSelectedPlan(candidates.get(0), target);
                rejectIncompletePlanning(target, candidates, estimator);
                return;
            }

            PlanningMode planningMode = resolvePlanningMode(candidates);
            Map<PatternCandidate, DirectRouteCost> quickCosts = new IdentityHashMap<>();
            for (PatternCandidate candidate : candidates) {
                quickCosts.put(candidate, estimator.estimateDirect(candidate, target));
            }
            candidates.sort((left, right) -> {
                int memoryHint = compareCycleMemoryHint(left.targeted.getBinding(), right.targeted.getBinding());
                if (memoryHint != 0) return memoryHint;
                if (isStandalonePatternGeneration()) {
                    int sourcePreference = compareStandaloneSourcePreference(target, left, right);
                    if (sourcePreference != 0) return sourcePreference;
                    int recycling = compareStandaloneRecyclingRoute(left.recyclingRoute, right.recyclingRoute);
                    if (recycling != 0) return recycling;
                }
                int staticCost = left.cost.compareTo(right.cost, planningMode);
                int quickCost = quickCosts.get(left).compareTo(quickCosts.get(right));
                int cost = compareRouteAndStaticCost(planningMode, quickCost, staticCost);
                return cost != 0 ? cost : compareCandidates(left, right);
            });

            PatternCandidate staticSelection = candidates.get(0);
            boolean stockOnlySelection = quickCosts.get(candidates.get(0)).isFullyStocked();
            Map<PatternCandidate, RouteCost> refinedCosts = new IdentityHashMap<>();
            Map<PatternCandidate, RouteScoringProgress> refinedProgress = new IdentityHashMap<>();
            if (!stockOnlySelection) {
                List<PatternCandidate> refined = selectDiverseCandidates(candidates,
                        getPlanningBudget().getMaxRefinedCandidates(), candidate -> dependencyOptions(
                                DynamicRecipePatternDetails.createScoringInputs(candidate.encoded.inputs,
                                        candidate.encoded.alternatives)));
                scoreRefinedRoutes(refined, refinedCosts, refinedProgress, estimator,
                        (candidate, quota) -> estimator.estimateRootWithQuota(candidate, target, quota));
                refined.sort((left, right) -> {
                    int memoryHint = compareCycleMemoryHint(left.targeted.getBinding(), right.targeted.getBinding());
                    if (memoryHint != 0) return memoryHint;
                    if (isStandalonePatternGeneration()) {
                        RouteCost leftRoute = refinedCosts.get(left);
                        RouteCost rightRoute = refinedCosts.get(right);
                        RouteScoringProgress leftProgress = refinedProgress.get(left);
                        RouteScoringProgress rightProgress = refinedProgress.get(right);
                        int sourcePreference = compareStandaloneSourcePreference(target, left, right);
                        if (sourcePreference != 0) return sourcePreference;
                        int deferredChemicalBath = compareStandaloneIncompleteChemicalBath(
                                isChemicalProductProcessedSolidTarget(target),
                                isChemicalBathRoute(left), leftProgress != null && leftProgress.isQuotaLimited(),
                                leftRoute != null && leftRoute.hasBoundedFallback(),
                                isChemicalBathRoute(right), rightProgress != null && rightProgress.isQuotaLimited(),
                                rightRoute != null && rightRoute.hasBoundedFallback());
                        if (deferredChemicalBath != 0) return deferredChemicalBath;
                        int recycling = compareStandaloneRecyclingRoute(left.recyclingRoute, right.recyclingRoute);
                        if (recycling != 0) return recycling;
                    }
                    int staticCost = left.cost.compareTo(right.cost, planningMode);
                    int routeCost = refinedCosts.get(left).compareTo(refinedCosts.get(right));
                    int cost = compareRouteAndStaticCost(planningMode, routeCost, staticCost);
                    return cost != 0 ? cost : compareCandidates(left, right);
                });

                PatternCandidate selected = refined.get(0);
                if (candidates.get(0) != selected) {
                    candidates.remove(selected);
                    candidates.add(0, selected);
                }
            }

            estimator.commitSelectedPlan(candidates.get(0), target);
            if (rejectIncompletePlanning(target, candidates, estimator)) return;

            if (isStandalonePatternGeneration() && candidates.get(0) != staticSelection) {
                CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
                if (session != null && session.matches(this)) {
                    session.recordStandaloneRecursiveRouteOverride(target, staticSelection, candidates.get(0));
                }
            }

            logChemicalProductSolidSelection(target, candidates, quickCosts, refinedCosts, refinedProgress,
                    stockOnlySelection);

            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            if (optimalRebuild != null) {
                optimalRebuild.inventoryScoredTargets.add(target);
                optimalRebuild.recordRouteCostEstimator(target, candidates.size(), refinedCosts.size(),
                        stockOnlySelection, System.nanoTime() - startedAt, estimator);
                if (ApplyGrayMod.LOGGER.isDebugEnabled()) {
                    List<String> ranking = new ArrayList<>(candidates.size());
                    for (PatternCandidate candidate : candidates) {
                        ranking.add(candidate.recipeMap.getUnlocalizedName() + ':' +
                                candidate.normalized.getRecipeFingerprint() + "={inputs=" +
                                describeCandidateInputs(candidate) + ", quick=" + quickCosts.get(candidate) +
                                (refinedCosts.containsKey(candidate) ? ", refined=" + refinedCosts.get(candidate) : "") +
                                '}');
                    }
                    ApplyGrayMod.LOGGER.debug("RecipeMap route selection target={} mode={} selected={} ranking={}",
                            target, planningMode, candidates.get(0).recipeKey, ranking);
                }
            }
        }

        private static boolean isChemicalBathRoute(PatternCandidate candidate) {
            return candidate != null && "chemical_bath".equals(candidate.recipeMap.getUnlocalizedName());
        }

        /** Applies the SCC safety filter independently from the route-cost ranking strategy. */
        private boolean retainCycleSafeCandidates(AEKey target, List<PatternCandidate> candidates,
                                                  RouteCostEstimator estimator) {
            int rejectedCycleCandidates = estimator.rejectUnsafeRootCandidates(target, candidates);
            if (!estimator.isCycleAnalysisComplete()) {
                CycleSafetyExhaustionPolicy exhaustionPolicy =
                        getPlanningBudget().getCycleSafetyExhaustionPolicy();
                boolean withhold = exhaustionPolicy == CycleSafetyExhaustionPolicy.FALLBACK_NORMAL;
                CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
                boolean firstObservation = session == null || !session.matches(this) ||
                        session.markSafetyUnknown(target, estimator.getCycleAnalysisReason(), withhold);
                if (withhold) {
                    candidates.clear();
                    if (!firstObservation) return false;
                    ApplyGrayMod.LOGGER.warn("Withheld safety-unknown dynamic RecipeMap routes target={} " +
                                    "reasonCode=CYCLE_SAFETY_UNKNOWN budgetReason={} fallback=NORMAL_PATTERN",
                            target, estimator.getCycleAnalysisReason());
                } else if (firstObservation && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                    ApplyGrayMod.LOGGER.debug("Continuing safety-unknown dynamic RecipeMap routes target={} " +
                            "reasonCode=CYCLE_SAFETY_UNKNOWN budgetReason={} recovery=BOUNDED_RUNTIME",
                            target, estimator.getCycleAnalysisReason());
                }
                if (withhold) return false;
            }
            if (candidates.isEmpty()) {
                ApplyGrayMod.LOGGER.warn("Rejected all dynamic RecipeMap routes target={} reasonCode=CYCLE_NO_EXTERNAL_SEED " +
                                "rejectedCandidates={}",
                        target, rejectedCycleCandidates);
                return false;
            }
            return true;
        }

        /** Logs one focused selection record for a direct chemical polymer plate route during standalone generation. */
        private static void logChemicalProductSolidSelection(AEKey target, List<PatternCandidate> candidates,
                                                              Map<PatternCandidate, DirectRouteCost> quickCosts,
                                                              Map<PatternCandidate, RouteCost> refinedCosts,
                                                              Map<PatternCandidate, RouteScoringProgress> refinedProgress,
                                                              boolean stockOnlySelection) {
            if (!Boolean.TRUE.equals(OPTIMAL_ROUTE_GENERATION.get()) ||
                    !isChemicalProductProcessedSolidTarget(target)) {
                return;
            }

            boolean hasChemicalBath = false;
            for (PatternCandidate candidate : candidates) {
                if ("chemical_bath".equals(candidate.recipeMap.getUnlocalizedName())) {
                    hasChemicalBath = true;
                    break;
                }
            }
            if (!hasChemicalBath) return;

            Material material = getMaterialForKey(target);
            List<String> ranking = new ArrayList<>(candidates.size());
            for (PatternCandidate candidate : candidates) {
                int formCost = getSolidMaterialInputFormCost(target,
                        DynamicRecipePatternDetails.createScoringInputs(candidate.encoded.inputs,
                                candidate.encoded.alternatives));
                ranking.add(candidate.recipeMap.getUnlocalizedName() + "{form=" + formCost +
                        ", inputs=" + describeCandidateInputs(candidate) +
                        ", quick=" + quickCosts.get(candidate) +
                        (refinedCosts.containsKey(candidate) ? ", refined=" + refinedCosts.get(candidate) +
                                ", adaptiveBudget=" + refinedProgress.get(candidate) : "") +
                        (candidate == candidates.get(0) ? ", selected" : "") + '}');
            }
            ApplyGrayMod.LOGGER.info("RecipeMap chemical-product solid selection material={} prefix={} target={} " +
                            "stockOnly={} candidates={}",
                    material == null ? "unknown" : material.getName(), getOrePrefixForKey(target), target,
                    stockOnlySelection, ranking);
        }

        private static List<String> describeCandidateInputs(PatternCandidate candidate) {
            List<String> result = new ArrayList<>(candidate.encoded.inputs.size());
            for (GenericStack input : candidate.encoded.inputs) {
                result.add(RecipeFingerprint.describeKey(input.what()) + 'x' + input.amount());
            }
            return result;
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
            return materializePattern(target, candidate, candidate.encoded);
        }

        private DynamicRecipePatternDetails materializePattern(AEKey target, PatternCandidate candidate,
                                                               EncodedRecipe encoded) {
            if (!cooperateWithCraftingCalculation()) {
                return null;
            }

            OptimalRebuildContext optimalRebuild = getActiveOptimalRebuild();
            DynamicRecipePatternDetails detail = candidate.source.provider
                    .getCachedDynamicPattern(candidate.recipeKey);
            if (detail != null && !detail.matchesRecipeDefinition(candidate.recipeMap.getUnlocalizedName(),
                    encoded.inputs, encoded.alternatives, encoded.outputs,
                    encoded.circuitConfiguration, candidate.cost.rawMaterials, candidate.cost.steps,
                    candidate.cost.routePriority, candidate.cost.ruleRoutePriority,
                    candidate.decision.getCyclePolicy(), candidate.cost.cycleRiskPenalty,
                    candidate.decision.getMaxPatternsForTarget(), candidate.cost.planningMode,
                    candidate.decision.getPinGroup(), candidate.targeted.getHiddenActualOutputs(),
                    candidate.targeted.getBinding(), candidate.targeted.getTokenLayout())) {
                candidate.source.provider.removeCachedDynamicPattern(candidate.recipeKey);
                detail = null;
            }
            if (detail == null) {
                detail = createPatternDetail(candidate, encoded);
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

        private static DynamicRecipePatternDetails createPatternDetail(PatternCandidate candidate) {
            return createPatternDetail(candidate, candidate.encoded);
        }

        private static DynamicRecipePatternDetails createPatternDetail(PatternCandidate candidate,
                                                                        EncodedRecipe encoded) {
            return new DynamicRecipePatternDetails(candidate.recipeKey,
                    candidate.recipeMap.getUnlocalizedName(), encoded.inputs,
                    encoded.alternatives, encoded.outputs,
                    encoded.circuitConfiguration,
                    candidate.cost.rawMaterials, candidate.cost.steps, candidate.cost.routePriority,
                    candidate.cost.ruleRoutePriority, candidate.decision.getCyclePolicy(),
                    candidate.cost.cycleRiskPenalty, candidate.decision.getMaxPatternsForTarget(),
                    candidate.cost.planningMode, candidate.decision.getPinGroup(),
                    candidate.targeted.getHiddenActualOutputs(), candidate.targeted.getBinding(),
                    candidate.targeted.getTokenLayout(), candidate.targeted.getExplanation());
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
                // Only the explicitly armed rebuild calculation or the standalone generation task may consume a
                // pending full index refresh. Ordinary terminal lookups must keep the previous cache stable.
                if (calculation == null || !hasActiveOptimalRebuildRequest()) {
                    if (!Boolean.TRUE.equals(OPTIMAL_ROUTE_GENERATION.get())) {
                        return false;
                    }
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
                        if (isOptimalRebuildCalculation()) {
                            ACTIVE_OPTIMAL_REBUILD.set(new OptimalRebuildContext(scannedRecipeMaps, scannedRecipes,
                                    elapsedMillis, startedAt));
                        }
                        ApplyGrayMod.LOGGER.info("Fully rebuilt {} active RecipeMap output indexes from {} recipes " +
                                        "for ApplyGray optimal route generation in {} ms",
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
            RecipeBindingResolver.RecipeMapSnapshot bindingSnapshot = RecipeBindingResolver.snapshot(recipeMap);
            RecipeOutputIndex existing = recipeOutputIndexes.get(recipeMap);
            if (!forceRebuild && existing != null &&
                    existing.contentVersion.equals(bindingSnapshot.getContentVersion())) {
                return existing;
            }

            long startedAt = System.nanoTime();
            RecipeOutputIndex indexed = RecipeOutputIndex.create(bindingSnapshot);
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
                    result = current != null && current.contentVersion.equals(indexed.contentVersion) ? current : indexed;
                    if (current != null && !current.contentVersion.equals(indexed.contentVersion)) {
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
            // Static raw-material cost treats an intermediate compound as if it were a leaf. It may only eliminate
            // another provider for the same dependency shape; different input trees need recursive route scoring.
            if (!sameDependencyOptions(left.encoded.alternatives, right.encoded.alternatives)) return false;
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
            return true;
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
                if (dynamic != null && dynamic.netProduces(target) &&
                        patternsByRecipe.get(dynamic.getRecipeKey()) == dynamic) {
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

        private boolean isPatternAvailableFor(AEKey target, DynamicRecipePatternDetails detail) {
            if (!detail.netProduces(target) || isRejectedFor(target, detail)) return false;
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            return session == null || !session.matches(this) ||
                    !session.isRejected(target, detail.getRecipeKey(), detail.getInputs());
        }

        private boolean isRegisteredPatternAvailableFor(AEKey target, DynamicRecipePatternDetails detail) {
            ensureProviderCacheBindings();
            return patternsByRecipe.get(detail.getRecipeKey()) == detail && isPatternAvailableFor(target, detail);
        }

        private boolean isRejectedFor(AEKey target, DynamicRecipePatternDetails detail) {
            return isRejectedFor(target, detail.getRecipeKey());
        }

        private boolean isRejectedFor(AEKey target, String recipeKey) {
            Set<String> rejected = rejectedRecipeKeysByTarget.get(target);
            return rejected != null && rejected.contains(recipeKey);
        }

        private void clearGenerated() {
            clearGenerated(true);
        }

        private void clearGenerated(boolean invalidateBindingSnapshots) {
            patternsByTarget.clear();
            patternsByRecipe.clear();
            standaloneRecipeKeysByTarget.clear();
            rejectedRecipeKeysByTarget.clear();
            routeCandidateCache.clear();
            invalidateRecipeOutputIndexes(invalidateBindingSnapshots);
            // Weak ownership entries deliberately survive cache invalidation so an already-submitted CPU can finish.
            // Fresh crafting lookups only see patterns in the cleared indexes above.
        }

        /** Promotes only the exact transient detail objects selected by the completed AE2 plan. */
        private synchronized void commitTransientPlanPatterns(CraftingRecoverySession session, ICraftingPlan plan) {
            if (plan == null) return;
            int committed = 0;
            for (IPatternDetails pattern : plan.patternTimes().keySet()) {
                DynamicRecipePatternDetails detail = getDynamicPattern(pattern);
                ICraftingProvider provider = session.getTransientProvider(detail);
                if (!(provider instanceof MetaTileEntityMERecipeMapPatternProvider)) continue;

                ((MetaTileEntityMERecipeMapPatternProvider) provider).replaceCachedDynamicPattern(detail);
                patternsByRecipe.put(detail.getRecipeKey(), detail);
                providersByPattern.put(detail, provider);
                for (GenericStack output : detail.getOutputs()) {
                    patternsByTarget.compute(output.what(), (target, previous) -> appendMissingByKey(previous,
                            Collections.singletonList(detail), DynamicRecipePatternDetails::getRecipeKey));
                }
                committed++;
            }
            if (committed > 0 && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Committed {} selected task-local RecipeMap pattern(s) after AE2 planning",
                        committed);
            }
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
            invalidateRecipeOutputIndexes(true);
        }

        /**
         * Drops output indexes while optionally retaining a main-thread-prepared binding snapshot.
         *
         * <p>Lookup-only maps such as {@code electric_furnace} publish their Vanilla fallbacks through the resolver
         * before this state is invalidated. Rebuilding the index must retain that frozen snapshot; ordinary RecipeMap
         * mutations already remove their own resolver entry before reaching this method.</p>
         */
        private void invalidateRecipeOutputIndexes(boolean invalidateBindingSnapshots) {
            recipeOutputIndexEpoch++;
            recipeOutputIndexes.clear();
            if (invalidateBindingSnapshots) {
                RecipeBindingResolver.invalidateAll();
            }
            pendingFullRecipeOutputIndexEpoch = 0;
        }

        private synchronized int invalidateRecipeMapContents(RecipeMap<?> recipeMap) {
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
                int cachedPatternCount = snapshot.provider.getCachedDynamicPatterns().size();
                if (cachedPatternCount > 0) {
                    // A candidate from this map can replace a saved route from any other map exposed by the same
                    // controller. For example, adding the direct electric-furnace dust route must evict an older
                    // blast-furnace molten route for the same ingot target before it can be republished on reload.
                    snapshot.provider.clearCachedPatterns();
                    discarded += cachedPatternCount;
                }
            }
            if (!affected && !recipeOutputIndexes.containsKey(recipeMap)) return 0;
            clearGenerated(false);
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

        /** Fills only the cache entries affected by task-scoped cycle filters; existing entries remain untouched. */
        private void materializePendingRecoveryPatterns(CraftingRecoverySession recovery, Set<AEKey> targets) {
            if (recovery == null || !recovery.matches(this)) return;
            for (AEKey target : targets) {
                if (target == null || isRawMaterialLeaf(target)) continue;
                List<DynamicRecipePatternDetails> existing = patternsByTarget.get(target);
                if (hasAvailablePattern(target, existing)) {
                    recovery.recordCacheExpansion(target, existing.size(), 0);
                    continue;
                }
                List<DynamicRecipePatternDetails> generated = createPatterns(target);
                synchronized (this) {
                    List<DynamicRecipePatternDetails> current = patternsByTarget.get(target);
                    List<DynamicRecipePatternDetails> merged = appendMissingByKey(current, generated,
                            DynamicRecipePatternDetails::getRecipeKey);
                    int retained = current == null ? 0 : current.size();
                    int appended = merged.size() - retained;
                    if (appended > 0) patternsByTarget.put(target, merged);
                    recovery.recordCacheExpansion(target, retained, appended);
                }
            }
        }

        /** Re-selects only affected task-local outputs after AE2 proves one candidate cycle is non-productive. */
        private void refreshTransientPatterns(CraftingRecoverySession session, Set<AEKey> targets) {
            if (session == null || !session.matches(this)) return;
            for (AEKey target : targets) {
                if (target == null) continue;
                session.forgetTransientPatterns(target);
                createTransientPatterns(session, target);
            }
        }

        private boolean hasAvailablePattern(AEKey target, List<DynamicRecipePatternDetails> patterns) {
            if (patterns == null || patterns.isEmpty()) return false;
            for (DynamicRecipePatternDetails detail : patterns) {
                if (isPatternAvailableFor(target, detail)) return true;
            }
            return false;
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

    /** Coalesces repeated provider membership changes until the published cache is next observed. */
    static final class ProviderCacheRebuildGate {

        private boolean pending;

        boolean invalidate() {
            if (pending) return false;
            pending = true;
            return true;
        }

        boolean isPending() {
            return pending;
        }

        boolean beginRebuild() {
            if (!pending) return false;
            pending = false;
            return true;
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
        private final String contentVersion;

        private RecipeOutputIndex(int recipeCount, Map<AEKey, List<Recipe>> recipesByOutput,
                                  RecipeBindingResolver.RecipeMapSnapshot bindingSnapshot) {
            this.recipeCount = recipeCount;
            this.outputCount = recipesByOutput.size();
            this.recipesByOutput = recipesByOutput;
            this.bindingSnapshot = bindingSnapshot;
            this.contentVersion = bindingSnapshot.getContentVersion();
        }

        private static RecipeOutputIndex create(RecipeBindingResolver.RecipeMapSnapshot bindingSnapshot) {
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

    /**
     * Creates one route-cost scope. Standalone route ranking grows by fair grants under the shared tree deadline;
     * it has no fixed recursive-expansion ceiling. A stalled search is stopped by its unchanged frontier instead.
     */
    private static RouteCostBudget newRouteCostBudget() {
        CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
        if (isStandalonePatternGeneration() && session != null && session.isOptimalRebuild()) {
            return session.newStandaloneRouteCostBudget(getPlanningBudget());
        }
        return new RouteCostBudget(getPlanningBudget(), isStandalonePatternGeneration());
    }

    /** The explicit tree generator is a background task and may use its separately configured refinement budget. */
    private static boolean isStandalonePatternGeneration() {
        return Boolean.TRUE.equals(OPTIMAL_ROUTE_GENERATION.get());
    }

    private static int getRouteExpansionLimit(PlanningBudget budget) {
        PlanningBudget effectiveBudget = budget == null ? PlanningBudget.DEFAULT : budget;
        // This caps graph/tree nodes, not the recursive route-cost work assigned to one selected target.
        return isStandalonePatternGeneration() ?
                effectiveBudget.getMaxStandaloneRouteExpansionsPerCalculation() :
                effectiveBudget.getMaxRouteExpansionsPerCalculation();
    }

    /**
     * The standalone preview follows its own configured selected-tree capacity, not the ordinary crafting-search
     * budget. The AE2 decoder's node bound is a wire-protocol safety contract, so it remains the only fixed cap.
     */
    static int getPatternGenerationTreeNodeLimit(PlanningBudget budget) {
        PlanningBudget effectiveBudget = budget == null ? PlanningBudget.DEFAULT : budget;
        return Math.max(1, Math.min(CraftingTreeStackRegistry.MAX_TREE_NODES,
                effectiveBudget.getMaxStandaloneRouteExpansionsPerCalculation()));
    }

    private static long getRouteCalculationNanos(PlanningBudget budget) {
        PlanningBudget effectiveBudget = budget == null ? PlanningBudget.DEFAULT : budget;
        return isStandalonePatternGeneration() ?
                effectiveBudget.getMaxStandaloneRouteCalculationNanos() :
                effectiveBudget.getMaxRouteCalculationNanos();
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
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            String plannedRecipe = session == null ? null : session.findPlannedRecipe(target);
            if (plannedRecipe != null && normal.size() > 1) {
                normal.sort((left, right) -> {
                    boolean leftSelected = plannedRecipe.equals(RouteEdge.of(left).id);
                    boolean rightSelected = plannedRecipe.equals(RouteEdge.of(right).id);
                    return leftSelected == rightSelected ? 0 : (leftSelected ? -1 : 1);
                });
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

    /** Builds a bounded transport tree from the same dynamic details that the normal calculation will consume. */
    private static final class PatternGenerationTreeBuilder {

        private final GridState state;
        @Nullable private final IGrid grid;
        private final AEKey rootTarget;
        private final int nodeLimit;
        private final int depthLimit;
        private int nodes;
        private int dynamicProcesses;
        private int normalProcesses;
        private int rawLeaves;
        private int canonicalDustSeedLeaves;
        private int noPatternLeaves;
        private int depthLeaves;
        private int nodeLimitLeaves;
        private int cycleLeaves;
        private int verificationNodes;
        private boolean verificationLimited;
        private final Set<AEKey> sampledNoPatternLeaves = new HashSet<>();
        private final List<String> noPatternLeafSamples = new ArrayList<>();
        @Nullable private LiteCraftTreeNode root;
        private final List<CycleObservation> observedCycles = new ArrayList<>();
        private final Set<String> observedCycleSignatures = new HashSet<>();

        private PatternGenerationTreeBuilder(GridState state, @Nullable IGrid grid, AEKey rootTarget,
                                             int nodeLimit, int depthLimit) {
            this.state = state;
            this.grid = grid;
            this.rootTarget = rootTarget;
            this.nodeLimit = Math.max(1, nodeLimit);
            this.depthLimit = Math.max(1, depthLimit);
        }

        private LiteCraftTreeNode build(AEKey target, long amount, @Nullable LiteCraftTreeProc parent,
                                        int depth, Set<AEKey> path, List<TreeRouteStep> routePath) {
            long requestedAmount = Math.max(1, amount);
            if (nodes >= nodeLimit) {
                nodeLimitLeaves++;
                return leaf(target, requestedAmount, parent);
            }
            nodes++;
            if (depth >= depthLimit) {
                depthLeaves++;
                return leaf(target, requestedAmount, parent);
            }
            if (!path.add(target)) {
                cycleLeaves++;
                observeCycle(target, routePath);
                return leaf(target, requestedAmount, parent);
            }

            int routePathSize = routePath.size();
            try {
                if (isRawMaterialLeaf(target)) {
                    rawLeaves++;
                    return leaf(target, requestedAmount, parent);
                }

                IPatternDetails detail = firstPattern(target);
                if (detail == null) {
                    noPatternLeaves++;
                    recordNoPatternLeaf(target, "NO_SELECTED_DYNAMIC_OR_NORMAL_PATTERN");
                    return leaf(target, requestedAmount, parent);
                }

                RouteEdge edge = RouteEdge.of(detail);
                long outputPerCraft = edge.getNetOutput(target);
                if (outputPerCraft <= 0) {
                    noPatternLeaves++;
                    recordNoPatternLeaf(target, "NON_POSITIVE_PATTERN_OUTPUT");
                    return leaf(target, requestedAmount, parent);
                }
                if (getDynamicPattern(detail) == null) {
                    normalProcesses++;
                } else {
                    dynamicProcesses++;
                }
                routePath.add(new TreeRouteStep(target, detail));
                long crafts = divideRoundUp(requestedAmount, outputPerCraft);
                List<LiteCraftTreeNode> inputs = new ArrayList<>();
                LiteCraftTreeProc process = new LiteCraftTreeProc(inputs, List.of());
                boolean canonicalDustToIngot = isCanonicalSameMaterialDustToIngotTransition(target, edge);
                for (IPatternDetails.IInput input : detail.getInputs()) {
                    GenericStack choice = firstInputChoice(input);
                    if (choice == null) continue;
                    long inputAmount = multiplySaturated(
                            multiplySaturated(choice.amount(), input.getMultiplier()), crafts);
                    if (inputAmount > 0) {
                        if (canonicalDustToIngot && isCanonicalSameMaterialDustInput(target, choice.what())) {
                            canonicalDustSeedLeaves++;
                            inputs.add(leaf(choice.what(), inputAmount, process));
                        } else {
                            inputs.add(build(choice.what(), inputAmount, process, depth + 1, path, routePath));
                        }
                    }
                }

                return new LiteCraftTreeNode(parent, new GenericStack(target, requestedAmount),
                        inputs.isEmpty() ? List.of() : List.of(process), 0);
            } finally {
                while (routePath.size() > routePathSize) {
                    routePath.remove(routePath.size() - 1);
                }
                path.remove(target);
            }
        }

        private void observeCycle(AEKey repeatedTarget, List<TreeRouteStep> routePath) {
            int start = -1;
            for (int index = 0; index < routePath.size(); index++) {
                if (routePath.get(index).target.equals(repeatedTarget)) {
                    start = index;
                    break;
                }
            }
            if (start < 0) return;

            List<AEKey> targets = new ArrayList<>(routePath.size() - start);
            List<IPatternDetails> patterns = new ArrayList<>(routePath.size() - start);
            StringBuilder signature = new StringBuilder(RecipeFingerprint.describeKey(repeatedTarget));
            for (int index = start; index < routePath.size(); index++) {
                TreeRouteStep step = routePath.get(index);
                targets.add(step.target);
                patterns.add(step.pattern);
                signature.append('>').append(RecipeFingerprint.describeKey(step.target));
                DynamicRecipePatternDetails dynamic = getDynamicPattern(step.pattern);
                if (dynamic != null) signature.append('#').append(dynamic.getRecipeKey());
            }
            if (observedCycleSignatures.add(signature.toString())) {
                observedCycles.add(new CycleObservation(repeatedTarget, targets, patterns));
            }
        }

        private boolean rejectObservedDynamicCycles() {
            if (observedCycles.isEmpty()) return false;
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session == null || !session.matches(state)) return false;

            int rejected = 0;
            for (CycleObservation cycle : observedCycles) {
                rejected += session.rejectCycle(state, cycle.repeatedTarget, cycle.targets, cycle.patterns);
            }
            return rejected > 0;
        }

        /**
         * Validates the route graph owned by this generator before persistence. Ordinary AE2 patterns are boundaries:
         * their input substitutions and cycle handling remain AE2's responsibility, and must never cause this
         * generator to discard an otherwise valid frozen RecipeMap route.
         */
        private void verifyFrozenDynamicRoutes() {
            verificationNodes = 0;
            verificationLimited = false;
            verifyFrozenDynamicRoute(rootTarget, 0, new HashSet<>(), new ArrayList<>());
            if (verificationLimited) {
                ApplyGrayMod.LOGGER.warn("RecipeMap frozen dynamic-route verification reached its node budget root={} " +
                                "visited={} limit={}; generation will retain conservative route safety filters",
                        rootTarget, verificationNodes, verificationNodeLimit());
            }
        }

        private void verifyFrozenDynamicRoute(AEKey target, int depth, Set<AEKey> path,
                                              List<TreeRouteStep> routePath) {
            if (verificationLimited || target == null || depth >= depthLimit || isRawMaterialLeaf(target)) return;
            if (++verificationNodes > verificationNodeLimit()) {
                verificationLimited = true;
                return;
            }
            if (!path.add(target)) {
                observeCycle(target, routePath);
                return;
            }

            int routePathSize = routePath.size();
            try {
                IPatternDetails detail = firstPattern(target);
                DynamicRecipePatternDetails dynamic = getDynamicPattern(detail);
                if (dynamic == null) return;
                routePath.add(new TreeRouteStep(target, detail));
                boolean canonicalDustToIngot = isCanonicalSameMaterialDustToIngotTransition(target,
                        RouteEdge.of(detail));
                for (IPatternDetails.IInput input : detail.getInputs()) {
                    for (GenericStack option : input.possibleInputs()) {
                        if (option != null && option.amount() > 0) {
                            if (canonicalDustToIngot && isCanonicalSameMaterialDustInput(target, option.what())) {
                                continue;
                            }
                            verifyFrozenDynamicRoute(option.what(), depth + 1, path, routePath);
                        }
                    }
                }
            } finally {
                while (routePath.size() > routePathSize) {
                    routePath.remove(routePath.size() - 1);
                }
                path.remove(target);
            }
        }

        private int verificationNodeLimit() {
            return Math.max(nodeLimit, Math.min(4096, nodeLimit * 4));
        }

        private void setRoot(LiteCraftTreeNode root) {
            this.root = root;
        }

        @Nullable
        private LiteCraftTreeNode getRoot() {
            return root;
        }

        @Nullable
        private IPatternDetails firstPattern(AEKey target) {
            for (IPatternDetails detail : state.findPatterns(target)) {
                DynamicRecipePatternDetails dynamic = getDynamicPattern(detail);
                if (dynamic != null && dynamic.getNetOutputAmount(target) > 0) {
                    return dynamic;
                }
            }
            if (grid == null) return null;
            for (IPatternDetails detail : getNormalPatternsForRouteCost(grid, target)) {
                if (RouteEdge.of(detail).getNetOutput(target) > 0) return detail;
            }
            return null;
        }

        private void logSummary() {
            ApplyGrayMod.LOGGER.info("RecipeMap standalone pattern tree root={} nodes={} dynamicProcesses={} " +
                            "normalProcesses={} leaves[raw={}, canonicalDustSeed={}, noPattern={}, depth={}, " +
                            "nodeLimit={}, cycle={}]",
                    rootTarget, nodes, dynamicProcesses, normalProcesses, rawLeaves, canonicalDustSeedLeaves,
                    noPatternLeaves, depthLeaves, nodeLimitLeaves, cycleLeaves);
            if (!noPatternLeafSamples.isEmpty()) {
                ApplyGrayMod.LOGGER.info("RecipeMap standalone unresolved leaf samples root={} samples={}",
                        rootTarget, noPatternLeafSamples);
            }
        }

        /** Captures the selected-tree state once per unresolved key without issuing another recipe lookup. */
        private void recordNoPatternLeaf(AEKey target, String reason) {
            if (target == null || noPatternLeafSamples.size() >= MAX_PATTERN_GENERATION_NO_PATTERN_LEAF_SAMPLES ||
                    !sampledNoPatternLeaves.add(target)) {
                return;
            }

            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            List<DynamicRecipePatternDetails> selected = session != null && session.matches(state) ?
                    session.findTransientPatterns(target) : null;
            List<PatternCandidate> candidates = session != null && session.matches(state) ?
                    session.findRouteCandidates(target) : null;
            Material material = getMaterialForKey(target);
            String selectionState = selected == null ? "NOT_MATERIALIZED" :
                    (selected.isEmpty() ? "NO_AVAILABLE_SELECTED_PATTERN" : "SELECTED_PATTERN_UNAVAILABLE");
            noPatternLeafSamples.add("{key=" + RecipeFingerprint.describeKey(target) +
                    ", material=" + (material == null ? "unknown" : material.getName()) +
                    ", prefix=" + getOrePrefixForKey(target) +
                    ", selection=" + selectionState +
                    ", candidates=" + (candidates == null ? "unvisited" : candidates.size()) +
                    ", maps=" + describeNoPatternCandidateMaps(candidates) +
                    ", reason=" + reason + '}');
        }

        private static String describeNoPatternCandidateMaps(@Nullable List<PatternCandidate> candidates) {
            if (candidates == null || candidates.isEmpty()) return "[]";
            List<String> recipeMaps = new ArrayList<>(candidates.size());
            for (PatternCandidate candidate : candidates) {
                recipeMaps.add(candidate.recipeMap.getUnlocalizedName());
            }
            return recipeMaps.toString();
        }

        @Nullable
        private static GenericStack firstInputChoice(IPatternDetails.IInput input) {
            for (GenericStack choice : input.possibleInputs()) {
                if (choice != null && choice.amount() > 0) return choice;
            }
            return null;
        }

        private static LiteCraftTreeNode leaf(AEKey target, long amount, @Nullable LiteCraftTreeProc parent) {
            return new LiteCraftTreeNode(parent, new GenericStack(target, amount), List.of(), 0);
        }

        private record TreeRouteStep(AEKey target, IPatternDetails pattern) {
        }

        private record CycleObservation(AEKey repeatedTarget, List<AEKey> targets,
                                        List<IPatternDetails> patterns) {
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
        private int currentRootExpansionQuota;
        private boolean currentRootQuotaLimited;
        private RouteScoringProgress lastRootScoringProgress = RouteScoringProgress.EMPTY;
        private int totalExpansions;
        private int normalPatternEdges;
        private int dynamicPatternEdges;
        private int boundedFallbacks;
        private boolean selectedPlanIncomplete;
        private String lastPlannerReason = "OK";
        private final Map<String, AndOrRoutePlanner.Result<AEKey>> plansByRootEdge = new HashMap<>();
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
            return selectedPlanIncomplete || budget.isExhausted() ||
                    cycleAnalysis != null && !cycleAnalysis.isComplete();
        }

        private boolean isCycleAnalysisComplete() {
            return cycleAnalysis != null && cycleAnalysis.isComplete();
        }

        private String getCycleAnalysisReason() {
            return cycleAnalysis == null ? "NOT_RUN" : cycleAnalysis.getBudgetReason();
        }

        private String getIncompleteReason() {
            if (selectedPlanIncomplete) return lastPlannerReason;
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

        private DirectRouteCost estimateDirect(IPatternDetails pattern, AEKey target) {
            return estimateDirect(RouteEdge.of(pattern), target);
        }

        private DirectRouteCost estimateDirect(PatternCandidate candidate, AEKey target) {
            return estimateDirect(RouteEdge.of(candidate), target);
        }

        private DirectRouteCost estimateDirect(RouteEdge edge, AEKey target) {
            InventoryLedger ledger = new InventoryLedger(inventory);
            DirectRouteCost total = DirectRouteCost.materialFormConversions(
                    getSolidMaterialInputFormCost(target, edge.inputs));
            boolean canonicalDustToIngot = isCanonicalSameMaterialDustToIngotTransition(target, edge);
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
                    boolean materialSeed = canonicalDustToIngot &&
                            isCanonicalSameMaterialDustInput(target, option.what());
                    DirectRouteCost optionCost = DirectRouteCost.input(option.what(), fromStock, remaining,
                            normalPattern, materialSeed || isRawMaterialLeaf(option.what()),
                            isNonConsumableControlToken(option.what()));
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

        /** Scores one root with an explicit local quota so progressive refinement can replay it at greater depth. */
        private RouteCost estimateRootWithQuota(IPatternDetails pattern, AEKey target, int rootExpansionQuota) {
            return estimateRootWithQuota(RouteEdge.of(pattern), target, rootExpansionQuota);
        }

        /** Scores one root with an explicit local quota so progressive refinement can replay it at greater depth. */
        private RouteCost estimateRootWithQuota(PatternCandidate candidate, AEKey target, int rootExpansionQuota) {
            return estimateRootWithQuota(RouteEdge.of(candidate), target, rootExpansionQuota);
        }

        /**
         * Replays the selected route's root input decisions against the same bounded cost model used for ranking.
         * Only the root inputs are frozen here; each reachable dynamic child receives its own pass when the selected
         * generation tree is materialized.
         */
        private EncodedRecipe freezeRootInputs(PatternCandidate candidate, AEKey target) {
            RouteEdge edge = RouteEdge.of(candidate);
            int quota = Math.min(budget.getRemainingExpansions(), getLimits().getMaxRouteExpansionsPerTarget());
            AndOrRoutePlanner.Result<AEKey> plan = planRoot(edge, target, Math.max(1, quota));
            List<GenericStack> choices = resolveRootChoices(edge, plan.rootInputChoices());
            if (choices.size() != edge.inputs.length) {
                ApplyGrayMod.LOGGER.warn("Recipe-pattern planner could not freeze every root input target={} " +
                                "recipe={} reasonCode={} selectedInputs={}/{}",
                        target, edge.id, plan.reasonCode(), choices.size(), edge.inputs.length);
                return candidate.encoded;
            }
            return candidate.encoded.withFrozenInputChoices(choices);
        }

        private List<GenericStack> resolveRootChoices(RouteEdge edge,
                List<AndOrRoutePlanner.Amount<AEKey>> plannedChoices) {
            List<GenericStack> resolved = new ArrayList<>(edge.inputs.length);
            for (int inputIndex = 0; inputIndex < edge.inputs.length; inputIndex++) {
                IPatternDetails.IInput input = edge.inputs[inputIndex];
                AndOrRoutePlanner.Amount<AEKey> planned = inputIndex < plannedChoices.size() ?
                        plannedChoices.get(inputIndex) : null;
                GenericStack selected = null;
                if (planned != null) {
                    for (GenericStack option : input.possibleInputs()) {
                        if (option != null && option.what().equals(planned.key()) &&
                                multiplySaturated(option.amount(), input.getMultiplier()) == planned.amount()) {
                            selected = option;
                            break;
                        }
                    }
                }
                if (selected == null) {
                    List<GenericStack> fallback = new ArrayList<>();
                    for (GenericStack option : input.possibleInputs()) {
                        if (option != null && option.amount() > 0) fallback.add(option);
                    }
                    fallback.sort((left, right) -> {
                        int key = RecipeFingerprint.describeKey(left.what())
                                .compareTo(RecipeFingerprint.describeKey(right.what()));
                        return key != 0 ? key : Long.compare(left.amount(), right.amount());
                    });
                    if (fallback.isEmpty()) return Collections.emptyList();
                    selected = fallback.get(0);
                }
                resolved.add(selected);
            }
            return resolved;
        }

        /**
         * Freezes alternatives for an already-selected standalone route without opening another recursive candidate
         * search. Shared stock is still consumed in input order, so two frozen inputs cannot both claim the same
         * stored stack.
         */
        private EncodedRecipe freezeDirectInputs(PatternCandidate candidate, AEKey target) {
            RouteEdge edge = RouteEdge.of(candidate);
            InventoryLedger ledger = new InventoryLedger(inventory);
            List<GenericStack> choices = new ArrayList<>(edge.inputs.length);
            boolean canonicalDustToIngot = isCanonicalSameMaterialDustToIngotTransition(target, edge);
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack selected = selectDirectInputChoice(target, canonicalDustToIngot, input, ledger);
                if (selected == null) return candidate.encoded;
                choices.add(selected);
            }
            return candidate.encoded.withFrozenInputChoices(choices);
        }

        @Nullable
        private GenericStack selectDirectInputChoice(AEKey output, boolean canonicalDustToIngot,
                                                     IPatternDetails.IInput input, InventoryLedger ledger) {
            GenericStack[] options = input.possibleInputs();
            int optionLimit = Math.min(options.length, getLimits().getMaxInputAlternatives());
            DirectRouteChoice best = null;
            GenericStack selected = null;
            for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                GenericStack option = options[optionIndex];
                if (option == null || option.amount() <= 0) continue;

                long required = multiplySaturated(option.amount(), input.getMultiplier());
                InventoryLedger branch = ledger.copy();
                long fromStock = branch.consume(option.what(), required);
                long remaining = required - fromStock;
                boolean normalPattern = remaining > 0 && !getNormalEdges(option.what()).isEmpty();
                boolean materialSeed = canonicalDustToIngot &&
                        isCanonicalSameMaterialDustInput(output, option.what());
                DirectRouteCost cost = DirectRouteCost.input(option.what(), fromStock, remaining, normalPattern,
                        materialSeed || isRawMaterialLeaf(option.what()), isNonConsumableControlToken(option.what()));
                if (best == null || cost.compareTo(best.cost) < 0) {
                    best = new DirectRouteChoice(cost, branch);
                    selected = option;
                }
            }
            if (best != null) ledger.replaceWith(best.ledger);
            return selected;
        }

        private RouteCost estimateRootWithQuota(RouteEdge edge, AEKey target, int rootExpansionQuota) {
            AndOrRoutePlanner.Result<AEKey> plan = planRoot(edge, target, rootExpansionQuota);
            RouteCost result = toRouteCost(plan.cost());
            lastRootScoringProgress = new RouteScoringProgress(currentRootExpansions, currentRootExpansionQuota,
                    currentRootQuotaLimited, budget.getRemainingExpansions(), budget.getMaxExpansions());
            if (rejectedCycleEdges > 0 && ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Recipe-pattern SCC guard rejected {} cycle edge(s) target={} " +
                                "reasonCode=CYCLE_NO_EXTERNAL_SEED",
                        rejectedCycleEdges, target);
            }
            if (budget.isExhausted() && !budget.isSessionManagedStandaloneBudget() && !loggedRouteCostBudget) {
                loggedRouteCostBudget = true;
                ApplyGrayMod.LOGGER.warn("Recipe-pattern route scoring stopped target={} reasonCode=BUDGET_EXHAUSTED " +
                                "budgetReason={} expansions={}",
                        target, budget.getExhaustionReason(), budget.getExpansions());
            }
            return result;
        }

        private AndOrRoutePlanner.Result<AEKey> planRoot(RouteEdge edge, AEKey target, int rootExpansionQuota) {
            beginRootScoringWithQuota(rootExpansionQuota);
            rejectedCycleEdges = 0;
            prepareCycleAnalysis(target);
            AndOrRoutePlanner<AEKey> planner = new AndOrRoutePlanner<>();
            AndOrRoutePlanner.Graph<AEKey> graph = new AndOrRoutePlanner.Graph<>() {

                @Override
                public long getAvailable(AEKey key) {
                    return inventory.get(key);
                }

                @Override
                public boolean isLeaf(AEKey key) {
                    return isRawMaterialLeaf(key);
                }

                @Override
                public boolean isFree(AEKey key) {
                    return isNonConsumableControlToken(key);
                }

                @Override
                public long estimateMaterialCost(AEKey key, long amount) {
                    return estimateKeyMaterialAmount(key, amount);
                }

                @Override
                public List<AndOrRoutePlanner.Edge<AEKey>> getEdges(AEKey key, int depth) {
                    List<AndOrRoutePlanner.Edge<AEKey>> planned = new ArrayList<>();
                    for (RouteEdge candidate : RouteCostEstimator.this.getEdges(key)) {
                        if (cycleAnalysis != null && cycleAnalysis.rejects(key, candidate, depth)) {
                            rejectedCycleEdges++;
                            continue;
                        }
                        planned.add(toPlannerEdge(candidate, key));
                    }
                    return planned;
                }

                @Override
                public String stableKey(AEKey key) {
                    return RecipeFingerprint.describeKey(key);
                }

                @Override
                public boolean reserveExpansion() {
                    if (currentRootExpansionQuota > 0 && currentRootExpansions >= currentRootExpansionQuota) {
                        currentRootQuotaLimited = true;
                        return false;
                    }
                    if (!budget.tryExpansion()) return false;
                    currentRootExpansions++;
                    totalExpansions++;
                    return true;
                }

                @Override
                public boolean shouldContinue() {
                    return budget.hasRemainingTime() && GridState.cooperateWithCraftingCalculation();
                }
            };

            AndOrRoutePlanner.Edge<AEKey> root = toPlannerEdge(edge, target);
            AndOrRoutePlanner.Result<AEKey> result = planner.plan(root, target, graph,
                    new AndOrRoutePlanner.Limits(getLimits().getMaxPlannerStatesPerTarget(),
                            getLimits().getMaxRouteDepth(),
                            getLimits().getMaxInputAlternatives()));
            PLANNING_METRICS.recordPlannerResult(result.isComplete(), result.expansions());
            plansByRootEdge.put(edge.id, result);
            lastPlannerReason = result.reasonCode();
            if (!result.isComplete()) {
                boundedFallbacks++;
            }
            if (ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Recipe-pattern AND/OR plan target={} root={} status={} reasonCode={} " +
                                "expansions={} cost={} selectedEdges={}",
                        target, edge.id, result.status(), result.reasonCode(), result.expansions(), result.cost(),
                        result.selectedEdges());
            }
            return result;
        }

        private void commitSelectedPlan(PatternCandidate selected, AEKey target) {
            RouteEdge edge = RouteEdge.of(selected);
            AndOrRoutePlanner.Result<AEKey> plan = plansByRootEdge.get(edge.id);
            if (plan == null) {
                int quota = Math.min(budget.getRemainingExpansions(),
                        getLimits().getMaxRouteExpansionsPerTarget());
                plan = planRoot(edge, target, Math.max(1, quota));
            }
            selectedPlanIncomplete = !plan.isComplete();
            lastPlannerReason = plan.reasonCode();
            if (!plan.isComplete()) return;
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session != null && session.matches(state)) {
                session.rememberPlannerRoutes(plan.selectedRoutes());
            }
        }

        private AndOrRoutePlanner.Edge<AEKey> toPlannerEdge(RouteEdge edge, AEKey output) {
            List<AndOrRoutePlanner.Input<AEKey>> inputs = new ArrayList<>(edge.inputs.length);
            for (IPatternDetails.IInput input : edge.inputs) {
                List<AndOrRoutePlanner.Amount<AEKey>> alternatives = new ArrayList<>();
                for (GenericStack option : input.possibleInputs()) {
                    if (option == null || option.amount() <= 0) continue;
                    alternatives.add(new AndOrRoutePlanner.Amount<>(option.what(),
                            multiplySaturated(option.amount(), input.getMultiplier())));
                }
                inputs.add(new AndOrRoutePlanner.Input<>(alternatives));
            }
            List<AndOrRoutePlanner.Amount<AEKey>> outputs = new ArrayList<>(edge.outputs.size());
            for (GenericStack result : edge.outputs) {
                if (result != null && result.amount() > 0) {
                    outputs.add(new AndOrRoutePlanner.Amount<>(result.what(), result.amount()));
                }
            }
            long cyclePenalty = cycleAnalysis == null ? 0 : cycleAnalysis.getPenalty(output, edge);
            return new AndOrRoutePlanner.Edge<>(edge.id, inputs, outputs, 1, cyclePenalty,
                    getSolidMaterialInputFormCost(output, edge.inputs));
        }

        private static RouteCost toRouteCost(AndOrRoutePlanner.Cost cost) {
            return new RouteCost(cost.missingMaterials(), cost.maxDepth(), cost.executions(),
                    cost.consumedStockMaterials(), cost.boundedFallbacks(), cost.unresolvedIntermediates(),
                    cost.cycleRisk(), cost.materialFormConversions());
        }

        private void prepareCycleAnalysis(AEKey target) {
            if (cycleAnalysis != null) return;
            CraftingRecoverySession session = CRAFTING_RECOVERY_SESSION.get();
            if (session != null && session.matches(state)) {
                cycleAnalysis = session.findReusableCycleAnalysis(target);
            }
            if (cycleAnalysis == null) {
                cycleAnalysis = RouteCycleAnalysis.analyze(target, this);
                if (cycleAnalysis.isComplete() && session != null && session.matches(state)) {
                    session.rememberCycleAnalysis(cycleAnalysis);
                }
            }
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

        private void beginRootScoringWithQuota(int quota) {
            currentRootExpansions = 0;
            currentRootQuotaLimited = false;
            currentRootExpansionQuota = Math.max(0, quota);
        }

        private RouteScoringProgress getLastRootScoringProgress() {
            return lastRootScoringProgress;
        }

        private int getRemainingExpansions() {
            return budget.getRemainingExpansions();
        }

        private int getFairExpansionAllowance(int participantCount) {
            return budget.getFairExpansionAllowance(participantCount);
        }

        private boolean hasRemainingTime() {
            return budget.hasRemainingTime();
        }

        private void recordStalledRefinement() {
            budget.recordStalledRefinement();
        }

        private boolean isBudgetExhausted() {
            return budget.isExhausted();
        }

        /** Uses the same normal/dynamic pattern universe that the CraftingService mixin can expose for this output. */
        private List<RouteEdge> getCycleEdges(AEKey target) {
            List<IPatternDetails> normal = getNormalEdges(target);
            List<PatternCandidate> dynamic = state.getCandidatesForRouteCost(target);
            if (normal.isEmpty()) {
                List<RouteEdge> result = new ArrayList<>(dynamic.size());
                for (PatternCandidate candidate : dynamic) {
                    result.add(RouteEdge.of(candidate));
                }
                return result;
            }
            if (!areOnlyMaterialFormChanges(target, normal) || !containsDirectMaterialSourceCandidate(dynamic)) {
                List<RouteEdge> result = new ArrayList<>(normal.size());
                for (IPatternDetails pattern : normal) {
                    result.add(RouteEdge.of(pattern));
                }
                return result;
            }

            List<RouteEdge> result = new ArrayList<>(normal.size() + dynamic.size());
            for (PatternCandidate candidate : dynamic) {
                result.add(RouteEdge.of(candidate));
            }
            for (IPatternDetails pattern : normal) {
                result.add(RouteEdge.of(pattern));
            }
            return result;
        }

        private static boolean containsDirectMaterialSourceCandidate(List<PatternCandidate> candidates) {
            for (PatternCandidate candidate : candidates) {
                CandidateRoutePriority priority = candidate.cost.routePriority;
                if (priority == CandidateRoutePriority.CHEMICAL_PRODUCT_SYNTHESIS ||
                        priority == CandidateRoutePriority.DUST_OR_FLUID_INPUT ||
                        priority == CandidateRoutePriority.INGOT_INPUT) {
                    return true;
                }
            }
            return false;
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

        private final String id;
        private final IPatternDetails.IInput[] inputs;
        private final List<GenericStack> outputs;
        @Nullable private final CandidateRoutePriority routePriority;
        private final CyclePolicy cyclePolicy;
        private final long cycleRiskPenalty;
        private final boolean normalPattern;

        private RouteEdge(String id, IPatternDetails.IInput[] inputs, List<GenericStack> outputs,
                          @Nullable CandidateRoutePriority routePriority, CyclePolicy cyclePolicy,
                          long cycleRiskPenalty, boolean normalPattern) {
            this.id = id;
            this.inputs = inputs;
            this.outputs = outputs;
            this.routePriority = routePriority;
            this.cyclePolicy = cyclePolicy == null ? CyclePolicy.BREAK_AT_EXTERNAL_SEED : cyclePolicy;
            this.cycleRiskPenalty = Math.max(0, cycleRiskPenalty);
            this.normalPattern = normalPattern;
        }

        private static RouteEdge of(IPatternDetails pattern) {
            DynamicRecipePatternDetails dynamic = getDynamicPattern(pattern);
            return new RouteEdge(dynamic == null ? stableNormalPatternId(pattern) : dynamic.getRecipeKey(),
                    pattern.getInputs(), pattern.getOutputs(),
                    dynamic == null ? null : dynamic.getRoutePriority(),
                    dynamic == null ? CyclePolicy.EXTERNAL_SEED : dynamic.getCyclePolicy(),
                    dynamic == null ? 0 : dynamic.getCycleRiskPenalty(), dynamic == null);
        }

        private static RouteEdge of(PatternCandidate candidate) {
            return new RouteEdge(candidate.recipeKey,
                    DynamicRecipePatternDetails.createScoringInputs(candidate.encoded.inputs,
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

        private static String stableNormalPatternId(IPatternDetails pattern) {
            List<String> parts = new ArrayList<>();
            for (GenericStack output : pattern.getOutputs()) {
                if (output != null) {
                    parts.add("o:" + RecipeFingerprint.describeKey(output.what()) + ':' + output.amount());
                }
            }
            for (IPatternDetails.IInput input : pattern.getInputs()) {
                List<String> alternatives = new ArrayList<>();
                for (GenericStack option : input.possibleInputs()) {
                    if (option != null) {
                        alternatives.add(RecipeFingerprint.describeKey(option.what()) + ':' + option.amount() +
                                'x' + input.getMultiplier());
                    }
                }
                Collections.sort(alternatives);
                parts.add("i:" + alternatives);
            }
            Collections.sort(parts);
            return "ae2:" + RecipeFingerprint.sha256(parts.toString());
        }
    }

    /**
     * A bounded Tarjan pass over the target material's dynamic dependency graph. Dependencies outside that material
     * are boundary seeds: recursive route scoring compares their complete synthesis trees and its path guard catches
     * cross-material loops. Static AE patterns, stock, and explicitly tagged seed edges also terminate graph expansion.
     * The result is deliberately immutable after analysis so recursive route scoring can consult it without touching
     * world state.
     */
    private static final class RouteCycleAnalysis {

        private final RouteCostEstimator estimator;
        private final AEKey root;
        @Nullable private final Material rootMaterial;
        private final Map<AEKey, GraphNode> nodes = new HashMap<>();
        private final Deque<GraphNode> tarjanStack = new ArrayDeque<>();
        private final long deadlineNanos;
        private boolean complete = true;
        private String budgetReason = "OK";
        private int edgeCount;
        private int nextTarjanIndex;

        private RouteCycleAnalysis(AEKey root, RouteCostEstimator estimator) {
            this.estimator = estimator;
            this.root = root;
            this.rootMaterial = getMaterialForKey(root);
            this.deadlineNanos = System.nanoTime() + estimator.getLimits().getMaxSccAnalysisNanos();
        }

        private static RouteCycleAnalysis analyze(AEKey root, RouteCostEstimator estimator) {
            RouteCycleAnalysis analysis = new RouteCycleAnalysis(root, estimator);
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
            if (isRouteDependencyLeaf(key)) {
                node.directSeed = true;
                return;
            }
            if (estimator.inventory.get(key) > 0) {
                node.directSeed = true;
                return;
            }

            List<RouteEdge> edges = estimator.getCycleEdges(key);
            node.edges = edges;
            if (edges.isEmpty()) {
                // AE2 can report a terminal dependency as missing; it does not need another crafting edge to start.
                node.directSeed = true;
                return;
            }
            for (RouteEdge edge : edges) {
                if (!reserveEdge()) return;
                if (terminatesCycleGraph(false, edge.normalPattern, edge.cyclePolicy)) {
                    node.directSeed = true;
                    continue;
                }

                boolean canonicalDustToIngot = isCanonicalSameMaterialDustToIngotTransition(key, edge);
                for (IPatternDetails.IInput input : edge.inputs) {
                    GenericStack[] options = input.possibleInputs();
                    int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                    for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                        GenericStack option = options[optionIndex];
                        if (option == null || option.amount() <= 0) continue;
                        AEKey dependency = option.what();
                        if (canonicalDustToIngot && isCanonicalSameMaterialDustInput(key, dependency)) {
                            // The powder is the physical material seed for this direct furnace transition, rather
                            // than a route to follow back through the same material's conversion graph.
                            continue;
                        }
                        node.dependencies.add(dependency);
                        if (isCycleScopeDependency(root.equals(dependency), rootMaterial != null &&
                                rootMaterial.equals(getMaterialForKey(dependency)))) {
                            collect(dependency);
                        } else {
                            collectBoundarySeed(dependency);
                        }
                        if (!complete) return;
                    }
                }
            }
        }

        private void collectBoundarySeed(AEKey key) {
            if (!complete || key == null || nodes.containsKey(key)) return;
            if (!reserveNode()) return;
            GraphNode boundary = new GraphNode(key);
            boundary.directSeed = true;
            nodes.put(key, boundary);
        }

        private boolean rejects(AEKey output, RouteEdge edge, int depth) {
            if (!complete || edge.normalPattern || edge.cyclePolicy == CyclePolicy.EXTERNAL_SEED) return false;
            if (edge.cyclePolicy == CyclePolicy.FORBID) return true;
            if (edge.cyclePolicy == CyclePolicy.RECYCLE_ONLY && depth > 0) return true;
            if (isCanonicalSameMaterialDustToIngotTransition(output, edge) &&
                    edge.cyclePolicy != CyclePolicy.BREAKABLE) {
                return false;
            }

            GraphNode node = nodes.get(output);
            boolean cyclic = node != null && node.component != null && node.component.cyclic ||
                    closesCycle(output, edge);
            if (!cyclic) return false;
            // BREAKABLE edges are the explicit, data-driven cycle cut. Keeping one merely because another edge in
            // the component reaches a seed would allow the same dynamic loop to reappear through route scoring.
            if (edge.cyclePolicy == CyclePolicy.BREAKABLE) return true;
            boolean reachesSeed = node != null && node.component != null &&
                    canReachSeed(node.component, new HashSet<>());
            if (!reachesSeed && !canReachSeedThroughEdge(edge, node == null ? null : node.component)) return true;
            return edge.cyclePolicy == CyclePolicy.ALLOW_NET_POSITIVE &&
                    (edge.getNetOutput(output) <= 0 || !hasImmediateSeedInput(edge,
                            node == null ? null : node.component));
        }

        private long getPenalty(AEKey output, RouteEdge edge) {
            if (!complete || edge.cyclePolicy != CyclePolicy.PENALIZE) return 0;
            GraphNode node = nodes.get(output);
            return node != null && node.component != null && node.component.cyclic ? edge.cycleRiskPenalty : 0;
        }

        private boolean hasImmediateSeedInput(RouteEdge edge, @Nullable Component component) {
            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;
                    long required = multiplySaturated(option.amount(), input.getMultiplier());
                    if (estimator.inventory.get(option.what()) >= required) return true;
                    GraphNode dependency = nodes.get(option.what());
                    if (dependency != null && dependency.component != component && dependency.component != null &&
                            canReachSeed(dependency.component, new HashSet<>())) {
                        return true;
                    }
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
        private boolean canReachSeedThroughEdge(RouteEdge edge, @Nullable Component component) {
            return edgeCanStartComponent(edge, component, new HashSet<>());
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

        private boolean contains(AEKey key) {
            return complete && nodes.containsKey(key);
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

        private boolean canReachSeed(Component component, Set<Component> visiting) {
            if (component.reachesSeed != null) return component.reachesSeed;
            if (component.directSeed) {
                component.reachesSeed = true;
                return true;
            }
            if (!visiting.add(component)) return false;
            try {
                for (GraphNode member : component.members) {
                    for (RouteEdge edge : member.edges) {
                        if (edgeCanStartComponent(edge, component, visiting)) {
                            component.reachesSeed = true;
                            return true;
                        }
                    }
                }
                component.reachesSeed = false;
                return false;
            } finally {
                visiting.remove(component);
            }
        }

        /** An SCC is startable only when one complete input choice avoids consuming that same SCC. */
        private boolean edgeCanStartComponent(RouteEdge edge, @Nullable Component component,
                                              Set<Component> visiting) {
            if (edge.cyclePolicy == CyclePolicy.FORBID || edge.cyclePolicy == CyclePolicy.BREAKABLE) return false;
            if (!edge.normalPattern && edge.cyclePolicy == CyclePolicy.EXTERNAL_SEED) return true;

            for (IPatternDetails.IInput input : edge.inputs) {
                GenericStack[] options = input.possibleInputs();
                int optionLimit = Math.min(options.length, estimator.getLimits().getMaxInputAlternatives());
                boolean satisfiableOutsideComponent = false;
                for (int optionIndex = 0; optionIndex < optionLimit; optionIndex++) {
                    GenericStack option = options[optionIndex];
                    if (option == null || option.amount() <= 0) continue;
                    long required = multiplySaturated(option.amount(), input.getMultiplier());
                    if (estimator.inventory.get(option.what()) >= required) {
                        satisfiableOutsideComponent = true;
                        break;
                    }

                    GraphNode dependency = nodes.get(option.what());
                    if (dependency == null || dependency.component == null || dependency.component == component) {
                        continue;
                    }
                    if (canReachSeed(dependency.component, visiting)) {
                        satisfiableOutsideComponent = true;
                        break;
                    }
                }
                if (!satisfiableOutsideComponent) return false;
            }
            return true;
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

    static boolean terminatesCycleGraph(boolean stored, boolean normalPattern, CyclePolicy cyclePolicy) {
        return stored || normalPattern || cyclePolicy == CyclePolicy.EXTERNAL_SEED;
    }

    static boolean isCycleScopeDependency(boolean sameKey, boolean sameMaterial) {
        return sameKey || sameMaterial;
    }

    /** Reserves half of a calculation for later refinement rounds while still probing every route once. */
    static int routeInitialExpansionQuota(int remainingCalculationExpansions, int preferredExpansionsPerRoot,
                                          int rootCount) {
        if (remainingCalculationExpansions <= 0) return 0;
        int normalizedRoots = Math.max(1, rootCount);
        int normalizedPreferred = Math.max(1, preferredExpansionsPerRoot);
        long denominator = Math.max(1L, (long) normalizedRoots * 2L);
        long fairProbe = ((long) remainingCalculationExpansions + denominator - 1L) / denominator;
        return (int) Math.min(normalizedPreferred, Math.max(1L, fairProbe));
    }

    /** Doubles a quota only when the calculation still has room to reach beyond the last truncated frontier. */
    static int routeNextExpansionQuota(int remainingCalculationExpansions, int previousQuota) {
        int normalizedPrevious = Math.max(1, previousQuota);
        if (remainingCalculationExpansions <= normalizedPrevious) return normalizedPrevious;
        long doubled = Math.min(Integer.MAX_VALUE, (long) normalizedPrevious * 2L);
        return (int) Math.min((long) remainingCalculationExpansions, doubled);
    }

    /**
     * Bounds one recursive route comparison. Normal crafting keeps its configured expansion cap; standalone
     * generation is deadline-bound and receives fair adaptive grants instead of an arbitrary per-target ceiling.
     */
    private static final class RouteCostBudget {

        private final PlanningBudget limits;
        private final int maxExpansions;
        private final long maxCalculationNanos;
        @Nullable private final CraftingRecoverySession standaloneSession;
        private final long createdAtNanos;
        private long deadlineNanos;
        private int expansions;
        private boolean exhausted;
        private String exhaustionReason = "OK";

        private RouteCostBudget(PlanningBudget limits, boolean standalonePatternGeneration) {
            this(limits, standalonePatternGeneration, 0L, null);
        }

        private RouteCostBudget(PlanningBudget limits, boolean standalonePatternGeneration, long sharedDeadlineNanos,
                                @Nullable CraftingRecoverySession standaloneSession) {
            this.limits = limits == null ? PlanningBudget.DEFAULT : limits;
            // The standalone value used to be treated as a hard cap. That caused a deep chemical branch to be
            // penalized merely for needing a later fair grant. The shared task deadline is the safety boundary here.
            maxExpansions = standalonePatternGeneration ? 0 : this.limits.getMaxRouteExpansionsPerCalculation();
            maxCalculationNanos = standalonePatternGeneration ?
                    this.limits.getMaxStandaloneRouteCalculationNanos() :
                    this.limits.getMaxRouteCalculationNanos();
            this.standaloneSession = standaloneSession;
            createdAtNanos = System.nanoTime();
            deadlineNanos = Math.max(0L, sharedDeadlineNanos);
        }

        private boolean tryExpansion() {
            if (!hasRemainingTime()) return false;
            if (maxExpansions > 0 && expansions >= maxExpansions) {
                exhaust("ROUTE_EXPANSION_LIMIT");
                return false;
            }
            expansions++;
            if (standaloneSession != null) standaloneSession.recordStandaloneRouteCostExpansion();
            return true;
        }

        /** Estimates an equal time-share of the still-open deadline for the next fair refinement round. */
        private int getFairExpansionAllowance(int participantCount) {
            if (!hasRemainingTime()) return 0;
            int participants = Math.max(1, participantCount);
            if (maxExpansions > 0) return Math.max(0, getRemainingExpansions() / participants);

            long now = System.nanoTime();
            long elapsed = Math.max(1L, now - createdAtNanos);
            long remaining = Math.max(0L, deadlineNanos - now);
            if (remaining <= 0) return 0;
            if (expansions <= 0) return Math.max(1, limits.getMaxRouteExpansionsPerTarget());

            double projected = ((double) expansions * (double) remaining) /
                    ((double) elapsed * (double) participants);
            if (projected >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return Math.max(1, (int) Math.ceil(projected));
        }

        private boolean hasRemainingTime() {
            if (deadlineNanos == 0) {
                deadlineNanos = System.nanoTime() + maxCalculationNanos;
            }
            if (System.nanoTime() - deadlineNanos < 0) return true;
            exhaust("ROUTE_TIME_LIMIT");
            return false;
        }

        private void exhaust(String reason) {
            if (exhausted) return;
            exhausted = true;
            exhaustionReason = reason;
            PLANNING_METRICS.recordBudgetExhaustion();
            if (standaloneSession != null) standaloneSession.recordStandaloneRouteCostBudgetExhaustion(reason);
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

        private int getRemainingExpansions() {
            if (maxExpansions <= 0) return Integer.MAX_VALUE;
            return Math.max(0, maxExpansions - expansions);
        }

        private int getMaxExpansions() {
            return maxExpansions;
        }

        private boolean hasExpansionCap() {
            return maxExpansions > 0;
        }

        private void recordStalledRefinement() {
            if (standaloneSession != null) standaloneSession.recordStandaloneRouteCostStall();
        }

        private void recordPathCycleRejection() {
            if (standaloneSession != null) standaloneSession.recordStandaloneRoutePathCycle();
        }

        private boolean isSessionManagedStandaloneBudget() {
            return standaloneSession != null;
        }

        private PlanningBudget getLimits() {
            return limits;
        }
    }

    /** Captures the bounded scorer's per-root share for the one focused standalone selection diagnostic. */
    private static final class RouteScoringProgress {

        private static final RouteScoringProgress EMPTY = new RouteScoringProgress(0, 0, false, 0, 0, 0, 0,
                null, 0);

        private final int expansions;
        private final int quota;
        private final boolean quotaLimited;
        private final int calculationRemaining;
        private final int calculationLimit;
        private final int adaptivePasses;
        private final int cumulativeExpansions;
        @Nullable private final RouteCost frontierCost;
        private final int identicalReplayCount;

        private RouteScoringProgress(int expansions, int quota, boolean quotaLimited, int calculationRemaining,
                                     int calculationLimit) {
            this(expansions, quota, quotaLimited, calculationRemaining, calculationLimit, 1,
                    Math.max(0, expansions), null, 0);
        }

        private RouteScoringProgress(int expansions, int quota, boolean quotaLimited, int calculationRemaining,
                                     int calculationLimit, int adaptivePasses, int cumulativeExpansions,
                                     @Nullable RouteCost frontierCost, int identicalReplayCount) {
            this.expansions = expansions;
            this.quota = quota;
            this.quotaLimited = quotaLimited;
            this.calculationRemaining = calculationRemaining;
            this.calculationLimit = calculationLimit;
            this.adaptivePasses = adaptivePasses;
            this.cumulativeExpansions = cumulativeExpansions;
            this.frontierCost = frontierCost;
            this.identicalReplayCount = identicalReplayCount;
        }

        private int getQuota() {
            return quota;
        }

        private boolean isQuotaLimited() {
            return quotaLimited;
        }

        private boolean shouldReceiveAnotherFairGrant() {
            return shouldContinueFairRouteRefinement(quotaLimited, identicalReplayCount);
        }

        private boolean becameStalled() {
            return quotaLimited && identicalReplayCount == MAX_IDENTICAL_ROUTE_REFINEMENT_REPLAYS;
        }

        private RouteScoringProgress withAdaptiveHistory(RouteScoringProgress previous, RouteCost currentCost) {
            int previousPasses = previous == null ? 0 : previous.adaptivePasses;
            int previousExpansions = previous == null ? 0 : previous.cumulativeExpansions;
            boolean identical = previous != null && currentCost.sameFrontierAs(previous.frontierCost);
            int identicalReplays = identical ? previous.identicalReplayCount + 1 : 0;
            return new RouteScoringProgress(expansions, quota, quotaLimited, calculationRemaining,
                    calculationLimit, previousPasses + 1, previousExpansions + expansions,
                    currentCost, identicalReplays);
        }

        @Override
        public String toString() {
            return "[expanded=" + expansions + ", quota=" + quota + ", quotaLimited=" + quotaLimited +
                    ", passes=" + adaptivePasses + ", cumulativeExpanded=" + cumulativeExpansions +
                    ", identicalReplays=" + identicalReplayCount + ", remaining=" + calculationRemaining +
                    ", limit=" + (calculationLimit <= 0 ? "deadline" : calculationLimit) + ']';
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

        private long getAvailable(AEKey key) {
            Long overridden = availableOverrides.get(key);
            return overridden == null ? inventory.get(key) : overridden;
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

        private static final DirectRouteCost ZERO = new DirectRouteCost(0, 0, 0, 0, 0);
        private static final DirectRouteCost UNRESOLVED =
                new DirectRouteCost(1, 0, 1, BOUNDED_ROUTE_COST_PENALTY, 0);

        private final int unresolvedInputs;
        private final long materialFormConversions;
        private final int dependentInputs;
        private final long missingMaterials;
        private final long consumedStockMaterials;

        private DirectRouteCost(int unresolvedInputs, long materialFormConversions, int dependentInputs,
                                long missingMaterials,
                                long consumedStockMaterials) {
            this.unresolvedInputs = unresolvedInputs;
            this.materialFormConversions = materialFormConversions;
            this.dependentInputs = dependentInputs;
            this.missingMaterials = missingMaterials;
            this.consumedStockMaterials = consumedStockMaterials;
        }

        private static DirectRouteCost materialFormConversions(long conversions) {
            return conversions <= 0 ? ZERO : new DirectRouteCost(0, conversions, 0, 0, 0);
        }

        private static DirectRouteCost input(AEKey key, long fromStock, long remaining,
                                              boolean hasNormalPattern, boolean rawMaterialLeaf,
                                              boolean nonConsumableControlToken) {
            if (nonConsumableControlToken) return ZERO;
            return new DirectRouteCost(directInputUnresolvedPenalty(remaining, hasNormalPattern, rawMaterialLeaf), 0,
                    remaining > 0 ? 1 : 0, estimateKeyMaterialAmount(key, remaining),
                    estimateKeyMaterialAmount(key, fromStock));
        }

        private boolean isFullyStocked() {
            return dependentInputs == 0;
        }

        private DirectRouteCost plus(DirectRouteCost other) {
            return new DirectRouteCost(unresolvedInputs + other.unresolvedInputs,
                    addSaturated(materialFormConversions, other.materialFormConversions),
                    dependentInputs + other.dependentInputs,
                    addSaturated(missingMaterials, other.missingMaterials),
                    addSaturated(consumedStockMaterials, other.consumedStockMaterials));
        }

        @Override
        public int compareTo(DirectRouteCost other) {
            return compareDirectRouteCost(unresolvedInputs, materialFormConversions, dependentInputs,
                    missingMaterials, consumedStockMaterials, other.unresolvedInputs,
                    other.materialFormConversions, other.dependentInputs, other.missingMaterials,
                    other.consumedStockMaterials);
        }

        @Override
        public String toString() {
            return "[unresolved=" + unresolvedInputs + ", forms=" + materialFormConversions +
                    ", dependencies=" + dependentInputs +
                    ", missing=" + missingMaterials + ", stock=" + consumedStockMaterials + ']';
        }
    }

    private static final class RouteCost implements Comparable<RouteCost> {

        private final long missingMaterials;
        private final int maxDepth;
        private final long executions;
        private final long consumedStockMaterials;
        private final int boundedFallbacks;
        private final int unresolvedIntermediates;
        private final long cycleRisk;
        private final long materialFormConversions;

        private RouteCost(long missingMaterials, int maxDepth, long executions,
                          long consumedStockMaterials, int boundedFallbacks, int unresolvedIntermediates,
                          long cycleRisk, long materialFormConversions) {
            this.missingMaterials = missingMaterials;
            this.maxDepth = maxDepth;
            this.executions = executions;
            this.consumedStockMaterials = consumedStockMaterials;
            this.boundedFallbacks = boundedFallbacks;
            this.unresolvedIntermediates = unresolvedIntermediates;
            this.cycleRisk = cycleRisk;
            this.materialFormConversions = materialFormConversions;
        }

        private boolean hasBoundedFallback() {
            return boundedFallbacks > 0;
        }

        /** Only a changed result proves that replaying this recursive prefix reached a new route frontier. */
        private boolean sameFrontierAs(@Nullable RouteCost other) {
            return other != null && missingMaterials == other.missingMaterials && maxDepth == other.maxDepth &&
                    executions == other.executions && consumedStockMaterials == other.consumedStockMaterials &&
                    boundedFallbacks == other.boundedFallbacks &&
                    unresolvedIntermediates == other.unresolvedIntermediates && cycleRisk == other.cycleRisk &&
                    materialFormConversions == other.materialFormConversions;
        }

        @Override
        public int compareTo(RouteCost other) {
            int completeness = compareRouteCompletenessSafetyFormAndMaterials(boundedFallbacks,
                    unresolvedIntermediates, cycleRisk, materialFormConversions, missingMaterials,
                    other.boundedFallbacks, other.unresolvedIntermediates, other.cycleRisk,
                    other.materialFormConversions, other.missingMaterials);
            if (completeness != 0) return completeness;
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
                    ", unresolved=" + unresolvedIntermediates +
                    ", cycle=" + cycleRisk + ", forms=" + materialFormConversions + ']';
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
        private final Set<AEKey> elementalFluidLeaves = new HashSet<>();
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

    /** Mutable only on one AE crafting worker; all rejection eligibility disappears when that task finishes. */
    private static final class CraftingRecoverySession {

        private final IGrid grid;
        private final AEKey rootTarget;
        private final long amount;
        private final boolean optimalRebuild;
        @Nullable private final CycleMemoryStore cycleMemory;
        private final CycleRecoveryTracker<AEKey> tracker = new CycleRecoveryTracker<>();
        private final Set<String> observedCycleSignatures = new HashSet<>();
        private final Set<AEKey> exposedDynamicTargets = new HashSet<>();
        private final Set<String> exposedDynamicRoutes = new HashSet<>();
        private final Set<AEKey> pendingExpansionTargets = new HashSet<>();
        private final Set<AEKey> expandedTargets = new HashSet<>();
        private final Set<AEKey> rejectedProcessedMaterialBackConversions = new HashSet<>();
        private final Set<String> matchedRememberedBindings = new HashSet<>();
        private final Map<AEKey, List<PatternCandidate>> routeCandidatesByTarget = new HashMap<>();
        private final Map<AEKey, String> plannerRecipeByTarget = new HashMap<>();
        private final List<String> standaloneRecursiveRouteOverrideSamples = new ArrayList<>();
        /** Candidate details visible only to this calculation, populated before CraftingCalculation.run(). */
        private final Map<AEKey, List<DynamicRecipePatternDetails>> transientPatternsByTarget = new HashMap<>();
        private final Map<IPatternDetails, ICraftingProvider> transientProviders = new IdentityHashMap<>();
        @Nullable private RouteCycleAnalysis reusableCycleAnalysis;
        private int standaloneRouteCostBudgetScopes;
        private int standaloneRouteCostExpansions;
        private int standaloneRouteCostBudgetExhaustions;
        private int standaloneRouteCostStalls;
        private int standaloneRoutePathCycleRejections;
        private int standaloneFastSelectionTargets;
        private String standaloneRouteCostBudgetLastReason = "OK";
        private int cycleObservations;
        private int rejectionFilters;
        private int recoveryAttempts;
        private int retainedPatternsDuringExpansion;
        private int appendedPatterns;
        private int newCycleMemoryEntries;
        private int largestCycle;
        private AEKey lastRepeatedTarget;
        private boolean succeeded;
        private boolean transientGraphPrepared;
        private boolean transientGraphIncomplete;
        private boolean transientGraphBuilding;
        private boolean standaloneSelectionFrozen;
        private int standaloneSelectedTargets;
        private long transientGraphDeadlineNanos;
        private int transientGraphTargets;
        private long transientGraphNanos;
        private int standaloneRecursiveRouteOverrides;
        private CraftingCalculation lastCalculation;

        private CraftingRecoverySession(IGrid grid, AEKey rootTarget, long amount, boolean optimalRebuild,
                                        @Nullable CycleMemoryStore cycleMemory) {
            this.grid = grid;
            this.rootTarget = rootTarget;
            this.amount = amount;
            this.optimalRebuild = optimalRebuild;
            this.cycleMemory = cycleMemory;
        }

        private boolean matches(GridState state) {
            return state != null && GRIDS.get(grid) == state;
        }

        private boolean isOptimalRebuild() {
            return optimalRebuild;
        }

        private RouteCostBudget newStandaloneRouteCostBudget(PlanningBudget budget) {
            if (!transientGraphBuilding || transientGraphDeadlineNanos == 0) {
                return new RouteCostBudget(budget, true);
            }
            standaloneRouteCostBudgetScopes++;
            return new RouteCostBudget(budget, true, transientGraphDeadlineNanos, this);
        }

        private void recordStandaloneRouteCostExpansion() {
            standaloneRouteCostExpansions++;
        }

        private void recordStandaloneRouteCostBudgetExhaustion(String reason) {
            standaloneRouteCostBudgetExhaustions++;
            standaloneRouteCostBudgetLastReason = reason == null ? "UNKNOWN" : reason;
        }

        private void recordStandaloneRouteCostStall() {
            standaloneRouteCostStalls++;
        }

        private void recordStandaloneRoutePathCycle() {
            standaloneRoutePathCycleRejections++;
        }

        private void recordStandaloneFastSelection() {
            standaloneFastSelectionTargets++;
        }

        private void recordStandaloneRecursiveRouteOverride(AEKey target, PatternCandidate staticCandidate,
                                                             PatternCandidate selectedCandidate) {
            standaloneRecursiveRouteOverrides++;
            if (standaloneRecursiveRouteOverrideSamples.size() >= 8) return;
            standaloneRecursiveRouteOverrideSamples.add(RecipeFingerprint.describeKey(target) + ':' +
                    staticCandidate.recipeMap.getUnlocalizedName() + "->" +
                    selectedCandidate.recipeMap.getUnlocalizedName());
        }

        private int rejectCycle(GridState state, AEKey repeatedTarget, List<AEKey> cycleTargets,
                                List<? extends IPatternDetails> cyclePatterns) {
            if (!matches(state)) return 0;

            Set<AEKey> members = new HashSet<>();
            for (AEKey target : cycleTargets) {
                if (target != null) members.add(target);
            }
            members.add(repeatedTarget);
            cycleObservations++;
            largestCycle = Math.max(largestCycle, members.size());
            lastRepeatedTarget = repeatedTarget;
            String cycleSignature = createCycleSignature(repeatedTarget, cycleTargets, cyclePatterns);
            observedCycleSignatures.add(cycleSignature);

            int progress = 0;
            for (int index = 0; index < cycleTargets.size(); index++) {
                AEKey target = cycleTargets.get(index);
                DynamicRecipePatternDetails dynamic = getDynamicPattern(cyclePatterns.get(index));
                if (target == null || dynamic == null || !dynamic.netProduces(target) ||
                        !isTransientPattern(target, dynamic) &&
                                state.patternsByRecipe.get(dynamic.getRecipeKey()) != dynamic) {
                    continue;
                }

                int edgeProgress = tracker.reject(target, dynamic.getRecipeKey(), members,
                        requiresCycleMember(dynamic.getInputs(), members));
                if (edgeProgress > 0) {
                    pendingExpansionTargets.add(target);
                    progress += edgeProgress;
                }
                if (cycleMemory != null && cycleMemory.record(dynamic.getRecipeBinding(), cycleSignature)) {
                    newCycleMemoryEntries++;
                }
            }
            if (!cyclePatterns.isEmpty()) {
                DynamicRecipePatternDetails opening = getDynamicPattern(cyclePatterns.get(0));
                if (opening != null && opening.netProduces(repeatedTarget) &&
                        isProcessedMaterialBackConversion(repeatedTarget, opening.getInputs()) &&
                        rejectedProcessedMaterialBackConversions.add(repeatedTarget)) {
                    pendingExpansionTargets.add(repeatedTarget);
                    progress++;
                }
            }
            rejectionFilters += progress;
            if (progress > 0) {
                reusableCycleAnalysis = null;
                routeCandidatesByTarget.clear();
                plannerRecipeByTarget.clear();
            }
            return progress;
        }

        @Nullable
        private List<PatternCandidate> findRouteCandidates(AEKey target) {
            return routeCandidatesByTarget.get(target);
        }

        private void rememberRouteCandidates(AEKey target, List<PatternCandidate> candidates) {
            if (target != null && candidates != null) routeCandidatesByTarget.put(target, candidates);
        }

        private void rememberPlannerRoutes(Map<AEKey, String> routes) {
            if (routes != null) routes.forEach(plannerRecipeByTarget::putIfAbsent);
        }

        @Nullable
        private String findPlannedRecipe(AEKey target) {
            return target == null ? null : plannerRecipeByTarget.get(target);
        }

        private Set<AEKey> getTransientCandidateTargets() {
            return new HashSet<>(routeCandidatesByTarget.keySet());
        }

        @Nullable
        private List<DynamicRecipePatternDetails> findTransientPatterns(AEKey target) {
            return transientPatternsByTarget.get(target);
        }

        private void rememberTransientPatterns(AEKey target, List<DynamicRecipePatternDetails> patterns) {
            if (target != null && patterns != null) transientPatternsByTarget.put(target, patterns);
        }

        private void forgetTransientPatterns(AEKey target) {
            if (target != null) transientPatternsByTarget.remove(target);
        }

        private void rememberTransientProvider(DynamicRecipePatternDetails detail, ICraftingProvider provider) {
            if (detail != null && provider != null) transientProviders.put(detail, provider);
        }

        @Nullable
        private ICraftingProvider getTransientProvider(IPatternDetails detail) {
            return detail == null ? null : transientProviders.get(detail);
        }

        private boolean isTransientPattern(AEKey target, DynamicRecipePatternDetails detail) {
            List<DynamicRecipePatternDetails> patterns = transientPatternsByTarget.get(target);
            if (patterns == null) return false;
            for (DynamicRecipePatternDetails candidate : patterns) {
                if (candidate == detail) return true;
            }
            return false;
        }

        private boolean isTransientGraphPrepared() {
            return transientGraphPrepared;
        }

        private boolean isStandaloneSelectionFrozen() {
            return standaloneSelectionFrozen;
        }

        private void freezeStandaloneSelection(int selectedTargets) {
            standaloneSelectionFrozen = true;
            standaloneSelectedTargets = selectedTargets;
            if (ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Fully selected {} reachable RecipeMap pattern target(s) for standalone " +
                                "generation root={}", selectedTargets, rootTarget);
            }
        }

        /** Starts another standalone generation pass without forgetting the cycle edges rejected by this session. */
        private void resetStandaloneSelectionAfterCycleRecovery() {
            routeCandidatesByTarget.clear();
            plannerRecipeByTarget.clear();
            standaloneRecursiveRouteOverrideSamples.clear();
            transientPatternsByTarget.clear();
            transientProviders.clear();
            reusableCycleAnalysis = null;
            standaloneRouteCostBudgetScopes = 0;
            standaloneRouteCostExpansions = 0;
            standaloneRouteCostBudgetExhaustions = 0;
            standaloneRouteCostStalls = 0;
            standaloneRoutePathCycleRejections = 0;
            standaloneFastSelectionTargets = 0;
            standaloneRouteCostBudgetLastReason = "OK";
            transientGraphPrepared = false;
            transientGraphIncomplete = false;
            standaloneSelectionFrozen = false;
            standaloneSelectedTargets = 0;
            standaloneRecursiveRouteOverrides = 0;
        }

        private void beginTransientGraphBuild(long deadlineNanos) {
            transientGraphBuilding = true;
            transientGraphDeadlineNanos = deadlineNanos;
        }

        private void endTransientGraphBuild() {
            transientGraphBuilding = false;
            transientGraphDeadlineNanos = 0;
        }

        private boolean shouldStopTransientGraphBuild() {
            if (!transientGraphBuilding || transientGraphDeadlineNanos == 0) return false;
            if (System.nanoTime() - transientGraphDeadlineNanos < 0) return false;
            transientGraphIncomplete = true;
            return true;
        }

        private void markTransientGraphIncomplete() {
            transientGraphIncomplete = true;
        }

        private void markTransientGraphPrepared(int targets, long nanos) {
            transientGraphPrepared = true;
            transientGraphTargets = targets;
            transientGraphNanos = nanos;
            if (ApplyGrayMod.LOGGER.isDebugEnabled()) {
                ApplyGrayMod.LOGGER.debug("Prepared task-local RecipeMap candidate graph root={} targets={} " +
                                "selectedPatterns={} elapsedMs={}", rootTarget, targets,
                        transientPatternsByTarget.size(), nanos / 1_000_000L);
            }
            if (standaloneRouteCostBudgetScopes > 0) {
                ApplyGrayMod.LOGGER.info("RecipeMap standalone route scoring summary root={} scopes={} " +
                                "expansions={} initialQuota={} expansionLimit=DEADLINE_ONLY " +
                                "stalledRefinements={} pathCycleRejections={} exhaustedScopes={} " +
                                "lastBudgetReason={} staticOverrides={} samples={}",
                        rootTarget, standaloneRouteCostBudgetScopes, standaloneRouteCostExpansions,
                        getPlanningBudget().getMaxRouteExpansionsPerTarget(), standaloneRouteCostStalls,
                        standaloneRoutePathCycleRejections, standaloneRouteCostBudgetExhaustions,
                        standaloneRouteCostBudgetLastReason,
                        standaloneRecursiveRouteOverrides,
                        standaloneRecursiveRouteOverrideSamples);
            }
            if (transientGraphIncomplete) {
                if (standaloneFastSelectionTargets > 0) {
                    ApplyGrayMod.LOGGER.warn("RecipeMap standalone route scoring reached its deadline but continued " +
                                    "with direct selected-route materialization root={} targets={} fastTargets={} " +
                                    "elapsedMs={} scoringLimitMs={} configKey=maxStandaloneRouteCalculationMillis " +
                                    "reasonCode=DEADLINE_FAST_CONTINUATION",
                            rootTarget, targets, standaloneFastSelectionTargets, nanos / 1_000_000L,
                            getPlanningBudget().getMaxStandaloneRouteCalculationMillis());
                } else {
                    ApplyGrayMod.LOGGER.warn("RecipeMap task-local candidate graph reached its planning budget root={} " +
                                    "targets={} elapsedMs={}", rootTarget, targets, nanos / 1_000_000L);
                }
            }
        }

        @Nullable
        private RouteCycleAnalysis findReusableCycleAnalysis(AEKey target) {
            return reusableCycleAnalysis != null && reusableCycleAnalysis.contains(target) ?
                    reusableCycleAnalysis : null;
        }

        private void rememberCycleAnalysis(RouteCycleAnalysis analysis) {
            if (analysis != null && analysis.isComplete() &&
                    (reusableCycleAnalysis == null ||
                            analysis.getNodeCount() > reusableCycleAnalysis.getNodeCount())) {
                reusableCycleAnalysis = analysis;
            }
        }

        private boolean markSafetyUnknown(AEKey target, String reason, boolean withhold) {
            return tracker.markSafetyUnknown(target, reason, withhold);
        }

        private boolean hasFilters(AEKey target) {
            return tracker.hasFilters(target) || rejectedProcessedMaterialBackConversions.contains(target);
        }

        private boolean consumeExpansion(AEKey target) {
            return pendingExpansionTargets.remove(target);
        }

        private Set<AEKey> drainPendingExpansions() {
            if (pendingExpansionTargets.isEmpty()) return Collections.emptySet();
            Set<AEKey> pending = new HashSet<>(pendingExpansionTargets);
            pendingExpansionTargets.clear();
            return pending;
        }

        private void recordCacheExpansion(AEKey target, int retained, int appended) {
            expandedTargets.add(target);
            retainedPatternsDuringExpansion += Math.max(0, retained);
            appendedPatterns += Math.max(0, appended);
        }

        private boolean isRejected(AEKey target, String recipeKey, IPatternDetails.IInput[] inputs) {
            if (tracker.rejectsRecipeOrUnknown(target, recipeKey)) return true;
            if (rejectedProcessedMaterialBackConversions.contains(target) &&
                    isProcessedMaterialBackConversion(target, inputs)) {
                return true;
            }
            return requiresCycleMember(inputs, tracker.getRejectedCycleMembers(target));
        }

        private void recordExposed(AEKey target, DynamicRecipePatternDetails detail) {
            if (target == null || detail == null) return;
            exposedDynamicTargets.add(target);
            exposedDynamicRoutes.add(detail.getRecipeKey());
        }

        private int compareCycleMemoryHint(RecipeBinding left, RecipeBinding right) {
            if (!optimalRebuild || cycleMemory == null || left == null || right == null) return 0;
            boolean leftRemembered = cycleMemory.isRemembered(left);
            boolean rightRemembered = cycleMemory.isRemembered(right);
            if (leftRemembered) matchedRememberedBindings.add(left.getTargetKey() + ':' + left.getRecipeFingerprint());
            if (rightRemembered) {
                matchedRememberedBindings.add(right.getTargetKey() + ':' + right.getRecipeFingerprint());
            }
            return Boolean.compare(leftRemembered, rightRemembered);
        }

        private void flushCycleMemory() {
            if (cycleMemory == null) return;
            cycleMemory.flush();
            if (newCycleMemoryEntries > 0) {
                ApplyGrayMod.LOGGER.info("Persisted {} new RecipeMap cycle hint(s); memory now contains {} " +
                                "versioned edge(s)", newCycleMemoryEntries, cycleMemory.size());
            }
        }

        private void logSummary() {
            if (cycleObservations == 0 && tracker.getSafetyUnknownTargetCount() == 0) return;
            String outcome = succeeded ? "SUCCESS" : "FAILED";
            String lastTarget = lastRepeatedTarget == null ? "<none>" : lastRepeatedTarget.toString();
            String message = "RecipeMap cycle recovery summary root={} amount={} mode={} retryScope={} outcome={} " +
                    "observations={} " +
                    "uniqueCycles={} largestCycle={} rejectionFilters={} retries={} lastRepeatedTarget={} " +
                    "safetyUnknownTargets={} dynamicTargetsExposed={} dynamicRoutesExposed={} " +
                    "cacheExpansionTargets={} retainedPatterns={} appendedPatterns={} rememberedHintsMatched={} " +
                    "newMemoryEntries={}";
            if (succeeded) {
                ApplyGrayMod.LOGGER.info(message, rootTarget, amount, optimalRebuild ? "OPTIMAL_REBUILD" : "NORMAL",
                        optimalRebuild ? "FULL_TREE" : "TARGET_BRANCH", outcome, cycleObservations,
                        observedCycleSignatures.size(), largestCycle, rejectionFilters, recoveryAttempts, lastTarget,
                        tracker.getSafetyUnknownTargetCount(), exposedDynamicTargets.size(), exposedDynamicRoutes.size(),
                        expandedTargets.size(), retainedPatternsDuringExpansion, appendedPatterns,
                        matchedRememberedBindings.size(), newCycleMemoryEntries);
            } else {
                ApplyGrayMod.LOGGER.warn(message, rootTarget, amount, optimalRebuild ? "OPTIMAL_REBUILD" : "NORMAL",
                        optimalRebuild ? "FULL_TREE" : "TARGET_BRANCH", outcome, cycleObservations,
                        observedCycleSignatures.size(), largestCycle, rejectionFilters, recoveryAttempts, lastTarget,
                        tracker.getSafetyUnknownTargetCount(), exposedDynamicTargets.size(), exposedDynamicRoutes.size(),
                        expandedTargets.size(), retainedPatternsDuringExpansion, appendedPatterns,
                        matchedRememberedBindings.size(), newCycleMemoryEntries);
            }
        }

        private static boolean requiresCycleMember(IPatternDetails.IInput[] inputs, Set<AEKey> cycleMembers) {
            if (inputs == null || inputs.length == 0 || cycleMembers.isEmpty()) return false;
            List<List<AEKey>> alternatives = new ArrayList<>(inputs.length);
            for (IPatternDetails.IInput input : inputs) {
                List<AEKey> optionKeys = new ArrayList<>();
                for (GenericStack option : input.possibleInputs()) {
                    if (option == null || option.amount() <= 0) continue;
                    optionKeys.add(option.what());
                }
                alternatives.add(optionKeys);
            }
            return CycleRecoveryTracker.requiresCycleMember(alternatives, cycleMembers);
        }

        /**
         * Identifies molten-material recovery from processed shapes. Once such an edge closes a real AE2 cycle,
         * filtering the whole route class avoids rediscovering the same material loop for screws, rounds, nuggets,
         * wires, and similar forms one calculation at a time. Dust and ingot inputs remain available as useful
         * upstream synthesis routes.
         */
        private static boolean isProcessedMaterialBackConversion(AEKey target, IPatternDetails.IInput[] inputs) {
            if (!(target instanceof AEFluidKey) || inputs == null || inputs.length == 0) return false;
            Material targetMaterial = getMaterialForKey(target);
            if (targetMaterial == null) return false;

            for (IPatternDetails.IInput input : inputs) {
                boolean hasOption = false;
                boolean allProcessedTargetMaterial = true;
                for (GenericStack option : input.possibleInputs()) {
                    if (option == null || option.amount() <= 0) continue;
                    hasOption = true;
                    AEKey inputKey = option.what();
                    String prefix = getOrePrefixForKey(inputKey);
                    if (!targetMaterial.equals(getMaterialForKey(inputKey)) || isDustPrefix(prefix) ||
                            isIngotPrefix(prefix) || isRawMaterialLeaf(inputKey)) {
                        allProcessedTargetMaterial = false;
                        break;
                    }
                }
                if (hasOption && allProcessedTargetMaterial) return true;
            }
            return false;
        }

        private static String createCycleSignature(AEKey repeatedTarget, List<AEKey> cycleTargets,
                                                   List<? extends IPatternDetails> cyclePatterns) {
            StringBuilder signature = new StringBuilder(RecipeFingerprint.describeKey(repeatedTarget));
            for (int index = 0; index < cycleTargets.size(); index++) {
                AEKey target = cycleTargets.get(index);
                signature.append('>').append(RecipeFingerprint.describeKey(target));
                DynamicRecipePatternDetails detail = getDynamicPattern(cyclePatterns.get(index));
                if (detail != null) {
                    signature.append('#').append(detail.getRecipeBinding().getRecipeFingerprint());
                }
            }
            return RecipeFingerprint.sha256(signature.toString());
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

        /** Converts each input to its selected single key while retaining the original per-craft quantities. */
        private EncodedRecipe withFrozenInputs(IPatternDetails.IInput[] selectedInputs) {
            if (selectedInputs == null || selectedInputs.length != inputs.size()) return this;

            List<GenericStack> selected = new ArrayList<>(inputs.size());
            for (int index = 0; index < inputs.size(); index++) {
                GenericStack selectedOption = null;
                for (GenericStack option : selectedInputs[index].possibleInputs()) {
                    if (option != null && option.amount() > 0) {
                        selectedOption = option;
                        break;
                    }
                }
                if (selectedOption == null) return this;
                selected.add(selectedOption);
            }
            return withFrozenInputChoices(selected);
        }

        private EncodedRecipe withFrozenInputChoices(List<GenericStack> selectedInputs) {
            if (selectedInputs == null || selectedInputs.size() != inputs.size()) return this;
            List<GenericStack> frozenInputs = new ArrayList<>(inputs.size());
            List<List<GenericStack>> frozenAlternatives = new ArrayList<>(inputs.size());
            for (int index = 0; index < inputs.size(); index++) {
                GenericStack original = inputs.get(index);
                GenericStack selected = selectedInputs.get(index);
                if (selected == null || selected.amount() <= 0) return this;
                GenericStack frozen = new GenericStack(selected.what(), original.amount());
                frozenInputs.add(frozen);
                frozenAlternatives.add(List.of(frozen));
            }
            return new EncodedRecipe(Collections.unmodifiableList(frozenInputs),
                    Collections.unmodifiableList(frozenAlternatives), outputs, circuitConfiguration, tokenLayout);
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
        private final boolean primaryCompoundSynthesis;
        private final boolean recyclingRoute;
        private final String recipeKey;

        private PatternCandidate(ProviderSnapshot source, RecipeMap<?> recipeMap, NormalizedRecipe normalized,
                                 AEKey target, EncodedRecipe encoded, TargetedRecipe targeted,
                                 RuleDecision decision, Cost cost, boolean primaryCompoundSynthesis,
                                 boolean recyclingRoute) {
            this.source = source;
            this.recipeMap = recipeMap;
            this.normalized = normalized;
            this.encoded = encoded;
            this.targeted = targeted;
            this.decision = decision;
            this.cost = cost;
            this.primaryCompoundSynthesis = primaryCompoundSynthesis;
            this.recyclingRoute = recyclingRoute;
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
