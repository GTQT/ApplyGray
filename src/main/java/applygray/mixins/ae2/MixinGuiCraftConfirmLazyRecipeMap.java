package applygray.mixins.ae2;

import applygray.integration.ae2.IRecipePatternRebuildable;

import ae2.client.gui.Icon;
import ae2.client.gui.me.crafting.GuiCraftConfirm;
import ae2.client.gui.style.GuiStyle;
import ae2.client.gui.widgets.TabButton;
import ae2.container.implementations.ContainerCraftConfirm;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiCraftConfirm.class, remap = false)
public abstract class MixinGuiCraftConfirmLazyRecipeMap {

    @Unique private TabButton applygray$rebuildButton;

    @Shadow
    protected abstract <B extends GuiButton> B addToLeftToolbar(B button);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void applygray$addRebuildButton(ContainerCraftConfirm container, InventoryPlayer playerInventory,
                                            ITextComponent title, GuiStyle style, CallbackInfo ci) {
        applygray$rebuildButton = addToLeftToolbar(new TabButton(Icon.ADVANCED_MEMORY_CARD_REFRESH,
                new TextComponentTranslation("applygray.gui.rebuild_recipe_patterns"),
                () -> ((IRecipePatternRebuildable) (Object) container)
                        .applygray$clearTargetPatternsAndRecalculate()));
    }
}
