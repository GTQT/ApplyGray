package applygray.mixins.supergiant;

import applygray.integration.ae2.RecipeMapPatternAccessActions;

import ae2.api.storage.IPEATermContainerHost;
import ae2.container.GuiIds;
import ae2.container.implementations.ContainerPEATerm;
import ae2.container.implementations.PatternAccessSupport;

import net.minecraft.entity.player.InventoryPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ContainerPEATerm.class, remap = false)
public abstract class MixinContainerPEATermRecipeMapClear {

    @Shadow @Final
    private PatternAccessSupport<?> patternAccessSupport;

    @Inject(method = "<init>(Lae2/container/GuiIds$GuiKey;Lnet/minecraft/entity/player/InventoryPlayer;Lae2/api/storage/IPEATermContainerHost;)V",
            at = @At("RETURN"))
    private void applygray$registerRecipeMapClearAction(GuiIds.GuiKey guiKey, InventoryPlayer playerInventory,
                                                        IPEATermContainerHost host, CallbackInfo ci) {
        RecipeMapPatternAccessActions.register((ContainerPEATerm) (Object) this, patternAccessSupport);
    }
}
