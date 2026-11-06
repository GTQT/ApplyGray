package applygray.mixins.supergiant;

import ae2.api.crafting.IPatternDetails;
import ae2.helpers.patternprovider.PseudoPatternDetails;
import applygray.integration.ae2.circuit.GregTechCircuitPatternDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PseudoPatternDetails.class, remap = false)
public class MixinPseudoPatternDetails {

    @Inject(
        method = "isPseudo",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void applygray$isFilteredPseudo(
        IPatternDetails details,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (GregTechCircuitPatternDetails.isFiltered(details)) {
            cir.setReturnValue(PseudoPatternDetails.isPseudo(GregTechCircuitPatternDetails.unwrap(details)));
        }
    }

    @Inject(
        method = "unwrap",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void applygray$unwrapFilteredPseudo(
        IPatternDetails details,
        CallbackInfoReturnable<IPatternDetails> cir
    ) {
        if (GregTechCircuitPatternDetails.isFiltered(details)) {
            cir.setReturnValue(GregTechCircuitPatternDetails.unwrap(PseudoPatternDetails.unwrap(
                GregTechCircuitPatternDetails.unwrap(details))));
        }
    }
}
