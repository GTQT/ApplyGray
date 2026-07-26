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
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.CraftingCalculation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy bridge between AE2's requested-output lookup and active RecipeMap pattern providers.
 * Only recipes requested by an AE crafting calculation become virtual patterns.
 */
public final class DynamicRecipePatternRegistry {

    private static final int STANDARD_FLUID_MILLIBUCKETS_PER_UNIT = 1000;
    /**
     * A RecipeMap can have hundreds of valid variants for one common output. Giving all of them to AE2 makes every
     * recursive branch fan out, which is worse than selecting a bounded set of the best direct candidates.
     */
    private static final int MAX_PATTERNS_PER_TARGET = 8;
    /**
     * Limits the work for a pathological common output even before candidates have been encoded. The output index is
     * ordered by RecipeMap registration order, so this is deliberately much larger than the exposed pattern limit.
     */
    private static final int MAX_RECIPES_PER_TARGET = 512;
    private static final String GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX = "metaitem.general_circuit.";

    private static final Map<IGrid, GridState> GRIDS = new ConcurrentHashMap<>();
    private static final Map<String, IGrid> PROVIDER_GRIDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> RECURSIVE_CYCLE_RECOVERY_REQUIRED = new ThreadLocal<>();
    private static final ThreadLocal<CraftingCalculation> ACTIVE_CRAFTING_CALCULATION = new ThreadLocal<>();
    /** Present only for the crafting calculation started by ApplyGray's explicit optimal rebuild action. */
    private static final ThreadLocal<OptimalRebuildContext> ACTIVE_OPTIMAL_REBUILD = new ThreadLocal<>();

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
        OptimalRebuildContext optimalRebuild = ACTIVE_OPTIMAL_REBUILD.get();
        if (optimalRebuild == null) return;

