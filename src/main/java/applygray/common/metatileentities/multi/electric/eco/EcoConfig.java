package applygray.common.metatileentities.multi.electric.eco;

import applygray.api.ApplyGrayAPI;
import net.minecraftforge.common.config.Config;

@Config(modid = ApplyGrayAPI.MODID, name = "applygray-eco")
public final class EcoConfig {
    @Config.RangeInt(min = 1, max = 1048576)
    public static int[] assemblerBatches = EcoTiers.DEFAULT_BATCHES.clone();
    @Config.RangeInt(min = 1, max = 1200)
    public static int assemblerCycleTicks = 20;
    @Config.RangeInt(min = 1, max = 1000000)
    public static int aePerCraft = 100;
    /** Bound actual recipe evaluations for recipes with changing NBT or remaining items. */
    @Config.RangeInt(min = 1, max = 8192)
    public static int evaluationsPerTick = 256;

    private EcoConfig() {}

    public static int batch(int tier) {
        int i = tier - 1;
        return assemblerBatches.length == EcoTiers.COUNT && assemblerBatches[i] > 0
                ? assemblerBatches[i] : EcoTiers.DEFAULT_BATCHES[i];
    }
}
