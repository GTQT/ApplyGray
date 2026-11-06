package applygray.mixins.supergiant;

import ae2.crafting.pattern.AEProcessingPattern;
import applygray.integration.ae2.encoding.ExtendedEncodingLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = AEProcessingPattern.class, remap = false)
public class MixinAEProcessingPattern {
    @ModifyArg(
        method = "<init>(Lae2/api/stacks/AEItemKey;)V",
        at = @At(
            value = "INVOKE",
            target = "Lae2/crafting/pattern/AEProcessingPattern;validateSparseStacks(Ljava/util/List;ILjava/lang/String;)V",
            ordinal = 1),
        index = 1,
        require = 1)
    private static int applygray$allowThirtyTwoOutputs(int maxOutputSlots) {
        return ExtendedEncodingLogic.PROCESSING_OUTPUT_SLOTS;
    }
}
