package applygray.integration.theoneprobe;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLInterModComms;

/** Registers the optional TOP module without loading TOP classes when the mod is absent. */
public final class TheOneProbeIntegration {

    private static final String TOP_MOD_ID = "theoneprobe";
    private static final String MODULE_CLASS = "applygray.integration.theoneprobe.ApplyGrayTheOneProbeIntegration";

    private TheOneProbeIntegration() {}

    public static void enqueueIMC() {
        if (Loader.isModLoaded(TOP_MOD_ID)) {
            FMLInterModComms.sendFunctionMessage(TOP_MOD_ID, "getTheOneProbe", MODULE_CLASS);
        }
    }
}
