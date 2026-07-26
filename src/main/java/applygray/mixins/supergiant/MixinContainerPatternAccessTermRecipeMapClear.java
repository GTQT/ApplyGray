package applygray.mixins.supergiant;

import applygray.integration.ae2.RecipeMapPatternAccessActions;

import ae2.api.storage.IPatternAccessTermContainerHost;
import ae2.container.implementations.ContainerPatternAccessTerm;
import ae2.container.implementations.PatternAccessSupport;

import net.minecraft.entity.player.InventoryPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ContainerPatternAccessTerm.class, remap = false)
public abstract class MixinContainerPatternAccessTermRecipeMapClear {

    @Shadow @Final
    private PatternAccessSupport<?> patternAccessSupport;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void applygray$registerRecipeMapClearAction(InventoryPlayer playerInventory,
                                                        IPatternAccessTermContainerHost host,
                                                        CallbackInfo ci) {
        RecipeMapPatternAccessActions.register((ContainerPatternAccessTerm) (Object) this, patternAccessSupport);
    }
}
