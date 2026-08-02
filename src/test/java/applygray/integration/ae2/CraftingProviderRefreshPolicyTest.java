package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingProviderRefreshPolicyTest {

    @Test
    void refreshesOnDeactivationAndOnlyOptedInActivation() {
        assertFalse(CraftingProviderRefreshPolicy.needsNativeRefresh(true, false));
        assertTrue(CraftingProviderRefreshPolicy.needsNativeRefresh(false, false));
        assertTrue(CraftingProviderRefreshPolicy.needsNativeRefresh(true, true));
    }
}
