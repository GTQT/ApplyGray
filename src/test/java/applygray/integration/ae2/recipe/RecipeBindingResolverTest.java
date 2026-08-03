package applygray.integration.ae2.recipe;

import gregtech.api.GregTechAPI;
import gregtech.api.modules.IModuleContainer;
import gregtech.api.modules.IModuleManager;
import gregtech.api.modules.ModuleStage;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.RecipeMapBuilder;
import gregtech.api.recipes.builders.SimpleRecipeBuilder;
import gregtech.api.recipes.chance.output.ChancedOutputList;
import gregtech.api.recipes.ingredients.GTRecipeItemInput;
import gregtech.api.recipes.properties.RecipePropertyStorageImpl;

import ae2.api.stacks.AEItemKey;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeBindingResolverTest {

    private static IModuleManager previousModuleManager;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
        previousModuleManager = GregTechAPI.moduleManager;
        GregTechAPI.moduleManager = new IModuleManager() {
            @Override
            public boolean isModuleEnabled(ResourceLocation id) {
                return false;
            }

            @Override
            public void registerContainer(IModuleContainer container) {
            }

            @Override
            public IModuleContainer getLoadedContainer() {
                return null;
            }

            @Override
            public ModuleStage getStage() {
                return ModuleStage.values()[0];
            }

            @Override
            public boolean hasPassedStage(ModuleStage stage) {
                return false;
            }
        };
    }

    @AfterAll
    static void restoreModuleManager() {
        GregTechAPI.moduleManager = previousModuleManager;
    }

    @Test
    void snapshotFingerprintsDoNotDependOnRecipeLookupOrder() {
        RecipeMap<SimpleRecipeBuilder> recipeMap = new RecipeMapBuilder<>(
                "applygray_binding_snapshot_" + System.nanoTime(), new SimpleRecipeBuilder())
                .itemInputs(1)
                .itemOutputs(1)
                .build();
        Recipe first = recipe(recipeMap, Items.IRON_INGOT, Items.GOLD_INGOT);
        Recipe second = recipe(recipeMap, Items.DIAMOND, Items.EMERALD);

        RecipeBindingResolver.RecipeMapSnapshot inOriginalOrder =
                RecipeBindingResolver.RecipeMapSnapshot.create(recipeMap, List.of(first, second));
        RecipeBindingResolver.RecipeMapSnapshot inReverseOrder =
                RecipeBindingResolver.RecipeMapSnapshot.create(recipeMap, List.of(second, first));

        assertEquals(inOriginalOrder.getContentVersion(), inReverseOrder.getContentVersion());
        assertEquals(fingerprints(inOriginalOrder), fingerprints(inReverseOrder));
    }

    @Test
    void rejectsAnOlderBindingAlgorithmEvenWhenThePersistedDetailIsOtherwiseReadable() {
        RecipeMap<SimpleRecipeBuilder> recipeMap = new RecipeMapBuilder<>(
                "applygray_binding_version_" + System.nanoTime(), new SimpleRecipeBuilder())
                .itemInputs(1)
                .itemOutputs(1)
                .build();
        Recipe recipe = recipe(recipeMap, Items.IRON_INGOT, Items.GOLD_INGOT);
        RecipeBindingResolver.RecipeMapSnapshot snapshot =
                RecipeBindingResolver.RecipeMapSnapshot.create(recipeMap, List.of(recipe));
        NormalizedRecipe normalized = snapshot.normalize(recipe);
        RecipeBinding oldAlgorithmBinding = new RecipeBinding(recipeMap.getUnlocalizedName(), 2,
                normalized.getRecipeFingerprint(), normalized.getRecipeMapContentVersion(),
                RecipeFingerprint.describeKey(AEItemKey.of(new ItemStack(Items.GOLD_INGOT))),
                RecipeBinding.NORMALIZATION_VERSION, "rules", "machine");

        RecipeBindingResolver.register(recipeMap, snapshot);
        try {
            RecipeBindingResolver.Resolution resolution = RecipeBindingResolver.resolve(oldAlgorithmBinding, recipeMap);
            assertFalse(resolution.isResolved());
            assertEquals("UNSUPPORTED_BINDING_VERSION", resolution.getReasonCode());
        } finally {
            RecipeBindingResolver.invalidate(recipeMap);
        }
    }

    @Test
    void resolvesAnExactPersistedBindingDespiteAnOlderPlanningContext() {
        RecipeMap<SimpleRecipeBuilder> recipeMap = new RecipeMapBuilder<>(
                "applygray_binding_context_" + System.nanoTime(), new SimpleRecipeBuilder())
                .itemInputs(1)
                .itemOutputs(1)
                .build();
        Recipe recipe = recipe(recipeMap, Items.IRON_INGOT, Items.GOLD_INGOT);
        RecipeBindingResolver.RecipeMapSnapshot snapshot =
                RecipeBindingResolver.RecipeMapSnapshot.create(recipeMap, List.of(recipe));
        NormalizedRecipe normalized = snapshot.normalize(recipe);
        RecipeBinding persistedBinding = new RecipeBinding(recipeMap.getUnlocalizedName(),
                RecipeBinding.FINGERPRINT_VERSION, normalized.getRecipeFingerprint(),
                normalized.getRecipeMapContentVersion(),
                RecipeFingerprint.describeKey(AEItemKey.of(new ItemStack(Items.GOLD_INGOT))),
                RecipeBinding.NORMALIZATION_VERSION, "rules-before-restart", "profile-before-restart");

        RecipeBindingResolver.register(recipeMap, snapshot);
        try {
            assertTrue(RecipeBindingResolver.resolve(persistedBinding, recipeMap).isResolved());
        } finally {
            RecipeBindingResolver.invalidate(recipeMap);
        }
    }

    private static Recipe recipe(RecipeMap<SimpleRecipeBuilder> recipeMap, Item input, Item output) {
        // An explicit muffler stack avoids OrePrefix's material-registry bootstrap, which is outside this test's scope.
        return new Recipe(
                List.of(new GTRecipeItemInput(new ItemStack(input))),
                List.of(new ItemStack(output)), ChancedOutputList.empty(),
                List.of(), List.of(), ChancedOutputList.empty(),
                List.of(new ItemStack(Items.STICK)), 40, 30, false, false,
                new RecipePropertyStorageImpl(), recipeMap.getPrimaryRecipeCategory());
    }

    private static List<String> fingerprints(RecipeBindingResolver.RecipeMapSnapshot snapshot) {
        return snapshot.getRecipes().stream()
                .map(snapshot::normalize)
                .map(NormalizedRecipe::getRecipeFingerprint)
                .toList();
    }
}
