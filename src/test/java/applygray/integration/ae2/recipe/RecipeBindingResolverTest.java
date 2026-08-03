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
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.ingredients.GTRecipeItemInput;
import gregtech.api.recipes.ingredients.GTRecipeOreInput;
import gregtech.api.recipes.machines.RecipeMapFurnace;
import gregtech.api.recipes.properties.RecipePropertyStorageImpl;

import ae2.api.stacks.AEItemKey;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @AfterEach
    void clearRuntimeFurnaceFallbacks() {
        RecipeBindingResolver.invalidateRuntimeFurnaceFallbacks();
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
        RecipeBinding oldAlgorithmBinding = new RecipeBinding(recipeMap.getUnlocalizedName(),
                RecipeBinding.FINGERPRINT_VERSION - 1,
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

    @Test
    void contentBindingSurvivesAnUnrelatedRecipeMapInsertion() {
        RecipeMap<SimpleRecipeBuilder> recipeMap = new RecipeMapBuilder<>(
                "applygray_binding_content_" + System.nanoTime(), new SimpleRecipeBuilder())
                .itemInputs(1)
                .itemOutputs(1)
                .build();
        Recipe targetRecipe = recipe(recipeMap, Items.IRON_INGOT, Items.GOLD_INGOT);
        Recipe insertedRecipe = recipe(recipeMap, Items.DIAMOND, Items.EMERALD);
        RecipeBindingResolver.RecipeMapSnapshot initial =
                RecipeBindingResolver.RecipeMapSnapshot.create(recipeMap, List.of(targetRecipe));
        NormalizedRecipe normalized = initial.normalize(targetRecipe);
        RecipeBinding binding = normalized.createBinding(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)), "rules", "machine");
        RecipeBindingResolver.RecipeMapSnapshot expanded =
                RecipeBindingResolver.RecipeMapSnapshot.create(recipeMap, List.of(insertedRecipe, targetRecipe));

        RecipeBindingResolver.register(recipeMap, expanded);
        try {
            assertTrue(RecipeBindingResolver.resolve(binding, recipeMap).isResolved());
        } finally {
            RecipeBindingResolver.invalidate(recipeMap);
        }
    }

    @Test
    void fingerprintIncludesNonConsumableOreDictionaryInputSemantics() {
        RecipeMap<SimpleRecipeBuilder> recipeMap = new RecipeMapBuilder<>(
                "applygray_binding_ore_identity_" + System.nanoTime(), new SimpleRecipeBuilder())
                .itemInputs(1)
                .itemOutputs(1)
                .build();
        String oreName = "applygray_test_lens_" + System.nanoTime();
        GTRecipeInput consumableInput = new GTRecipeOreInput(oreName);
        GTRecipeInput nonConsumableInput = new GTRecipeOreInput(oreName).setNonConsumable();
        assertEquals(oreName, GTRecipeInput.writePersistentIdentityToNBT(consumableInput).getString("oreName"));
        assertFalse(GTRecipeInput.writePersistentIdentityToNBT(consumableInput).hasKey("ore"));
        Recipe consumable = recipe(recipeMap, consumableInput, Items.GOLD_INGOT);
        Recipe nonConsumable = recipe(recipeMap, nonConsumableInput, Items.GOLD_INGOT);

        assertNotEquals(RecipeFingerprint.contentFingerprint(recipeMap.getUnlocalizedName(), consumable),
                RecipeFingerprint.contentFingerprint(recipeMap.getUnlocalizedName(), nonConsumable));
    }

    @Test
    void restoresOnlySavedFurnaceFallbackBeforeAnExplicitFullCapture() {
        ItemStack input = new ItemStack(Blocks.SAND);
        TestFurnaceRecipeMap furnaceMap = new TestFurnaceRecipeMap(
                "applygray_binding_furnace_" + System.nanoTime(), input, new ItemStack(Blocks.GLASS));
        Recipe fallback = furnaceMap.getFallback();

        AEItemKey target = AEItemKey.of(fallback.getOutputs().get(0));
        assertTrue(target != null);
        if (target == null) return;

        RecipeBinding binding = new RecipeBinding(furnaceMap.getUnlocalizedName(),
                RecipeBinding.FINGERPRINT_VERSION,
                RecipeFingerprint.contentFingerprint(furnaceMap.getUnlocalizedName(), fallback), "test",
                RecipeFingerprint.describeKey(target), RecipeBinding.NORMALIZATION_VERSION, "rules", "machine");
        assertFalse(RecipeBindingResolver.resolve(binding, furnaceMap).isResolved());

        int recovered = RecipeBindingResolver.recoverPersistedFurnaceFallbacks(
                new RecipeMap<?>[]{furnaceMap},
                List.of(new RecipeBindingResolver.PersistedFurnaceFallback(binding, input)));

        assertEquals(1, recovered);
        assertTrue(RecipeBindingResolver.resolve(binding, furnaceMap).isResolved());
        assertEquals(1, furnaceMap.getLookupCount());
        assertTrue(RecipeBindingResolver.captureMainThreadRuntimeRecipes(new RecipeMap<?>[]{furnaceMap})
                .contains(furnaceMap));
        assertTrue(furnaceMap.getLookupCount() > 1);
    }

    private static Recipe recipe(RecipeMap<SimpleRecipeBuilder> recipeMap, Item input, Item output) {
        return recipe(recipeMap, new GTRecipeItemInput(new ItemStack(input)), output);
    }

    private static Recipe recipe(RecipeMap<SimpleRecipeBuilder> recipeMap, GTRecipeInput input, Item output) {
        return recipe(recipeMap, input, new ItemStack(output));
    }

    private static Recipe recipe(RecipeMap<SimpleRecipeBuilder> recipeMap, GTRecipeInput input, ItemStack output) {
        // An explicit muffler stack avoids OrePrefix's material-registry bootstrap, which is outside this test's scope.
        return new Recipe(
                List.of(input),
                List.of(output), ChancedOutputList.empty(),
                List.of(), List.of(), ChancedOutputList.empty(),
                List.of(new ItemStack(Items.STICK)), 40, 30, false, false,
                new RecipePropertyStorageImpl(), recipeMap.getPrimaryRecipeCategory());
    }

    private static final class TestFurnaceRecipeMap extends RecipeMapFurnace {

        private final ItemStack fallbackInput;
        private final Recipe fallback;
        private int lookupCount;

        private TestFurnaceRecipeMap(String name, ItemStack input, ItemStack output) {
            super(name, new SimpleRecipeBuilder(), recipeMap -> null);
            fallbackInput = input.copy();
            fallbackInput.setCount(1);
            fallback = recipe(this, new GTRecipeItemInput(fallbackInput), output);
        }

        @Override
        public Recipe findRecipe(long voltage, List<ItemStack> inputs, List<FluidStack> fluidInputs,
                                 boolean exactVoltage) {
            lookupCount++;
            if (inputs == null) return null;
            for (ItemStack input : inputs) {
                if (ItemStack.areItemsEqual(fallbackInput, input) &&
                        ItemStack.areItemStackTagsEqual(fallbackInput, input)) {
                    return fallback;
                }
            }
            return null;
        }

        private Recipe getFallback() {
            return fallback;
        }

        private int getLookupCount() {
            return lookupCount;
        }
    }

    private static List<String> fingerprints(RecipeBindingResolver.RecipeMapSnapshot snapshot) {
        return snapshot.getRecipes().stream()
                .map(snapshot::normalize)
                .map(NormalizedRecipe::getRecipeFingerprint)
                .toList();
    }
}
