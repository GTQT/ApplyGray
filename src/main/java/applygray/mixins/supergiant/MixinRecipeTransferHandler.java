package applygray.mixins.supergiant;

import gregtech.integration.ae2.GTCircuitHelper;

import net.minecraft.entity.player.EntityPlayer;

import ae2.container.me.items.ContainerPatternEncodingTerm;
import ae2.integration.modules.hei.PatternEncodingRecipeTransferHandler;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Starts the GT programmable-circuit transfer session for Supergiant's HEI handler. */
@Mixin(value = PatternEncodingRecipeTransferHandler.class, remap = false)
public abstract class MixinRecipeTransferHandler {

    @Inject(method = "transferRecipe", at = @At("HEAD"), remap = false)
    private void applygray$beginProgrammableCircuitTransfer(ContainerPatternEncodingTerm container,
                                                            IRecipeLayout recipeLayout, EntityPlayer player,
                                                            boolean maxTransfer, boolean doTransfer,
                                                            CallbackInfoReturnable<IRecipeTransferError> cir) {
        GTCircuitHelper.beginPatternTransfer(player, doTransfer);
    }

    @Inject(method = "transferRecipe", at = @At("RETURN"), remap = false)
    private void applygray$endProgrammableCircuitTransfer(ContainerPatternEncodingTerm container,
                                                          IRecipeLayout recipeLayout, EntityPlayer player,
                                                          boolean maxTransfer, boolean doTransfer,
                                                          CallbackInfoReturnable<IRecipeTransferError> cir) {
        GTCircuitHelper.endPatternTransfer();
    }
}
