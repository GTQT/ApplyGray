package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEKey;
import ae2.crafting.CraftingCalculation;
import ae2.crafting.CraftingTreeProcess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes lazy RecipeMap details selected by a recursion that cannot produce its requested key positively.
 */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class MixinCraftingCalculationLazyRecipeMap {

    @Shadow @Final
    private List<AEKey> requestStack;

    @Shadow @Final
    private List<CraftingTreeProcess> processStack;

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

        // The last process is the edge that closes the recursion. Prefer leaving an ore-backed dust at that edge
        // as an external input so the restarted calculation can take its normal ore-processing route.
        int lastProcessIndex = Math.min(requestStack.size(), processStack.size()) - 1;
        for (int index = lastProcessIndex; index >= requestIndex; index--) {
            if (DynamicRecipePatternRegistry.rejectRecursiveCycleAtOreDust(requestStack.get(index),
                    processStack.get(index).getDetails()) > 0) {
                return;
            }
        }

        int removedByOutput = 0;
        for (int index = requestIndex; index <= lastProcessIndex; index++) {
            removedByOutput += DynamicRecipePatternRegistry.rejectRecursiveCycleAtOutput(requestStack.get(index),
                    processStack.get(index).getDetails());
        }
        if (removedByOutput > 0) return;

        List<IPatternDetails> cyclePatterns = new ArrayList<>(processStack.size() - requestIndex);
        for (int index = requestIndex; index < processStack.size(); index++) {
            cyclePatterns.add(processStack.get(index).getDetails());
        }
        DynamicRecipePatternRegistry.rejectRecursiveCycle(requested, cyclePatterns);
    }
}
