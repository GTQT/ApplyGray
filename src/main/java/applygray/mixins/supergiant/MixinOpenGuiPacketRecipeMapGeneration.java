package applygray.mixins.supergiant;

import applygray.client.gui.GuiRecipePatternCraftAmount;

import ae2.client.gui.me.crafting.GuiCraftAmount;
import ae2.client.gui.style.GuiStyle;
import ae2.container.AEBaseContainer;
import ae2.container.GuiIds;
import ae2.container.implementations.ContainerCraftAmount;
import ae2.core.network.clientbound.OpenGuiPacket;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.InventoryPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces only AE2's amount-screen factory result so the original GUI class remains unmodified. */
@Mixin(value = OpenGuiPacket.class, remap = false)
public abstract class MixinOpenGuiPacketRecipeMapGeneration {

    @Shadow private GuiIds.GuiKey guiKey;

    @Inject(method = "createScreen", at = @At("RETURN"), cancellable = true)
    private void applygray$createRecipePatternCraftAmount(AEBaseContainer container, InventoryPlayer inventory,
                                                           CallbackInfoReturnable<GuiScreen> cir) {
        if (guiKey == GuiIds.GuiKey.CRAFT_AMOUNT && cir.getReturnValue() instanceof GuiCraftAmount craftAmount) {
            GuiStyle style = craftAmount.getStyle();
            if (style != null) {
                cir.setReturnValue(new GuiRecipePatternCraftAmount((ContainerCraftAmount) container, inventory,
                        style));
            }
        }
    }
}
