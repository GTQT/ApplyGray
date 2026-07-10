package applygray.mixins.ae2;

import java.io.IOException;

import appeng.client.gui.implementations.GuiCraftConfirm;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketValueConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiCraftConfirm.class, remap = false)
public abstract class MixinGuiCraftConfirmLazyRecipeMap {

    @Shadow private GuiButton start;
    @Unique private GuiButton applygray$rebuildButton;

    @Inject(method = "func_73866_w_", at = @At("RETURN"))
    private void applygray$addRebuildButton(CallbackInfo ci) {
        if (start == null) return;
        applygray$rebuildButton = new GuiButton(9107, start.x - 82, start.y, 80, 20, I18n.format("applygray.gui.rebuild_recipe_patterns"));
        applygray$rebuildButton.enabled = start.enabled;
        ((MixinGuiScreenButtonListAccessor) (Object) this).applygray$getButtonList().add(applygray$rebuildButton);
    }

    @Inject(method = "drawBG", at = @At("RETURN"))
    private void applygray$syncRebuildButton(int offsetX, int offsetY, int mouseX, int mouseY, CallbackInfo ci) {
        if (applygray$rebuildButton != null && start != null) {
            applygray$rebuildButton.enabled = start.enabled;
        }
    }
    @Inject(method = "func_146284_a", at = @At("HEAD"), cancellable = true)
    private void applygray$handleRebuildButton(GuiButton button, CallbackInfo ci) throws IOException {
        if (button != applygray$rebuildButton) return;
        NetworkHandler.instance().sendToServer(new PacketValueConfig(
                "ApplyGray.RebuildRecipePatterns", "Rebuild"));
        applygray$rebuildButton.enabled = false;
        ci.cancel();
    }
}