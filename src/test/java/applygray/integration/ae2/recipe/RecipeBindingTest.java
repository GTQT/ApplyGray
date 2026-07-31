package applygray.integration.ae2.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeBindingTest {

    @Test
    void currentBindingRoundTripsThroughNbt() {
        RecipeBinding binding = new RecipeBinding("assembler", RecipeBinding.FINGERPRINT_VERSION,
                "0123456789abcdef", "map-content", "item:target", RecipeBinding.NORMALIZATION_VERSION,
                "rules", "machine");

        RecipeBinding restored = RecipeBinding.readFromNBT(binding.writeToNBT());

        assertNotNull(restored);
        assertEquals(binding, restored);
    }

    @Test
    void legacyBindingPayloadIsDiscardedInsteadOfGuessed() {
        RecipeBinding binding = new RecipeBinding("assembler", RecipeBinding.FINGERPRINT_VERSION,
                "0123456789abcdef", "map-content", "item:target", RecipeBinding.NORMALIZATION_VERSION,
                "rules", "machine");
        var legacy = binding.writeToNBT();
        legacy.setInteger("Version", 1);

        assertNull(RecipeBinding.readFromNBT(legacy));
    }
}
