package applygray.mattermanipulator.uplink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UplinkTransferCostsTest {

    @Test
    void fluidPlasmaCostRoundsUpToWholeBuckets() {
        long costPerBucket = UplinkTransferCosts.PLASMA_EU_PER_TRANSFER_UNIT;
        long bucket = UplinkTransferCosts.FLUID_UNITS_PER_BUCKET;

        assertEquals(0L, UplinkTransferCosts.fluidPlasmaCost(0L));
        assertEquals(costPerBucket, UplinkTransferCosts.fluidPlasmaCost(1L));
        assertEquals(costPerBucket, UplinkTransferCosts.fluidPlasmaCost(bucket));
        assertEquals(costPerBucket * 2L, UplinkTransferCosts.fluidPlasmaCost(bucket + 1L));
    }

    @Test
    void positiveGeneratorRecipeProducesPlasmaEnergy() {
        assertEquals(81_920L, UplinkTransferCosts.plasmaEnergyPerFluidUnit(2_048L, 40, 1));
        assertEquals(0L, UplinkTransferCosts.plasmaEnergyPerFluidUnit(-2_048L, 40, 1));
    }
}
