package applygray.integration.ae2.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MachineCapabilityProfileTemperatureTest {

    @Test
    void godforgeModuleHeatSuppliesBlastRecipeTemperature() {
        assertEquals(12_601, MachineCapabilityProfile.selectRecipeTemperature(0, 0, 12_601));
    }

    @Test
    void dedicatedHeatingCoilRemainsThePreferredTemperatureSource() {
        assertEquals(1_800, MachineCapabilityProfile.selectRecipeTemperature(1_800, 12_601, 16_000));
    }

    @Test
    void genericHeatMachineIsUsedWhenNoDedicatedSourceIsPresent() {
        assertEquals(2_400, MachineCapabilityProfile.selectRecipeTemperature(0, 2_400, 0));
        assertEquals(0, MachineCapabilityProfile.selectRecipeTemperature(0, 0, 0));
    }
}
