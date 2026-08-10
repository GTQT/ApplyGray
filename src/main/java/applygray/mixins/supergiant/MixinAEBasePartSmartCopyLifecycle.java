package applygray.mixins.supergiant;

import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderLinkable;
import applygray.mattermanipulator.integration.ae2.SmartCopyPatternProviderRegistry;

import ae2.parts.AEBasePart;
import ae2.parts.crafting.PatternProviderPart;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes server-side Smart Copy registrations when an AE2 part leaves its cable bus. */
@Mixin(value = AEBasePart.class, remap = false)
public abstract class MixinAEBasePartSmartCopyLifecycle {

    @Inject(method = "removeFromWorld", at = @At("TAIL"))
    private void applygray$removeSmartCopyRegistration(CallbackInfo ci) {
        if (!((Object) this instanceof PatternProviderPart provider) ||
                !(provider instanceof SmartCopyPatternProviderLinkable linked)) return;

        World world = provider.getLevel();
        if (world == null || world.isRemote) return;

        SmartCopyPatternProviderRegistry.sourceUnavailable(provider);
        SmartCopyPatternProviderRegistry.unregister(linked);
    }
}
