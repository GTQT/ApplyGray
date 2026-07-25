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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy bridge between AE2's requested-output lookup and active RecipeMap pattern providers.
 * Only recipes requested by an AE crafting calculation become virtual patterns.
 */
public final class DynamicRecipePatternRegistry {

    private static final int STANDARD_FLUID_MILLIBUCKETS_PER_UNIT = 1000;

    private static final Map<IGrid, GridState> GRIDS = new ConcurrentHashMap<>();
    private static final Map<String, IGrid> PROVIDER_GRIDS = new ConcurrentHashMap<>();

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
            if (oldState != null) oldState.removeProvider(providerId);
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
        if (state != null) state.removeProvider(providerId);
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

    public static void invalidateTarget(IGrid grid, AEKey target) {
        GridState state = GRIDS.get(grid);
        if (state == null || target == null) return;
        state.invalidateTarget(target);
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
        private final Map<IPatternDetails, ICraftingProvider> providersByPattern = new ConcurrentHashMap<>();

        private synchronized void putProvider(ProviderSnapshot snapshot) {
            ProviderSnapshot existing = providers.put(snapshot.providerId, snapshot);
            if (!snapshot.sameDefinition(existing)) clearGenerated();
        }

        private synchronized void removeProvider(String providerId) {
            if (providers.remove(providerId) != null) clearGenerated();
        }

        private List<IPatternDetails> findPatterns(AEKey target) {
            List<DynamicRecipePatternDetails> existing = patternsByTarget.get(target);
            if (existing == null) {
                synchronized (this) {
                    existing = patternsByTarget.get(target);
                    if (existing == null) {
                        existing = createPatterns(target);
                        patternsByTarget.put(target, existing);
                    }
                }
            }
            return new ArrayList<IPatternDetails>(existing);
        }

        private List<DynamicRecipePatternDetails> createPatterns(AEKey target) {
            List<PatternCandidate> candidates = new ArrayList<>();
            List<ProviderSnapshot> sources = new ArrayList<>(providers.values());
            for (ProviderSnapshot source : sources) {
                for (RecipeMap<?> recipeMap : source.recipeMaps) {
                    for (Recipe recipe : recipeMap.getRecipeList()) {
                        if (!recipeProduces(recipe, target)) continue;
                        EncodedRecipe encoded = encodeRecipe(source, recipe);
                        if (encoded == null) continue;
                        Cost cost = estimateRecipeCost(recipe, sources, 0, new HashSet<String>());
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
                    detail = new DynamicRecipePatternDetails(candidate.recipeKey,
                            candidate.recipeMap.getUnlocalizedName(), candidate.encoded.inputs,
                            candidate.encoded.alternatives, candidate.encoded.outputs,
                            candidate.encoded.circuitConfiguration,
                            candidate.cost.rawMaterials, candidate.cost.steps);
                    candidate.source.provider.cacheDynamicPattern(detail);
                    ApplyGrayMod.LOGGER.debug("Generated lazy RecipeMap pattern {} (raw={}, steps={}, programmableNc={})",
                            candidate.recipeKey, candidate.cost.rawMaterials, candidate.cost.steps,
                            candidate.encoded.programmableNonConsumableInputs);
                }
                patternsByRecipe.put(candidate.recipeKey, detail);
                providersByPattern.put(detail, candidate.source.provider);
                result.add(detail);
            }
            return Collections.unmodifiableList(result);
        }

        private synchronized void invalidateTarget(AEKey target) {
            patternsByTarget.remove(target);
            List<String> removeKeys = new ArrayList<>();
            for (Map.Entry<String, DynamicRecipePatternDetails> entry : patternsByRecipe.entrySet()) {
                if (entry.getValue().produces(target)) removeKeys.add(entry.getKey());
            }
            for (String key : removeKeys) {
                DynamicRecipePatternDetails detail = patternsByRecipe.remove(key);
                if (detail == null) continue;
                ICraftingProvider provider = providersByPattern.remove(detail);
                if (provider instanceof MetaTileEntityMERecipeMapPatternProvider) {
                    ((MetaTileEntityMERecipeMapPatternProvider) provider).removeCachedDynamicPattern(key);
                }
            }
            for (Map.Entry<AEKey, List<DynamicRecipePatternDetails>> entry : patternsByTarget.entrySet()) {
                List<DynamicRecipePatternDetails> retained = new ArrayList<>();
                for (DynamicRecipePatternDetails detail : entry.getValue()) {
                    if (!detail.produces(target)) retained.add(detail);
                }
                entry.setValue(Collections.unmodifiableList(retained));
            }
            ApplyGrayMod.LOGGER.info("Cleared lazy RecipeMap patterns for {}", target);
        }

        private void clearGenerated() {
            patternsByTarget.clear();
            patternsByRecipe.clear();
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
            for (ItemStack choice : choices) {
                if (choice.isEmpty()) continue;
                ItemStack option = choice.copy();
                option.setCount(input.getAmount());
                GenericStack genericOption = GenericStack.fromItemStack(option);
                if (genericOption != null) options.add(genericOption);
            }
            if (options.isEmpty()) return null;
            inputs.add(options.get(0));
            alternatives.add(options);
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
        for (ItemStack choice : choices) {
            if (choice.isEmpty()) continue;
            ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
            if (programmable.isEmpty()) return null;
            ProgrammableCircuit.wrap(choice, programmable);
            GenericStack genericProgrammable = GenericStack.fromItemStack(programmable);
            if (genericProgrammable != null) programmableOptions.add(genericProgrammable);
        }
        return programmableOptions.isEmpty() ? null : programmableOptions;
    }

    private static Cost estimateRecipeCost(Recipe recipe, Collection<ProviderSnapshot> sources, int depth,
                                           Set<String> visiting) {
        if (depth >= 12) return Cost.fallback(recipe);
        Cost total = new Cost(0, 1);
        for (GTRecipeInput input : recipe.getInputs()) {
            if (input instanceof IntCircuitIngredient || input.isNonConsumable()) continue;
            FluidStack fluid = input.getInputFluidStack();
            if (fluid != null) {
                total.rawMaterials += estimateFluidRawMaterialCost(fluid, input.getAmount());
                continue;
            }
            ItemStack[] choices = input.getInputStacks();
            if (choices.length == 0) return Cost.fallback(recipe);
            ItemStack required = choices[0].copy();
            required.setCount(input.getAmount());
            total.add(estimateItemCost(required, sources, depth + 1, visiting));
        }
        return total;
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

    private static Cost estimateItemCost(ItemStack required, Collection<ProviderSnapshot> sources, int depth,
                                         Set<String> visiting) {
        String key = stackKey(required);
        if (!visiting.add(key)) return new Cost(required.getCount(), 0);
        try {
            Cost best = null;
            for (ProviderSnapshot source : sources) {
                for (RecipeMap<?> recipeMap : source.recipeMaps) {
                    for (Recipe candidate : recipeMap.getRecipeList()) {
                        if (!recipeProduces(candidate, AEItemKey.of(required)) || encodeRecipe(source, candidate) == null) {
                            continue;
                        }
                        int outputAmount = matchingOutputAmount(candidate, required);
                        if (outputAmount <= 0) continue;
                        Cost candidateCost = estimateRecipeCost(candidate, sources, depth, visiting);
                        candidateCost.multiply((required.getCount() + outputAmount - 1L) / outputAmount);
                        if (best == null || candidateCost.compareTo(best) < 0) best = candidateCost;
                    }
                }
            }
            return best == null ? new Cost(required.getCount(), 0) : best;
        } finally {
            visiting.remove(key);
        }
    }

    private static int matchingOutputAmount(Recipe recipe, ItemStack requested) {
        int amount = 0;
        for (ItemStack output : recipe.getOutputs()) {
            if (ItemStack.areItemsEqual(output, requested) && ItemStack.areItemStackTagsEqual(output, requested)) {
                amount += output.getCount();
            }
        }
        return amount;
    }

    private static String stackKey(ItemStack stack) {
        return String.valueOf(stack.getItem().getRegistryName()) + '@' + stack.getMetadata() + ':' +
                (stack.hasTagCompound() ? stack.getTagCompound().toString() : "");
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
            return new Cost(raw, 1);
        }

        private void add(Cost other) {
            rawMaterials = Math.min(Long.MAX_VALUE / 4, rawMaterials + other.rawMaterials);
            steps = Math.min(Integer.MAX_VALUE / 4, steps + other.steps);
        }

        private void multiply(long multiplier) {
            rawMaterials = Math.min(Long.MAX_VALUE / 4, rawMaterials * multiplier);
            steps = (int) Math.min(Integer.MAX_VALUE / 4, (long) steps * multiplier);
        }

        @Override
        public int compareTo(Cost other) {
            int rawCompare = Long.compare(rawMaterials, other.rawMaterials);
            return rawCompare != 0 ? rawCompare : Integer.compare(steps, other.steps);
        }
    }
}
