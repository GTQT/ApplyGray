package applygray.mixins.ae2fc;

import gregtech.integration.ae2.GTCircuitHelper;

import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.helpers.patternmodifier.PatternModifierLogic;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps programmable circuits at amount one when a stored processing pattern is scaled.
 */
@Mixin(value = PatternModifierLogic.class, remap = false)
public abstract class MixinFluidPatternTerminalRecipeTransferHandler {

    @Inject(method = "modifyAmounts", at = @At("RETURN"), cancellable = true)
    private static void applygray$preserveCircuitAmount(ItemStack stack, World world, int factor, boolean divide,
                                                        CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = cir.getReturnValue();
        if (result.isEmpty()) {
            return;
        }
        if (!(PatternDetailsHelper.decodePattern(result, world) instanceof AEProcessingPattern pattern)) {
            return;
        }

        List<GenericStack> inputs = applygray$fixCircuitAmounts(pattern.getSparseInputs());
        List<GenericStack> outputs = applygray$fixCircuitAmounts(pattern.getSparseOutputs());
        if (inputs == pattern.getSparseInputs() && outputs == pattern.getSparseOutputs()) {
            return;
        }
        cir.setReturnValue(PatternDetailsHelper.encodeProcessingPattern(inputs, outputs));
    }

    private static List<GenericStack> applygray$fixCircuitAmounts(List<GenericStack> stacks) {
        List<GenericStack> result = null;
        for (int index = 0; index < stacks.size(); index++) {
            GenericStack stack = stacks.get(index);
            if (!applygray$isProgrammableCircuit(stack) || stack.amount() == 1) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(stacks);
            }
            result.set(index, new GenericStack(stack.what(), 1));
        }
        return result == null ? stacks : result;
    }

    private static boolean applygray$isProgrammableCircuit(GenericStack stack) {
        return stack != null && stack.what() instanceof AEItemKey itemKey &&
                GTCircuitHelper.isProgrammableCircuit(itemKey.toStack());
    }
}
