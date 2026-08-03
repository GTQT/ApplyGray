package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipeInputPreview;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.crafting.execution.CraftingCpuHelper;
import ae2.crafting.execution.InputTemplate;
import ae2.crafting.inv.ICraftingInventory;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoids scanning fuzzy inventory variants when an ApplyGray dynamic input has already been frozen to one key. */
@Mixin(value = CraftingCpuHelper.class, remap = false)
public abstract class MixinCraftingCpuHelperExactDynamicInput {

    @Inject(method = "getValidItemTemplates", at = @At("HEAD"), cancellable = true)
    private static void applygray$useExactDynamicInputTemplate(ICraftingInventory inventory,
                                                                IPatternDetails.IInput input, World level,
                                                                CallbackInfoReturnable<Iterable<InputTemplate>> cir) {
        Iterable<InputTemplate> exactTemplates = DynamicRecipeInputPreview.getExactTemplates(input);
        if (exactTemplates != null) {
            cir.setReturnValue(exactTemplates);
        }
    }

    /** Keeps the exact-key scope active for both AE2 simulation and modulation calls inside extractTemplates. */
    @Redirect(method = "extractTemplates", at = @At(value = "INVOKE",
            target = "Lae2/crafting/inv/ICraftingInventory;extract(Lae2/api/stacks/AEKey;JLae2/api/config/Actionable;)J"))
    private static long applygray$extractFrozenDynamicInputExactly(ICraftingInventory targetInventory, AEKey key,
                                                                     long amount, Actionable action,
                                                                     ICraftingInventory inventory,
                                                                     InputTemplate template, long requestedAmount) {
        if (!DynamicRecipeInputPreview.beginExactDynamicInputExtraction(template, key)) {
            return targetInventory.extract(key, amount, action);
        }
        long startedAtNanos = System.nanoTime();
        try {
            return targetInventory.extract(key, amount, action);
        } finally {
            DynamicRecipePatternRegistry.recordExactDynamicInputExtraction(System.nanoTime() - startedAtNanos);
            DynamicRecipeInputPreview.endExactDynamicInputExtraction();
        }
    }
}