        long elapsedMillis = (System.nanoTime() - optimalRebuild.startedAt) / 1_000_000L;
        ApplyGrayMod.LOGGER.info("Finished Supergiant optimal rebuild: indexed {} active RecipeMaps from {} " +
                        "recipes in {} ms; inspected {} matching recipes for {} requested outputs and " +
                        "generated {} / reused {} dynamic patterns in {} ms; candidate priorities " +
                        "[dust/fluid={}, ingot={}, general={}, form change={}, recycling={}]",
                optimalRebuild.indexedRecipeMaps, optimalRebuild.indexedRecipes,
                optimalRebuild.indexRebuildMillis, optimalRebuild.matchingRecipeCandidates,
                optimalRebuild.requestedOutputs, optimalRebuild.generatedPatterns,
                optimalRebuild.reusedPatterns, elapsedMillis,
                optimalRebuild.dustOrFluidCandidates, optimalRebuild.ingotCandidates,
                optimalRebuild.generalCandidates, optimalRebuild.materialFormChangeCandidates,
                optimalRebuild.recyclingCandidates);
        ACTIVE_OPTIMAL_REBUILD.remove();
    }

    private static OptimalRebuildContext getActiveOptimalRebuild() {
        return ACTIVE_CRAFTING_CALCULATION.get() == null ? null : ACTIVE_OPTIMAL_REBUILD.get();
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
     * Invalidates the current plan's dynamic patterns and the output indexes used to find their replacements.
     * The next lazy lookup will rebuild each active RecipeMap index from its complete recipe list.
     *
     * @return the number of dynamic patterns removed from this grid
     */
    public static int invalidatePlanPatternsAndRecipeOutputIndexes(IGrid grid,
                                                                    Collection<? extends IPatternDetails> patterns) {
        GridState state = GRIDS.get(grid);
        if (state == null) return 0;
        return state.invalidatePlanPatternsAndRecipeOutputIndexes(patterns);
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
     * Rejects dynamic patterns that participated in a recursive chain without a positive net output.
     *
     * <p>The rejection is scoped to the requested key. The same recipe can still be useful when it is selected to
     * produce a different output.</p>
     */
    public static int rejectRecursiveCycle(AEKey target, Collection<? extends IPatternDetails> patterns) {
        if (target == null || patterns.isEmpty()) return 0;

        int removed = 0;
        for (GridState state : GRIDS.values()) {
            removed += state.rejectRecursiveCycle(target, patterns);
        }
        if (removed > 0) {
            RECURSIVE_CYCLE_RECOVERY_REQUIRED.set(Boolean.TRUE);
        }
        return removed;
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
     * <p>Unlike {@link #rejectRecursiveCycle(AEKey, Collection)}, this preserves the association between each
     * pattern and its own requested output. This prevents the reverse edge of a cycle from being selected again
     * through a different output lookup on the next calculation.</p>
     *
     * @return the number of dynamic patterns removed from the matching grid
     */
    public static int rejectRecursiveCycleAtOutput(AEKey target, IPatternDetails pattern) {
        if (target == null || pattern == null) return 0;
        int removed = 0;
        for (GridState state : GRIDS.values()) {
            removed += state.rejectRecursiveCycleAtOutput(target, pattern);
        }
        if (removed > 0) {
            RECURSIVE_CYCLE_RECOVERY_REQUIRED.set(Boolean.TRUE);
            ApplyGrayMod.LOGGER.info("Discarded {} lazy RecipeMap pattern(s) from a non-productive recursive " +
                    "cycle while producing {}", removed, target);
        }
        return removed;
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

    /** Orders virtual patterns by the input required per requested net output. */
    public static int compareDynamicPatternPriority(AEKey requested, DynamicRecipePatternDetails left,
                                                    DynamicRecipePatternDetails right) {
        long leftOutput = requested == null ? 0 : left.getNetOutputAmount(requested);
        long rightOutput = requested == null ? 0 : right.getNetOutputAmount(requested);
        int efficiency = compareInputOutputEfficiency(left.getRawMaterialCost(), leftOutput,
                right.getRawMaterialCost(), rightOutput);
        if (efficiency != 0) return efficiency;

        int steps = Integer.compare(left.getStepCost(), right.getStepCost());
        return steps != 0 ? steps : left.getRecipeKey().compareTo(right.getRecipeKey());
    }

    /** Exposes the first deterministic recipe output only, leaving all later outputs out of AE2's pattern result. */
    static List<GenericStack> selectPrimaryPatternOutputs(AEKey requested, List<GenericStack> recipeOutputs) {
        if (requested == null || recipeOutputs.isEmpty()) {
            return Collections.emptyList();
        }
        GenericStack primaryOutput = recipeOutputs.get(0);
        return requested.matches(primaryOutput) ? Collections.singletonList(primaryOutput) :
                Collections.emptyList();
    }

    static boolean isOreInputPrefix(String prefixName) {
        return prefixName.startsWith("ore") || prefixName.startsWith("rawOre");
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
        return false;
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
    private static CandidateRoutePriority getCandidateRoutePriority(AEKey target, Recipe recipe,
                                                                      EncodedRecipe encoded) {
        if (isRecyclingRecipe(recipe)) {
            return CandidateRoutePriority.RECYCLING;
        }

        boolean usesDustOrFluid = false;
        boolean usesIngot = false;
        Material targetMaterial = getMaterialForKey(target);
        boolean hasMaterialInput = false;
        boolean onlyTargetMaterialInputs = targetMaterial != null;

        for (GenericStack input : encoded.inputs) {
            AEKey inputKey = input.what();
            if (inputKey instanceof AEFluidKey) {
                usesDustOrFluid = true;
            } else if (inputKey instanceof AEItemKey itemKey) {
                UnificationEntry entry = OreDictUnifier.getUnificationEntry(itemKey.toStack());
                if (entry != null) {
                    String prefixName = entry.orePrefix.name();
                    usesDustOrFluid |= isDustPrefix(prefixName);
                    usesIngot |= isIngotPrefix(prefixName);
                }
            }

            Material inputMaterial = getMaterialForKey(inputKey);
            if (inputMaterial != null) {
                hasMaterialInput = true;
                if (targetMaterial == null || !targetMaterial.equals(inputMaterial)) {
                    onlyTargetMaterialInputs = false;
                }
            }
        }

        if (usesDustOrFluid) {
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
            }
        }

        private static boolean isRecipeMapAvailable(ProviderSnapshot snapshot, DynamicRecipePatternDetails detail) {
            for (RecipeMap<?> recipeMap : snapshot.recipeMaps) {
                if (recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName())) {
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
            // Ores are external resources. A parent recipe may consume them, but the lazy generator must never
            // attempt to make an ore itself through another RecipeMap pattern.
            if (isExternalOreInput(target)) {
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

        private List<DynamicRecipePatternDetails> createPatterns(AEKey target) {
            List<PatternCandidate> candidates = new ArrayList<>(MAX_PATTERNS_PER_TARGET);
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
                        List<GenericStack> patternOutputs = selectPrimaryPatternOutputs(target, encoded.outputs);
                        if (patternOutputs.isEmpty()) continue;
                        encoded = encoded.withOutputs(patternOutputs);
                        long netOutput = DynamicRecipePatternDetails.getNetOutputAmount(target, encoded.inputs,
                                encoded.alternatives, encoded.outputs);
                        if (netOutput <= 0) continue;
                        CandidateRoutePriority routePriority = getCandidateRoutePriority(target, recipe, encoded);
                        if (optimalRebuild != null) {
                            optimalRebuild.recordCandidate(routePriority);
                        }
                        // This ranking only affects pattern preference. Avoiding recursive cost evaluation keeps
                        // large RecipeMaps from turning one lookup into a full dependency scan.
                        Cost cost = Cost.fallback(recipe, storedItems, netOutput, routePriority);
                        PatternCandidate candidate = new PatternCandidate(source, recipeMap, recipe, encoded, cost);
                        if (!seenRecipeKeys.add(candidate.recipeKey)) continue;
                        if (isRejectedFor(target, candidate.recipeKey)) continue;
                        keepBestCandidate(candidates, candidate);
                    }
                }
            }

            if (cappedRecipeScan) {
                ApplyGrayMod.LOGGER.warn("Lazy RecipeMap pattern lookup for {} stopped after {} recipe candidates " +
                                "to keep the crafting calculation bounded",
                        target, MAX_RECIPES_PER_TARGET);
            }

            List<DynamicRecipePatternDetails> result = new ArrayList<>(candidates.size());
            for (int index = 0; index < candidates.size(); index++) {
                if (!cooperateWithCraftingCalculation()) {
                    return Collections.emptyList();
                }
                PatternCandidate candidate = candidates.get(index);
                DynamicRecipePatternDetails detail = candidate.source.provider
                        .getCachedDynamicPattern(candidate.recipeKey);
                if (detail != null && !detail.getOutputs().equals(candidate.encoded.outputs)) {
                    candidate.source.provider.removeCachedDynamicPattern(candidate.recipeKey);
                    detail = null;
                }
                if (detail == null) {
                    detail = new DynamicRecipePatternDetails(candidate.recipeKey,
                            candidate.recipeMap.getUnlocalizedName(), candidate.encoded.inputs,
                            candidate.encoded.alternatives, candidate.encoded.outputs,
                            candidate.encoded.circuitConfiguration,
                            candidate.cost.rawMaterials, candidate.cost.steps);
                    if (!isPatternAvailableFor(target, detail)) {
                        continue;
                    }
                    detail = candidate.source.provider.cacheDynamicPattern(detail);
                    if (!isPatternAvailableFor(target, detail)) {
                        continue;
                    }
                    if (optimalRebuild != null) {
                        optimalRebuild.generatedPatterns++;
                    }
                } else {
                    if (!isPatternAvailableFor(target, detail)) {
                        continue;
                    }
                    if (optimalRebuild != null) {
                        optimalRebuild.reusedPatterns++;
                    }
                }
                patternsByRecipe.put(candidate.recipeKey, detail);
                providersByPattern.put(detail, candidate.source.provider);
                result.add(detail);
            }
            return Collections.unmodifiableList(result);
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
                // A terminal or container query can call getCraftingFor on the server thread while the new plan is
                // being scheduled. Do not let that query consume the explicit rebuild before its calculation starts.
                if (calculation == null) {
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
                uniqueRecipeMaps.addAll(Arrays.asList(snapshot.recipeMaps));
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

        private static void keepBestCandidate(List<PatternCandidate> candidates, PatternCandidate candidate) {
            int insertionIndex = 0;
            while (insertionIndex < candidates.size() &&
                    compareCandidates(candidates.get(insertionIndex), candidate) <= 0) {
                insertionIndex++;
            }
            if (insertionIndex >= MAX_PATTERNS_PER_TARGET) {
                return;
            }

            candidates.add(insertionIndex, candidate);
            if (candidates.size() > MAX_PATTERNS_PER_TARGET) {
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

        private synchronized int invalidatePlanPatternsAndRecipeOutputIndexes(
                Collection<? extends IPatternDetails> patterns) {
            int clearedPatterns = invalidatePlanPatterns(patterns);
            int clearedIndexes = recipeOutputIndexes.size();
            requestFullRecipeOutputIndexRebuild();
            ApplyGrayMod.LOGGER.info("Cleared {} RecipeMap output indexes before rebuilding the Supergiant " +
                    "crafting calculation; the next calculation will rescan every active RecipeMap", clearedIndexes);
            return clearedPatterns;
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

        private synchronized int rejectRecursiveCycle(AEKey target,
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
            for (DynamicRecipePatternDetails detail : cyclePatterns) {
                rejected.add(detail.getRecipeKey());
            }

            int removed = invalidatePlanPatterns(cyclePatterns);
            if (removed > 0) {
                ApplyGrayMod.LOGGER.info("Discarded {} lazy RecipeMap patterns from a non-productive recursive " +
                        "cycle for {}", removed, target);
            }
            return removed;
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

            int removed = invalidatePlanPatterns(Collections.singleton(dynamic));
            return removed;
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
        if (!recipe.getChancedOutputs().getChancedEntries().isEmpty() ||
                !recipe.getChancedFluidOutputs().getChancedEntries().isEmpty()) return null;
        if (producesGeneralCircuitBoard(recipe)) return null;

        List<GenericStack> inputs = new ArrayList<>();
        List<List<GenericStack>> alternatives = new ArrayList<>();
        int circuitConfiguration = -1;
        int programmableNonConsumableInputs = 0;
        for (GTRecipeInput input : recipe.getInputs()) {
            if (input instanceof IntCircuitIngredient) {
                if (circuitConfiguration >= 0) return null;
                ItemStack[] circuits = input.getInputStacks();
                if (circuits.length == 0) return null;
                circuitConfiguration = IntCircuitIngredient.getCircuitConfiguration(circuits[0]);
                continue;
            }
            if (input.isNonConsumable()) {
                List<GenericStack> programmableOptions = encodeNonConsumableItem(input, storedItems);
                if (programmableOptions == null) return null;
                inputs.add(programmableOptions.get(0));
                alternatives.add(programmableOptions);
                programmableNonConsumableInputs++;
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
        return new EncodedRecipe(inputs, alternatives, outputs, circuitConfiguration,
                programmableNonConsumableInputs);
    }

    /**
     * Converts one non-consumable item requirement into the corresponding programmable circuit.
     * Non-consumable fluids and multi-count item requirements have no equivalent virtual circuit representation.
     */
    private static List<GenericStack> encodeNonConsumableItem(GTRecipeInput input, KeyCounter storedItems) {
        if (input.getInputFluidStack() != null || input.getAmount() != 1 ||
                MetaItems.PROGRAMMABLE_CIRCUIT == null) {
            return null;
        }

        ItemStack[] choices = input.getInputStacks();
        if (choices == null || choices.length == 0) return null;
        if (containsExternalOreInput(choices)) return null;

        List<GenericStack> programmableOptions = new ArrayList<>();
        for (ItemStack choice : prioritizeItemChoices(choices, storedItems)) {
            ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
            if (programmable.isEmpty()) return null;
            ProgrammableCircuit.wrap(choice, programmable);
            GenericStack genericProgrammable = GenericStack.fromItemStack(programmable);
            if (genericProgrammable != null) programmableOptions.add(genericProgrammable);
        }
        return programmableOptions.isEmpty() ? null : programmableOptions;
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
        private int dustOrFluidCandidates;
        private int ingotCandidates;
        private int generalCandidates;
        private int materialFormChangeCandidates;
        private int recyclingCandidates;

        private OptimalRebuildContext(int indexedRecipeMaps, int indexedRecipes, long indexRebuildMillis,
                                      long startedAt) {
            this.indexedRecipeMaps = indexedRecipeMaps;
            this.indexedRecipes = indexedRecipes;
            this.indexRebuildMillis = indexRebuildMillis;
            this.startedAt = startedAt;
        }

        private void recordCandidate(CandidateRoutePriority routePriority) {
            switch (routePriority) {
                case DUST_OR_FLUID_INPUT -> dustOrFluidCandidates++;
                case INGOT_INPUT -> ingotCandidates++;
                case GENERAL -> generalCandidates++;
                case MATERIAL_FORM_CHANGE -> materialFormChangeCandidates++;
                case RECYCLING -> recyclingCandidates++;
            }
        }
    }

    private static final class EncodedRecipe {
        private final List<GenericStack> inputs;
        private final List<List<GenericStack>> alternatives;
        private final List<GenericStack> outputs;
        private final int circuitConfiguration;
        private final int programmableNonConsumableInputs;

        private EncodedRecipe(List<GenericStack> inputs, List<List<GenericStack>> alternatives,
                              List<GenericStack> outputs,
                              int circuitConfiguration, int programmableNonConsumableInputs) {
            this.inputs = inputs;
            this.alternatives = alternatives;
            this.outputs = outputs;
            this.circuitConfiguration = circuitConfiguration;
            this.programmableNonConsumableInputs = programmableNonConsumableInputs;
        }

        private EncodedRecipe withOutputs(List<GenericStack> patternOutputs) {
            return new EncodedRecipe(inputs, alternatives, patternOutputs, circuitConfiguration,
                    programmableNonConsumableInputs);
        }
    }

    private static final class PatternCandidate {
        private final ProviderSnapshot source;
        private final RecipeMap<?> recipeMap;
        private final EncodedRecipe encoded;
        private final Cost cost;
        private final String recipeKey;

        private PatternCandidate(ProviderSnapshot source, RecipeMap<?> recipeMap, Recipe recipe,
                                 EncodedRecipe encoded, Cost cost) {
            this.source = source;
            this.recipeMap = recipeMap;
            this.encoded = encoded;
            this.cost = cost;
            this.recipeKey = source.providerId + ':' + recipeMap.getUnlocalizedName() + ':' + recipe.hashCode();
        }
    }

    enum CandidateRoutePriority {
        DUST_OR_FLUID_INPUT,
        INGOT_INPUT,
        GENERAL,
        MATERIAL_FORM_CHANGE,
        RECYCLING
    }

    static int compareCandidateRoutePriority(CandidateRoutePriority left, CandidateRoutePriority right) {
        return Integer.compare(left.ordinal(), right.ordinal());
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
