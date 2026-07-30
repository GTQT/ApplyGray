package applygray.mixins.supergiant;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternDetails;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.stacks.AEKey;
import ae2.crafting.CraftingCalculation;
import ae2.crafting.CraftingTreeProcess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Removes lazy RecipeMap details selected by a recursion that cannot produce its requested key positively. */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class MixinCraftingCalculationLazyRecipeMap {

    @Shadow @Final
    private List<AEKey> requestStack;

    @Shadow @Final
    private List<CraftingTreeProcess> processStack;

    @Inject(method = "run", at = @At("HEAD"))
    private void applygray$enterLazyRecipeMapCalculation(CallbackInfoReturnable<ICraftingPlan> cir) {
        DynamicRecipePatternRegistry.enterCraftingCalculation((CraftingCalculation) (Object) this);
    }

    @Inject(method = "finish", at = @At("HEAD"))
    private void applygray$leaveLazyRecipeMapCalculation(CallbackInfo ci) {
        DynamicRecipePatternRegistry.leaveCraftingCalculation((CraftingCalculation) (Object) this);
    }

    @Inject(method = "cycleHasNetOutput", at = @At("RETURN"))
    private void applygray$discardNonProductiveLazyRecipeMapCycle(AEKey requested,
                                                                  CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;

        int requestIndex = -1;
        for (int index = requestStack.size() - 1; index >= 0; index--) {
            if (requestStack.get(index).equals(requested)) {
                requestIndex = index;
                break;
            }
        }
        if (requestIndex < 0 || requestIndex >= processStack.size()) return;

        // The repeated request belongs to its opening process. The closing process merely consumes the repeated
        // key while producing another key, so rejecting it would discard a valid route for that other output.
        IPatternDetails detail = processStack.get(requestIndex).getDetails();
        DynamicRecipePatternDetails dynamic = DynamicRecipePatternRegistry.getDynamicPattern(detail);
        if (dynamic == null || !dynamic.netProduces(requested)) return;

        int removed = DynamicRecipePatternRegistry.isOptimalRebuildCalculation() ?
                DynamicRecipePatternRegistry.invalidateRecursiveCycleForOptimalRebuild(requested, List.of(detail)) :
                DynamicRecipePatternRegistry.rejectRecursiveCycleAtOutput(requested, detail);
        if (removed > 0) {
            ApplyGrayMod.LOGGER.debug("Rejected recursive RecipeMap opening output {} at request stack index {}: {} ({})",
                    requested, requestIndex, dynamic.getRecipeKey(), dynamic.getRecipeMapName());
        }
    }
}
