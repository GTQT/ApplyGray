package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCacheRebuildGateTest {

    @Test
    void coalescesRepeatedProviderChangesUntilTheCacheIsObserved() {
        DynamicRecipePatternRegistry.ProviderCacheRebuildGate gate =
                new DynamicRecipePatternRegistry.ProviderCacheRebuildGate();

        assertTrue(gate.invalidate());
        assertFalse(gate.invalidate());
        assertTrue(gate.isPending());

        assertTrue(gate.beginRebuild());
        assertFalse(gate.isPending());
        assertFalse(gate.beginRebuild());

        assertTrue(gate.invalidate());
    }
}
