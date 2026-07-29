package applygray.mixins.supergiant;

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

import java.util.ArrayList;
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

        int lastProcessIndex = Math.min(requestStack.size(), processStack.size()) - 1;
        if (DynamicRecipePatternRegistry.isOptimalRebuildCalculation()) {
            List<IPatternDetails> cyclePatterns = new ArrayList<>(lastProcessIndex - requestIndex + 1);
            for (int index = requestIndex; index <= lastProcessIndex; index++) {
                cyclePatterns.add(processStack.get(index).getDetails());
            }
            DynamicRecipePatternRegistry.invalidateRecursiveCycleForOptimalRebuild(requested, cyclePatterns);
            return;
        }

        // The last process is the edge that closes the recursion. Prefer leaving an ore-backed dust at that edge
        // as an external input so the restarted calculation can take its normal ore-processing route.
        for (int index = lastProcessIndex; index >= requestIndex; index--) {
            if (DynamicRecipePatternRegistry.rejectRecursiveCycleAtOreDust(requestStack.get(index),
                    processStack.get(index).getDetails()) > 0) {
                return;
            }
        }

        // The rest of the active segment can be ordinary intermediate processing. Only the first and closing
        // processes form the recursive edge, so retain every intervening dynamic pattern for later requests.
        int removedByOutput = DynamicRecipePatternRegistry.rejectRecursiveCycleAtOutput(requestStack.get(requestIndex),
                processStack.get(requestIndex).getDetails());
        if (lastProcessIndex != requestIndex) {
            removedByOutput += DynamicRecipePatternRegistry.rejectRecursiveCycleAtOutput(
                    requestStack.get(lastProcessIndex), processStack.get(lastProcessIndex).getDetails());
        }
        if (removedByOutput > 0) return;
    }
}
