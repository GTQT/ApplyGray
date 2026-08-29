package applygray.mattermanipulator.uplink;

final class UplinkTransferCosts {

    static final long PLASMA_EU_PER_TRANSFER_UNIT = 131_072L;
    static final long FLUID_UNITS_PER_BUCKET = 1_000L;

    private UplinkTransferCosts() {}

    static long fluidPlasmaCost(long amount) {
        if (amount <= 0L) return 0L;
        long buckets = amount / FLUID_UNITS_PER_BUCKET + (amount % FLUID_UNITS_PER_BUCKET == 0L ? 0L : 1L);
        if (buckets > Long.MAX_VALUE / PLASMA_EU_PER_TRANSFER_UNIT) return Long.MAX_VALUE;
        return buckets * PLASMA_EU_PER_TRANSFER_UNIT;
    }

    static long plasmaEnergyPerFluidUnit(long recipeEUt, int duration, int inputAmount) {
        if (recipeEUt <= 0L || duration <= 0 || inputAmount <= 0) return 0L;
        long totalEnergy = recipeEUt > Long.MAX_VALUE / duration ? Long.MAX_VALUE : recipeEUt * duration;
        return Math.max(1L, totalEnergy / inputAmount);
    }
}
