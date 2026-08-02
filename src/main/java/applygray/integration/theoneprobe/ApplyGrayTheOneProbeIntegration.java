package applygray.integration.theoneprobe;

import mcjty.theoneprobe.api.ITheOneProbe;

import java.util.function.Function;

/** TOP's IMC entry point. */
@SuppressWarnings("unused")
public final class ApplyGrayTheOneProbeIntegration implements Function<ITheOneProbe, Void> {

    @Override
    public Void apply(ITheOneProbe oneProbe) {
        oneProbe.registerProvider(new RecipeMapPatternProviderInfoProvider());
        return null;
    }
}
