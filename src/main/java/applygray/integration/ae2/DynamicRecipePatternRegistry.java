package applygray.integration.ae2;

import applygray.ApplyGrayMod;

import gregtech.api.GTValues;
import gregtech.api.fluids.store.FluidStorageKeys;
import gregtech.api.unification.FluidUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.ingredients.IntCircuitIngredient;
import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IGrid;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy bridge between AE2's requested-output lookup and active RecipeMap pattern providers.
 * Only recipes requested by an AE crafting calculation become virtual patterns.
 */
public final class DynamicRecipePatternRegistry {

    private static final int STANDARD_FLUID_MILLIBUCKETS_PER_UNIT = 1000;
    private static final String GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX = "metaitem.general_circuit.";

    private static final Map<IGrid, GridState> GRIDS = new ConcurrentHashMap<>();
    private static final Map<String, IGrid> PROVIDER_GRIDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> RECURSIVE_CYCLE_RECOVERY_REQUIRED = new ThreadLocal<>();

    private DynamicRecipePatternRegistry() {}

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
        // Retired dynamic details must remain resolvable while an already-submitted CPU still holds them.
        // Weak keys release the association once no plan or CPU references the old detail anymore.
        private final Map<IPatternDetails, ICraftingProvider> providersByPattern =
                Collections.synchronizedMap(new WeakHashMap<>());

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
            if (Thread.currentThread().isInterrupted()) {
                return Collections.emptyList();
            }

