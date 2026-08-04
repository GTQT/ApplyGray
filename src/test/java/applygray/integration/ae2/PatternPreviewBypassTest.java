package applygray.integration.ae2;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternPreviewBypassTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void oneRunDynamicPatternBypassesAndMultiRunDoesNot() {
        DynamicRecipePatternDetails detail = detail();

        assertTrue(DynamicRecipePatternRegistry.canBypassPatternMaximumCraftablePreview(detail, 1, null));
        assertFalse(DynamicRecipePatternRegistry.canBypassPatternMaximumCraftablePreview(detail, 2, null));
    }

    @Test
    void ordinaryAtomicSafeAcceptsOnlyEmptyOrFullyDynamicInputs() {
        DynamicRecipePatternDetails dynamic = detail();

        assertTrue(DynamicRecipePatternRegistry.isOrdinaryPatternAtomicSafe(List.of()));
        assertTrue(DynamicRecipePatternRegistry.isOrdinaryPatternAtomicSafe(List.of(List.of())));
        assertTrue(DynamicRecipePatternRegistry.isOrdinaryPatternAtomicSafe(List.of(List.of(dynamic), List.of())));
        assertFalse(DynamicRecipePatternRegistry.isOrdinaryPatternAtomicSafe(
                List.of(List.of(dynamic), List.of(nonDynamicPattern()))));
        assertFalse(DynamicRecipePatternRegistry.isOrdinaryPatternAtomicSafe(null));
        assertFalse(DynamicRecipePatternRegistry.isOrdinaryPatternAtomicSafe(
                java.util.Collections.singletonList(null)));
    }

    @Test
    void nonProcessingPatternDetailsAreNeverRegisteredAsPatterns() {
        DynamicRecipePatternDetails detail = detail();

        ExactPatternInputRegistry.registerPattern(detail);

        assertFalse(ExactPatternInputRegistry.isRegisteredPattern(detail));
    }

    private static IPatternDetails nonDynamicPattern() {
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return null;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of();
            }
        };
    }

    private static DynamicRecipePatternDetails detail() {
        AEItemKey input = key(Items.IRON_INGOT);
        AEItemKey output = key(Items.DIAMOND);
        return new DynamicRecipePatternDetails("test-recipe", "assembler",
                List.of(new GenericStack(input, 1)), List.of(List.of(new GenericStack(input, 1))),
                List.of(new GenericStack(output, 1)), -1, 1, 1,
                DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL, 0,
                applygray.integration.ae2.rules.CyclePolicy.BREAK_AT_EXTERNAL_SEED, 0, 1,
                applygray.integration.ae2.rules.PlanningMode.STOCK_FIRST, "", List.of(),
                new applygray.integration.ae2.recipe.RecipeBinding("assembler",
                        applygray.integration.ae2.recipe.RecipeBinding.FINGERPRINT_VERSION,
                        "fingerprint", "map-content", "item:diamond",
                        applygray.integration.ae2.recipe.RecipeBinding.NORMALIZATION_VERSION, "rules", "machine"),
                applygray.integration.ae2.recipe.NonConsumableTokenLayout.EMPTY, List.of());
    }

    private static AEItemKey key(net.minecraft.item.Item item) {
        AEItemKey key = AEItemKey.of(new ItemStack(item));
        if (key == null) throw new AssertionError("Could not create item key");
        return key;
    }
}
