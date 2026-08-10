package applygray.mixins.supergiant;

import java.util.Collections;
import java.util.List;

import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderLinkable;
import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IManagedGridNode;
import ae2.helpers.patternprovider.PatternProviderLogic;
import ae2.helpers.patternprovider.PatternProviderLogicHost;
import ae2.helpers.patternprovider.PseudoPatternDetails;
import ae2.parts.crafting.PatternProviderPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes a linked Pattern Provider advertise source patterns while keeping target-side output behavior. */
@Mixin(value = PatternProviderLogic.class, remap = false)
public abstract class MixinPatternProviderLogicSmartCopy {

    @Shadow @Final private PatternProviderLogicHost host;
    @Shadow @Final private IManagedGridNode mainNode;

    @Inject(method = "getAvailablePatterns", at = @At("HEAD"), cancellable = true)
    private void applygray$getSmartCopyPatterns(CallbackInfoReturnable<List<IPatternDetails>> cir) {
        SmartCopyPatternProviderLinkable linked = applygray$linkableHost();
        if (linked == null || linked.applygray$getSmartCopyLink().isEmpty()) return;

        if (!mainNode.isActive()) {
            cir.setReturnValue(Collections.emptyList());
            return;
        }
        PatternProviderLogic source = linked.applygray$getSmartCopySourceLogic();
        cir.setReturnValue(source == null ? Collections.emptyList() : source.getAvailablePatterns());
    }

    @Redirect(method = "pushPattern", at = @At(value = "INVOKE",
            target = "Ljava/util/List;contains(Ljava/lang/Object;)Z"))
    private boolean applygray$acceptLinkedSourcePattern(List<?> localPatterns, Object pattern) {
        return localPatterns.contains(pattern) || applygray$containsLinkedSourcePattern(pattern);
    }

    @Redirect(method = "canMergePatternPushBasic", at = @At(value = "INVOKE",
            target = "Ljava/util/List;contains(Ljava/lang/Object;)Z"))
    private boolean applygray$acceptLinkedSourceMergePattern(List<?> localPatterns, Object pattern) {
        return localPatterns.contains(pattern) || applygray$containsLinkedSourcePattern(pattern);
    }

    @Inject(method = "updatePatterns", at = @At("RETURN"))
    private void applygray$notifySmartCopyTargets(CallbackInfo ci) {
        if (host instanceof PatternProviderPart source &&
                (!(source instanceof SmartCopyPatternProviderLinkable linked) ||
                        linked.applygray$getSmartCopyLink().isEmpty())) {
            SmartCopyPatternProviderRegistry.sourcePatternsChanged(source);
        }
    }

    private boolean applygray$containsLinkedSourcePattern(Object pattern) {
        if (!(pattern instanceof IPatternDetails details)) return false;
        SmartCopyPatternProviderLinkable linked = applygray$linkableHost();
        if (linked == null || linked.applygray$getSmartCopyLink().isEmpty()) return false;

        PatternProviderLogic source = linked.applygray$getSmartCopySourceLogic();
        if (source == null) return false;
        for (IPatternDetails sourcePattern : source.getAvailablePatterns()) {
            if (PseudoPatternDetails.unwrap(sourcePattern).equals(details)) return true;
        }
        return false;
    }

    private SmartCopyPatternProviderLinkable applygray$linkableHost() {
        return host instanceof SmartCopyPatternProviderLinkable linked ? linked : null;
    }
}
