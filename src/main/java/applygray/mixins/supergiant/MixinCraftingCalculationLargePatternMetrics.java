package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.networking.crafting.ICraftingPlan;
import ae2.crafting.CraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Brackets one AE2 calculation so large-pattern decisions can be logged once, after completion. */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class MixinCraftingCalculationLargePatternMetrics {

    @Inject(method = "run", at = @At("HEAD"))
    private void applygray$beginLargePatternCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        DynamicRecipePatternRegistry.beginLargePatternCalculation((CraftingCalculation) (Object) this);
    }

    @Inject(method = "run", at = @At("RETURN"))
    private void applygray$finishLargePatternCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        DynamicRecipePatternRegistry.finishLargePatternCalculation((CraftingCalculation) (Object) this);
    }
}