            List<DynamicRecipePatternDetails> existing = patternsByTarget.get(target);
            if (existing == null) {
                long startedAt = System.nanoTime();
                List<DynamicRecipePatternDetails> generated = createPatterns(target);
                if (Thread.currentThread().isInterrupted()) {
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
            for (DynamicRecipePatternDetails detail : existing) {
                if (isPatternAvailableFor(target, detail)) {
                    available.add(detail);
                }
            }
            return available;
        }

        private List<DynamicRecipePatternDetails> createPatterns(AEKey target) {
            List<PatternCandidate> candidates = new ArrayList<>();
            List<ProviderSnapshot> sources = new ArrayList<>(providers.values());
            int scannedRecipes = 0;
            for (ProviderSnapshot source : sources) {
                for (RecipeMap<?> recipeMap : source.recipeMaps) {
                    for (Recipe recipe : recipeMap.getRecipeList()) {
                        if ((scannedRecipes++ & 63) == 0 && Thread.currentThread().isInterrupted()) {
                            return Collections.emptyList();
                        }
                        if (!recipeProduces(recipe, target)) continue;
                        EncodedRecipe encoded = encodeRecipe(source, recipe);
                        if (encoded == null) continue;
                        // This ranking only affects pattern preference. A recursive full-recipe scan here can hold
                        // up the crafting calculation for minutes on large RecipeMaps.
                        Cost cost = Cost.fallback(recipe);
                        candidates.add(new PatternCandidate(source, recipeMap, recipe, encoded, cost));
                    }
                }
            }

            candidates.sort(Comparator
                    .comparingLong((PatternCandidate candidate) -> candidate.cost.rawMaterials)
                    .thenComparingInt(candidate -> candidate.cost.steps)
                    .thenComparing(candidate -> candidate.recipeKey));

            List<DynamicRecipePatternDetails> result = new ArrayList<>();
            for (PatternCandidate candidate : candidates) {
                DynamicRecipePatternDetails detail = candidate.source.provider
                        .getCachedDynamicPattern(candidate.recipeKey);
                if (detail == null) {
                    if (!DynamicRecipePatternDetails.hasNetOutput(target, candidate.encoded.inputs,
                            candidate.encoded.alternatives, candidate.encoded.outputs)) {
                        continue;
                    }
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
                    ApplyGrayMod.LOGGER.debug("Generated lazy RecipeMap pattern {} (raw={}, steps={}, programmableNc={})",
                            candidate.recipeKey, candidate.cost.rawMaterials, candidate.cost.steps,
                            candidate.encoded.programmableNonConsumableInputs);
                } else if (!isPatternAvailableFor(target, detail)) {
                    continue;
                }
                patternsByRecipe.put(candidate.recipeKey, detail);
                providersByPattern.put(detail, candidate.source.provider);
                result.add(detail);
            }
            return Collections.unmodifiableList(result);
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

            List<AEKey> affectedTargets = new ArrayList<>();
            for (Map.Entry<AEKey, List<DynamicRecipePatternDetails>> entry : patternsByTarget.entrySet()) {
                for (DynamicRecipePatternDetails detail : entry.getValue()) {
                    if (removedPatterns.contains(detail)) {
                        affectedTargets.add(entry.getKey());
                        break;
                    }
                }
            }
            for (AEKey target : affectedTargets) {
                patternsByTarget.remove(target);
            }
            return removedPatterns.size();
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

        private boolean isPatternAvailableFor(AEKey target, DynamicRecipePatternDetails detail) {
            return detail.netProduces(target) && !isRejectedFor(target, detail);
        }

        private boolean isRejectedFor(AEKey target, DynamicRecipePatternDetails detail) {
            Set<String> rejected = rejectedRecipeKeysByTarget.get(target);
            return rejected != null && rejected.contains(detail.getRecipeKey());
        }

        private void clearGenerated() {
            patternsByTarget.clear();
            patternsByRecipe.clear();
            rejectedRecipeKeysByTarget.clear();
            providersByPattern.clear();
        }
    }

    private static boolean recipeProduces(Recipe recipe, AEKey target) {
        if (!recipe.getChancedOutputs().getChancedEntries().isEmpty() ||
                !recipe.getChancedFluidOutputs().getChancedEntries().isEmpty()) {
            return false;
        }
        for (ItemStack output : recipe.getOutputs()) {
            AEItemKey outputKey = AEItemKey.of(output);
            if (target.equals(outputKey)) {
                return true;
            }
        }
        for (FluidStack output : recipe.getFluidOutputs()) {
            AEFluidKey outputKey = AEFluidKey.of(output);
            if (target.equals(outputKey)) {
                return true;
            }
        }
        return false;
    }

    private static EncodedRecipe encodeRecipe(ProviderSnapshot source, Recipe recipe) {
        if (!recipe.getChancedOutputs().getChancedEntries().isEmpty() ||
                !recipe.getChancedFluidOutputs().getChancedEntries().isEmpty()) return null;

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
                List<GenericStack> programmableOptions = encodeNonConsumableItem(input);
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
            List<GenericStack> options = new ArrayList<>();
            for (ItemStack choice : prioritizeGeneralCircuitBoards(choices)) {
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
    private static List<GenericStack> encodeNonConsumableItem(GTRecipeInput input) {
        if (input.getInputFluidStack() != null || input.getAmount() != 1 ||
                MetaItems.PROGRAMMABLE_CIRCUIT == null) {
            return null;
        }

        ItemStack[] choices = input.getInputStacks();
        if (choices == null || choices.length == 0) return null;

        List<GenericStack> programmableOptions = new ArrayList<>();
        for (ItemStack choice : prioritizeGeneralCircuitBoards(choices)) {
            ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
            if (programmable.isEmpty()) return null;
            ProgrammableCircuit.wrap(choice, programmable);
            GenericStack genericProgrammable = GenericStack.fromItemStack(programmable);
            if (genericProgrammable != null) programmableOptions.add(genericProgrammable);
        }
        return programmableOptions.isEmpty() ? null : programmableOptions;
    }

    /**
     * Dynamic patterns use the first alternative as their default AE input. Prefer the universal circuit board
     * variants when a RecipeMap input accepts one, while retaining every other valid alternative.
     */
    private static List<ItemStack> prioritizeGeneralCircuitBoards(ItemStack[] choices) {
        List<ItemStack> generalCircuitBoards = new ArrayList<>(choices.length);
        List<ItemStack> otherChoices = new ArrayList<>(choices.length);
        for (ItemStack choice : choices) {
            if (choice == null || choice.isEmpty()) continue;
            (isGeneralCircuitBoard(choice) ? generalCircuitBoards : otherChoices).add(choice);
        }
        generalCircuitBoards.addAll(otherChoices);
        return generalCircuitBoards;
    }

    private static boolean isGeneralCircuitBoard(ItemStack stack) {
        String translationKey = stack.getTranslationKey();
        return translationKey.startsWith(GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX) ||
                translationKey.startsWith("item." + GENERAL_CIRCUIT_TRANSLATION_KEY_PREFIX);
    }

    private static long estimateFluidRawMaterialCost(FluidStack fluid, int amount) {
        int millibucketsPerUnit = isMoltenMaterialFluid(fluid) ?
                GTValues.L : STANDARD_FLUID_MILLIBUCKETS_PER_UNIT;
        return Math.max(1L, (long) amount / millibucketsPerUnit);
    }

    private static boolean isMoltenMaterialFluid(FluidStack fluidStack) {
        Material material = FluidUnifier.getMaterialFromFluid(fluidStack.getFluid());
        return material != null && material.hasFluid() &&
                material.getFluid(FluidStorageKeys.MOLTEN) == fluidStack.getFluid();
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

    private static final class Cost implements Comparable<Cost> {
        private long rawMaterials;
        private int steps;

        private Cost(long rawMaterials, int steps) {
            this.rawMaterials = rawMaterials;
            this.steps = steps;
        }

        private static Cost fallback(Recipe recipe) {
            long raw = 0;
            for (GTRecipeInput input : recipe.getInputs()) {
                if (input instanceof IntCircuitIngredient || input.isNonConsumable()) continue;
                FluidStack fluid = input.getInputFluidStack();
                raw += fluid == null ? input.getAmount() : estimateFluidRawMaterialCost(fluid, input.getAmount());
            }
            for (GTRecipeInput input : recipe.getFluidInputs()) {
                if (input.isNonConsumable()) continue;
                FluidStack fluid = input.getInputFluidStack();
                if (fluid != null) raw += estimateFluidRawMaterialCost(fluid, input.getAmount());
            }
            return new Cost(raw, 1);
        }

        @Override
        public int compareTo(Cost other) {
            int rawCompare = Long.compare(rawMaterials, other.rawMaterials);
            return rawCompare != 0 ? rawCompare : Integer.compare(steps, other.steps);
        }
    }
}
