package applygray.mixins.ae2fc;

import gregtech.integration.ae2.GTCircuitHelper;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.parts.encoding.ProcessingPatternAmountHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Preserves programmable circuits while Supergiant adjusts processing-pattern amounts.
 */
@Mixin(value = ProcessingPatternAmountHelper.class, remap = false)
public abstract class MixinUtil {

    @Unique
    private static final ThreadLocal<Boolean> applygray$filteringCircuitChecks = ThreadLocal.withInitial(() -> false);

    @Inject(method = "canApply", at = @At("HEAD"), cancellable = true)
    private static void applygray$skipCircuitDivisibility(List<GenericStack> stacks,
                                                          ProcessingPatternAmountHelper.Operation operation,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (applygray$filteringCircuitChecks.get() || !applygray$containsProgrammableCircuit(stacks)) {
            return;
        }

        List<GenericStack> consumedStacks = new ArrayList<>(stacks.size());
        for (GenericStack stack : stacks) {
            if (!applygray$isProgrammableCircuit(stack)) {
                consumedStacks.add(stack);
            }
        }

        applygray$filteringCircuitChecks.set(true);
        try {
            cir.setReturnValue(ProcessingPatternAmountHelper.canApply(consumedStacks, operation));
        } finally {
            applygray$filteringCircuitChecks.remove();
        }
    }

    @Inject(method = "apply", at = @At("RETURN"), cancellable = true)
    private static void applygray$preserveCircuitAmount(GenericStack stack,
                                                        ProcessingPatternAmountHelper.Operation operation,
                                                        CallbackInfoReturnable<GenericStack> cir) {
        GenericStack result = cir.getReturnValue();
        if (applygray$isProgrammableCircuit(result)) {
            cir.setReturnValue(new GenericStack(result.what(), 1));
        }
    }

    @Unique
    private static boolean applygray$containsProgrammableCircuit(List<GenericStack> stacks) {
        for (GenericStack stack : stacks) {
            if (applygray$isProgrammableCircuit(stack)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean applygray$isProgrammableCircuit(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey itemKey)) {
            return false;
        }
        return GTCircuitHelper.isProgrammableCircuit(itemKey.toStack());
    }
}
