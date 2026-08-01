package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.me.service.CraftingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Keeps the Provider identity for generated RecipeMap patterns.
 *
 * <p>Pattern discovery itself is entirely AE2-native: the provider publishes persisted details through
 * {@link ICraftingProvider#getAvailablePatterns()} and requests an ordinary AE2 provider-cache refresh after
 * generation. This mixin must not generate, rank, or retry routes during an AE2 crafting calculation.</p>
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class MixinCraftingServiceRecipeMapPatternProvider {

    @Inject(method = "getProviders", at = @At("RETURN"), cancellable = true)
    private void applygray$getGeneratedRecipeMapPatternProvider(IPatternDetails details,
                                                                CallbackInfoReturnable<Iterable<ICraftingProvider>> cir) {
        ICraftingProvider provider = DynamicRecipePatternRegistry.getProvider(details);
        if (provider != null) {
            cir.setReturnValue(List.of(provider));
        }
    }
}
