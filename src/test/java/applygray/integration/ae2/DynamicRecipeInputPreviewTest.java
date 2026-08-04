package applygray.integration.ae2;

import ae2.api.crafting.IPatternDetails;
import ae2.api.config.FuzzyMode;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.execution.InputTemplate;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamicRecipeInputPreviewTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void returnsOneDirectTemplateForFrozenDynamicInput() {
        AEItemKey input = key(Items.IRON_INGOT);
        DynamicRecipePatternDetails detail = detail(List.of(new GenericStack(input, 3)),
                List.of(List.of(new GenericStack(input, 3))));

        IPatternDetails.IInput patternInput = detail.getInputs()[0];
        Iterable<InputTemplate> templates = DynamicRecipeInputPreview.getExactTemplates(patternInput);

        assertNotNull(templates);
        InputTemplate template = templates.iterator().next();
        assertEquals(input, template.key());
        assertEquals(1, template.amount());
    }

    @Test
    void retainsAesFuzzyLookupForDynamicAlternatives() {
        AEItemKey iron = key(Items.IRON_INGOT);
        AEItemKey gold = key(Items.GOLD_INGOT);
        DynamicRecipePatternDetails detail = detail(List.of(new GenericStack(iron, 1)),
                List.of(List.of(new GenericStack(iron, 1), new GenericStack(gold, 1))));

        assertNull(DynamicRecipeInputPreview.getExactTemplates(detail.getInputs()[0]));
    }

    @Test
    void zeroValuedExactEntryKeepsAesFuzzyCacheInitialized() {
        AEItemKey input = key(Items.IRON_INGOT);
        KeyCounter cache = new KeyCounter();

        cache.add(input, 0);

        assertEquals(0, cache.get(input));
        assertFalse(cache.findFuzzy(input, FuzzyMode.IGNORE_ALL).isEmpty());
    }

    @Test
    void returnsDirectTemplateForRegisteredProcessingPatternInput() {
        AEItemKey input = key(Items.IRON_INGOT);
        IPatternDetails.IInput patternInput = singleOptionInput(input);

        ExactPatternInputRegistry.registerInput(patternInput);
        try {
            Iterable<InputTemplate> templates = DynamicRecipeInputPreview.getExactTemplates(patternInput);
            assertNotNull(templates);
            InputTemplate template = templates.iterator().next();
            assertEquals(input, template.key());
            assertEquals(1, template.amount());
        } finally {
            ExactPatternInputRegistry.clear();
        }
    }

    @Test
    void retainsFuzzyLookupForUnregisteredProcessingPatternInput() {
        IPatternDetails.IInput patternInput = singleOptionInput(key(Items.IRON_INGOT));

        assertNull(DynamicRecipeInputPreview.getExactTemplates(patternInput));
    }

    @Test
    void nonProcessingPatternsAreNeverRegistered() {
        AEItemKey iron = key(Items.IRON_INGOT);
        DynamicRecipePatternDetails detail = detail(List.of(new GenericStack(iron, 3)),
                List.of(List.of(new GenericStack(iron, 3))));

        ExactPatternInputRegistry.registerPattern(detail);

        assertFalse(ExactPatternInputRegistry.isExact(detail.getInputs()[0]));
    }

    private static IPatternDetails.IInput singleOptionInput(AEItemKey input) {
        return new IPatternDetails.IInput() {
            private final GenericStack[] options = {new GenericStack(input, 1)};

            @Override
            public GenericStack[] possibleInputs() {
                return options.clone();
            }

            @Override
            public long getMultiplier() {
                return 1;
            }

            @Override
            public boolean isValid(ae2.api.stacks.AEKey candidate, World level) {
                return input.equals(candidate);
            }

            @Override
            public ae2.api.stacks.AEKey getRemainingKey(ae2.api.stacks.AEKey template) {
                return null;
            }
        };
    }

    private static DynamicRecipePatternDetails detail(List<GenericStack> inputs,
                                                       List<List<GenericStack>> alternatives) {
        AEItemKey output = key(Items.DIAMOND);
        return new DynamicRecipePatternDetails("test-recipe", "assembler", inputs, alternatives,
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
