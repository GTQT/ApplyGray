package applygray.integration.ae2.recipe;

import applygray.ApplyGrayMod;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.machines.RecipeMapFurnace;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.OreDictionary;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves bindings against immutable RecipeMap snapshots. RecipeMap mutations invalidate this cache before new
 * work is accepted; already-buffered work remains valid only when its exact recipe fingerprint is still present.
 * The RecipeMap mixin performs the invalidation for normal add/remove paths.
 */
public final class RecipeBindingResolver {

    private static final ConcurrentMap<RecipeMap<?>, RecipeMapSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    /**
     * RecipeMapFurnace deliberately creates Vanilla furnace recipes only during lookup. Keep the resulting recipes
     * in a main-thread snapshot so asynchronous AE2 planning can index the same fallback route safely.
     */
    private static final ConcurrentMap<RecipeMap<?>, List<Recipe>> RUNTIME_FURNACE_FALLBACKS =
            new ConcurrentHashMap<>();
    private static final Set<RecipeMap<?>> DIRTY_RUNTIME_FURNACE_MAPS = ConcurrentHashMap.newKeySet();
    private static final Set<RecipeMap<?>> FURNACE_CAPTURE_FAILURES_LOGGED = ConcurrentHashMap.newKeySet();
    private RecipeBindingResolver() {
    }

    public static RecipeMapSnapshot snapshot(RecipeMap<?> recipeMap) {
        return SNAPSHOTS.computeIfAbsent(recipeMap, RecipeBindingResolver::createSnapshot);
    }

    public static void register(RecipeMap<?> recipeMap, RecipeMapSnapshot snapshot) {
        if (recipeMap != null && snapshot != null) SNAPSHOTS.put(recipeMap, snapshot);
    }

    public static void invalidate(RecipeMap<?> recipeMap) {
        if (recipeMap != null) SNAPSHOTS.remove(recipeMap);
    }

    public static void invalidateAll() {
        SNAPSHOTS.clear();
    }

    /**
     * Captures lookup-only Vanilla furnace fallbacks before a provider snapshot crosses into AE2's async planner.
     * The returned maps changed content and require their output indexes and persisted dynamic patterns to be reset.
     */
    public static List<RecipeMap<?>> captureMainThreadRuntimeRecipes(RecipeMap<?>[] recipeMaps) {
        if (recipeMaps == null || recipeMaps.length == 0) return Collections.emptyList();

        List<RecipeMap<?>> changed = new ArrayList<>();
        for (RecipeMap<?> recipeMap : recipeMaps) {
            if (!(recipeMap instanceof RecipeMapFurnace)) continue;
            if (captureRuntimeFurnaceFallbacks(recipeMap)) changed.add(recipeMap);
        }
        return changed.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(changed);
    }

    /**
     * Must be called when Vanilla smelting registrations change after providers have already begun planning.
     * Existing bindings become unavailable until the owning provider captures a replacement main-thread snapshot.
     */
    public static List<RecipeMap<?>> invalidateRuntimeFurnaceFallbacks() {
        List<RecipeMap<?>> invalidated = new ArrayList<>();
        for (RecipeMap<?> recipeMap : RUNTIME_FURNACE_FALLBACKS.keySet()) {
            if (!(recipeMap instanceof RecipeMapFurnace)) continue;
            DIRTY_RUNTIME_FURNACE_MAPS.add(recipeMap);
            RUNTIME_FURNACE_FALLBACKS.remove(recipeMap);
            SNAPSHOTS.remove(recipeMap);
            invalidated.add(recipeMap);
        }
        return invalidated.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(invalidated);
    }

    public static Resolution resolve(RecipeBinding binding, @Nullable RecipeMap<?> recipeMap) {
        if (binding == null) return Resolution.rejected("MISSING_BINDING");
        if (recipeMap == null || !binding.isForRecipeMap(recipeMap.getUnlocalizedName())) {
            return Resolution.rejected("RECIPE_MAP_MISMATCH");
        }
        if (binding.getRecipeFingerprintVersion() != RecipeBinding.FINGERPRINT_VERSION ||
                binding.getNormalizationVersion() != RecipeBinding.NORMALIZATION_VERSION) {
            return Resolution.rejected("UNSUPPORTED_BINDING_VERSION");
        }

        RecipeMapSnapshot snapshot = snapshot(recipeMap);
        // The map-level content version is a cache epoch, not the identity of this request. A late registration of
        // an unrelated recipe must not strand a complete buffered request. Version 4 fingerprints the canonical
        // recipe content directly, while an ambiguous content fingerprint remains unsafe to execute.
        List<Recipe> candidates = snapshot.getRecipes(binding.getRecipeFingerprint());
        if (candidates.isEmpty()) return Resolution.rejected("BINDING_RECIPE_NOT_FOUND");
        if (candidates.size() != 1) return Resolution.rejected("BINDING_FINGERPRINT_AMBIGUOUS");
        Recipe recipe = candidates.get(0);
        NormalizedRecipe normalized = snapshot.normalize(recipe);
        if (normalized == null || !producesBoundTarget(normalized, binding)) {
            return Resolution.rejected("BINDING_TARGET_NOT_DETERMINISTIC_OUTPUT");
        }
        return Resolution.resolved(recipe);
    }

