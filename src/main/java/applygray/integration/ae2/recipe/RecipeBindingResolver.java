package applygray.integration.ae2.recipe;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves bindings against immutable RecipeMap snapshots. RecipeMap mutations must invalidate this cache before
 * new work is accepted; the RecipeMap mixin performs that invalidation for normal add/remove paths.
 */
public final class RecipeBindingResolver {

    private static final ConcurrentMap<RecipeMap<?>, RecipeMapSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private RecipeBindingResolver() {
    }

    public static RecipeMapSnapshot snapshot(RecipeMap<?> recipeMap) {
        return SNAPSHOTS.computeIfAbsent(recipeMap, RecipeMapSnapshot::create);
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
        if (!binding.getRecipeMapContentVersion().equals(snapshot.getContentVersion())) {
            return Resolution.rejected("RECIPE_MAP_CONTENT_CHANGED");
        }
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
            Collection<Recipe> source = recipeMap.getRecipeList();
            List<Recipe> recipes = Collections.unmodifiableList(new ArrayList<>(source));
            String contentVersion = RecipeFingerprint.contentVersion(recipeMap, recipes);
            Map<Recipe, Integer> indexes = new IdentityHashMap<>();
            Map<String, List<Recipe>> byFingerprint = new LinkedHashMap<>();
            for (int index = 0; index < recipes.size(); index++) {
                Recipe recipe = recipes.get(index);
                indexes.put(recipe, index);
                String fingerprint = RecipeFingerprint.fingerprint(recipeMap.getUnlocalizedName(), recipe, index);
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

        public int getRecipeCount() {
            return recipes.size();
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
