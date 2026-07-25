package applygray.mixins.ae2;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternRegistry;
import applygray.integration.ae2.IRecipePatternRebuildable;

import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.security.IActionHost;
import ae2.api.stacks.AEKey;
import ae2.container.AEBaseContainer;
import ae2.container.implementations.ContainerCraftConfirm;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates a lazy target before Supergiant's native craft-confirm replanning action runs. */
@Mixin(value = ContainerCraftConfirm.class, remap = false)
public abstract class MixinContainerCraftConfirmLazyRecipeMap implements IRecipePatternRebuildable {

    @Shadow @Nullable private AEKey whatToCraft;
    @Shadow public abstract void replan();

    @Inject(method = "replan", at = @At("HEAD"))
    private void applygray$invalidateLazyTargetBeforeReplan(CallbackInfo ci) {
        AEBaseContainer container = (AEBaseContainer) (Object) this;
        if (container.getPlayer().world.isRemote || whatToCraft == null) return;
        Object target = container.getTarget();
        if (!(target instanceof IActionHost actionHost)) return;

        IGridNode node = actionHost.getActionableNode();
        IGrid grid = node == null ? null : node.grid();
        if (grid == null) return;

        DynamicRecipePatternRegistry.invalidateTarget(grid, whatToCraft);
        ApplyGrayMod.LOGGER.info("Rebuilding Supergiant crafting calculation after clearing lazy patterns for {}",
                whatToCraft);
    }

    @Override
    public void applygray$clearTargetPatternsAndRecalculate() {
        replan();
    }
}