    private static RecipeMapSnapshot createSnapshot(RecipeMap<?> recipeMap) {
        List<Recipe> runtimeFallbacks = RUNTIME_FURNACE_FALLBACKS.get(recipeMap);
        return RecipeMapSnapshot.create(recipeMap,
                runtimeFallbacks == null ? Collections.emptyList() : runtimeFallbacks);
    }

    /** Builds fallback recipes through RecipeMapFurnace itself, preserving its static-recipe-first lookup semantics. */
    private static boolean captureRuntimeFurnaceFallbacks(RecipeMap<?> recipeMap) {
        boolean hasCapturedFallbacks = RUNTIME_FURNACE_FALLBACKS.containsKey(recipeMap);
        if (hasCapturedFallbacks && !DIRTY_RUNTIME_FURNACE_MAPS.remove(recipeMap)) return false;

        try {
            Set<Recipe> registeredRecipes = Collections.newSetFromMap(new IdentityHashMap<>());
            registeredRecipes.addAll(recipeMap.getRecipeList());

            List<Map.Entry<ItemStack, ItemStack>> furnaceEntries = new ArrayList<>(
                    FurnaceRecipes.instance().getSmeltingList().entrySet());
            furnaceEntries.removeIf(entry -> entry.getKey() == null || entry.getKey().isEmpty() ||
                    entry.getKey().getMetadata() == OreDictionary.WILDCARD_VALUE ||
                    entry.getValue() == null || entry.getValue().isEmpty());
            furnaceEntries.sort(Comparator
                    .comparing((Map.Entry<ItemStack, ItemStack> entry) -> describeStack(entry.getKey()))
                    .thenComparing(entry -> describeStack(entry.getValue())));

            List<Recipe> fallbacks = new ArrayList<>(furnaceEntries.size());
            for (Map.Entry<ItemStack, ItemStack> entry : furnaceEntries) {
                ItemStack input = entry.getKey().copy();
                input.setCount(1);
                Recipe resolved = recipeMap.findRecipe(Long.MAX_VALUE, Collections.singletonList(input),
                        Collections.emptyList(), false);
                // A registered GT furnace recipe wins before RecipeMapFurnace reaches its Vanilla fallback.
                if (resolved != null && !registeredRecipes.contains(resolved)) fallbacks.add(resolved);
            }

            List<Recipe> immutableFallbacks = Collections.unmodifiableList(new ArrayList<>(fallbacks));
            RecipeMapSnapshot refreshed = RecipeMapSnapshot.create(recipeMap, immutableFallbacks);
            RecipeMapSnapshot previous = SNAPSHOTS.put(recipeMap, refreshed);
            RUNTIME_FURNACE_FALLBACKS.put(recipeMap, immutableFallbacks);
            DIRTY_RUNTIME_FURNACE_MAPS.remove(recipeMap);
            FURNACE_CAPTURE_FAILURES_LOGGED.remove(recipeMap);

            boolean changed = previous == null || !previous.getContentVersion().equals(refreshed.getContentVersion());
            if (changed) {
                ApplyGrayMod.LOGGER.info("Captured {} Vanilla furnace fallback recipe(s) for RecipeMap {}; " +
                                "dynamic pattern planning can now index direct furnace routes",
                        immutableFallbacks.size(), recipeMap.getUnlocalizedName());
            }
            return changed;
        } catch (RuntimeException exception) {
            DIRTY_RUNTIME_FURNACE_MAPS.add(recipeMap);
            if (FURNACE_CAPTURE_FAILURES_LOGGED.add(recipeMap)) {
                ApplyGrayMod.LOGGER.warn("Could not capture Vanilla furnace fallback recipes for RecipeMap {}; " +
                                "direct furnace routes will remain unavailable until the next successful capture",
                        recipeMap.getUnlocalizedName(), exception);
            }
            return false;
        }
    }

    private static String describeStack(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" :
                RecipeFingerprint.canonicalNbt(stack.writeToNBT(new NBTTagCompound()));
    }

