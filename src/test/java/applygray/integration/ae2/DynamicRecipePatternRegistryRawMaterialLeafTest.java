package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicRecipePatternRegistryRawMaterialLeafTest {

    @Test
    void treatsElementalGasesAsLeaves() {
        assertTrue(DynamicRecipePatternRegistry.isElementalGasLeaf(true, true, true));
        assertTrue(DynamicRecipePatternRegistry.isRawMaterialLeaf(false, false, true, false));
    }

    @Test
    void keepsCompoundFluidsExpandable() {
        assertFalse(DynamicRecipePatternRegistry.isElementalGasLeaf(true, false, true));
        assertFalse(DynamicRecipePatternRegistry.isRawMaterialLeaf(false, false, false, false));
    }

    @Test
    void treatsStandardWaterAsABasicFluidLeaf() {
        assertTrue(DynamicRecipePatternRegistry.isBasicFluidLeaf(true, "water"));
        assertTrue(DynamicRecipePatternRegistry.isRawMaterialLeaf(false, false, false, true));
    }

    @Test
    void keepsOtherCompoundFluidsExpandable() {
        assertFalse(DynamicRecipePatternRegistry.isBasicFluidLeaf(true, "distilled_water"));
        assertFalse(DynamicRecipePatternRegistry.isBasicFluidLeaf(false, "water"));
    }

    @Test
    void treatsBenzeneAsAnExplicitConfiguredFluidLeaf() {
        assertTrue(DynamicRecipePatternRegistry.isConfiguredFluidLeaf(true, "benzene"));
        assertFalse(DynamicRecipePatternRegistry.isConfiguredFluidLeaf(true, "nitrobenzene"));
        assertFalse(DynamicRecipePatternRegistry.isConfiguredFluidLeaf(false, "benzene"));
    }

    @Test
    void doesNotClassifyElementalItemsAsElementalFluids() {
        assertFalse(DynamicRecipePatternRegistry.isElementalGasLeaf(false, true, true));
    }

    @Test
    void keepsElementalLiquidAndMoltenFormsExpandable() {
        assertFalse(DynamicRecipePatternRegistry.isElementalGasLeaf(true, true, false));
    }

    @Test
    void preservesExistingOreAndElementalDustLeaves() {
        assertTrue(DynamicRecipePatternRegistry.isRawMaterialLeaf(true, false, false, false));
        assertTrue(DynamicRecipePatternRegistry.isRawMaterialLeaf(false, true, false, false));
    }
}
