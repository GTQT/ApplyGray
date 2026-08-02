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