    private static boolean producesBoundTarget(NormalizedRecipe recipe, RecipeBinding binding) {
        for (var output : recipe.getDeterministicOutputs()) {
            if (output.amount() > 0 && binding.getTargetKey().equals(RecipeFingerprint.describeKey(output.what()))) {
                return true;
            }
        }
        return false;
    }

    public static final class RecipeMapSnapshot {

        private final RecipeMap<?> recipeMap;
        private final List<Recipe> recipes;
        private final Map<Recipe, Integer> registrationIndexes;
        private final Map<String, List<Recipe>> recipesByFingerprint;
        private final String contentVersion;

        private RecipeMapSnapshot(RecipeMap<?> recipeMap, List<Recipe> recipes,
                                  Map<Recipe, Integer> registrationIndexes,
                                   Map<String, List<Recipe>> recipesByFingerprint, String contentVersion) {
            this.recipeMap = recipeMap;
            this.recipes = recipes;
            this.registrationIndexes = registrationIndexes;
            this.recipesByFingerprint = recipesByFingerprint;
            this.contentVersion = contentVersion;
        }

        public static RecipeMapSnapshot create(RecipeMap<?> recipeMap) {
            return create(recipeMap, Collections.emptyList());
        }

        static RecipeMapSnapshot create(RecipeMap<?> recipeMap, Collection<Recipe> runtimeFallbacks) {
            List<Recipe> mutableRecipes = new ArrayList<>(recipeMap.getRecipeList());
            Set<Recipe> knownRecipes = Collections.newSetFromMap(new IdentityHashMap<>());
            knownRecipes.addAll(mutableRecipes);
            if (runtimeFallbacks != null) {
                for (Recipe recipe : runtimeFallbacks) {
                    if (recipe != null && knownRecipes.add(recipe)) mutableRecipes.add(recipe);
                }
            }
            // RecipeMap#getRecipeList has a lookup-derived order. Sort by canonical content so cache epochs remain
            // deterministic across equivalent map reloads.
            String recipeMapId = recipeMap.getUnlocalizedName();
            Map<Recipe, String> sortKeys = new IdentityHashMap<>();
            for (Recipe recipe : mutableRecipes) {
                sortKeys.put(recipe, RecipeFingerprint.contentFingerprint(recipeMapId, recipe));
            }
            mutableRecipes.sort(Comparator.comparing(sortKeys::get));
            List<Recipe> recipes = Collections.unmodifiableList(mutableRecipes);
            String contentVersion = RecipeFingerprint.contentVersion(recipeMap, recipes);
            Map<Recipe, Integer> indexes = new IdentityHashMap<>();
            Map<String, List<Recipe>> byFingerprint = new LinkedHashMap<>();
            for (int index = 0; index < recipes.size(); index++) {
                Recipe recipe = recipes.get(index);
                indexes.put(recipe, index);
                String fingerprint = RecipeFingerprint.fingerprint(recipeMap.getUnlocalizedName(), recipe);
                byFingerprint.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(recipe);
            }
            Map<String, List<Recipe>> immutableByFingerprint = new LinkedHashMap<>();
            for (Map.Entry<String, List<Recipe>> entry : byFingerprint.entrySet()) {
                immutableByFingerprint.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            return new RecipeMapSnapshot(recipeMap, recipes, Collections.unmodifiableMap(indexes),
                    Collections.unmodifiableMap(immutableByFingerprint), contentVersion);
        }

        public String getContentVersion() {
            return contentVersion;
        }

        public List<Recipe> getRecipes() {
            return recipes;
        }

        public List<Recipe> getRecipes(String fingerprint) {
            List<Recipe> matches = recipesByFingerprint.get(fingerprint);
            return matches == null ? Collections.emptyList() : matches;
        }

        @Nullable
        public NormalizedRecipe normalize(Recipe recipe) {
            Integer registrationIndex = registrationIndexes.get(recipe);
            return registrationIndex == null ? null : NormalizedRecipe.from(recipeMap, recipe, registrationIndex,
                    contentVersion);
        }
    }

    public static final class Resolution {

        @Nullable
        private final Recipe recipe;
        private final String reasonCode;

        private Resolution(@Nullable Recipe recipe, String reasonCode) {
            this.recipe = recipe;
            this.reasonCode = reasonCode;
        }

        private static Resolution resolved(Recipe recipe) {
            return new Resolution(recipe, "OK");
        }

        private static Resolution rejected(String reasonCode) {
            return new Resolution(null, reasonCode);
        }

        public boolean isResolved() {
            return recipe != null;
        }

        @Nullable
        public Recipe getRecipe() {
            return recipe;
        }

        public String getReasonCode() {
            return reasonCode;
        }
    }
}
