package applygray.mixins.ae2fc;

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

/**
 * Closes the transfer scope even when HEI reports a transfer error.
 */
@Mixin(value = PatternEncodingRecipeTransferHandler.class, remap = false)
public abstract class MixinExtendedFluidPatternTerminalRecipeTransferHandler {

    @Inject(method = "transferRecipe", at = @At("RETURN"), remap = false)
    private void applygray$endTransfer(ContainerPatternEncodingTerm container, IRecipeLayout recipeLayout,
                                       EntityPlayer player, boolean maxTransfer, boolean doTransfer,
                                       CallbackInfoReturnable<IRecipeTransferError> cir) {
        GTCircuitHelper.endPatternTransfer();
    }
}
