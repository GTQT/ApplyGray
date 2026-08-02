package applygray.integration.ae2;

import applygray.integration.ae2.recipe.NonConsumableTokenLayout;
import applygray.integration.ae2.recipe.RecipeBinding;
import applygray.integration.ae2.rules.CyclePolicy;
import applygray.integration.ae2.rules.PlanningMode;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DynamicRecipePatternDetailsLargePatternTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void scalesConsumablesAndOutputButKeepsNonConsumableTokensAtOne() {
        AEItemKey input = key(Items.IRON_INGOT);
        AEItemKey token = key(Items.STICK);
        AEItemKey output = key(Items.GOLD_INGOT);
        DynamicRecipePatternDetails ordinary = new DynamicRecipePatternDetails("test-recipe", "assembler",
                List.of(new GenericStack(input, 2), new GenericStack(token, 1)),
                List.of(List.of(new GenericStack(input, 2)), List.of(new GenericStack(token, 1))),
                List.of(new GenericStack(output, 1)), -1, 2, 1,
                DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL, 0,
                CyclePolicy.BREAK_AT_EXTERNAL_SEED, 0, 1, PlanningMode.STOCK_FIRST, "",
                List.of(), binding(), new NonConsumableTokenLayout(List.of(
                        new NonConsumableTokenLayout.Slot(1, List.of(new ItemStack(Items.STICK))))), List.of());

        DynamicRecipePatternDetails large = ordinary.createLargePattern(500);

        assertNotNull(large);
        assertEquals(500, large.getRecipeRunsPerPattern());
        IPatternDetails.IInput[] inputs = large.getInputs();
        assertEquals(1_000, inputs[0].getMultiplier());
        assertEquals(1, inputs[1].getMultiplier());
        assertEquals(500, large.getOutputs().getFirst().amount());
        assertEquals(500, large.getNetOutputAmount(output));
    }

    private static AEItemKey key(net.minecraft.item.Item item) {
        AEItemKey key = AEItemKey.of(new ItemStack(item));
        if (key == null) throw new AssertionError("Could not create item key");
        return key;
    }

    private static RecipeBinding binding() {
        return new RecipeBinding("assembler", RecipeBinding.FINGERPRINT_VERSION, "fingerprint", "map-content",
                "item:gold_ingot", RecipeBinding.NORMALIZATION_VERSION, "rules", "machine");
    }
}
