package applygray.mixins.supergiant;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternRegistry;
import applygray.integration.ae2.IRecipePatternRebuildable;

import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.crafting.CalculationStrategy;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.security.IActionHost;
import ae2.api.stacks.AEKey;
import ae2.api.storage.ISubGuiHost;
import ae2.container.AEBaseContainer;
import ae2.container.implementations.ContainerCraftConfirm;
import net.minecraft.entity.player.InventoryPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an ApplyGray-only full RecipeMap rebuild action to the crafting confirmation container. */
@Mixin(value = ContainerCraftConfirm.class, remap = false)
public abstract class MixinContainerCraftConfirmLazyRecipeMap implements IRecipePatternRebuildable {

    @Unique private static final String APPLYGRAY_REBUILD_RECIPE_MAP_PATTERNS =
            "applygray.rebuild_recipe_map_patterns";

    @Shadow @Nullable private ICraftingPlan result;
    @Shadow @Nullable private AEKey whatToCraft;
    @Shadow private long amount;
    @Shadow private CalculationStrategy strategy;
    @Shadow public abstract boolean planJob(AEKey what, long amount, CalculationStrategy strategy);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void applygray$registerFullRecipeMapRebuildAction(InventoryPlayer playerInventory, ISubGuiHost host,
                                                              CallbackInfo ci) {
        ((InvokerAEBaseContainer) (Object) this).applygray$registerClientAction(
                APPLYGRAY_REBUILD_RECIPE_MAP_PATTERNS,
                Boolean.class,
                ignored -> applygray$performFullRecipeMapRebuild());
    }

    @Unique
    private void applygray$performFullRecipeMapRebuild() {
        AEBaseContainer container = (AEBaseContainer) (Object) this;
        if (container.getPlayer().world.isRemote || result == null) return;
        Object target = container.getTarget();
        if (!(target instanceof IActionHost actionHost)) return;

        IGridNode node = actionHost.getActionableNode();
        IGrid grid = node == null ? null : node.grid();
        if (grid == null) return;

        int cleared = DynamicRecipePatternRegistry.invalidatePlanPatternsAndRecipeOutputIndexes(grid,
                result.patternTimes().keySet());
        ApplyGrayMod.LOGGER.info("Starting ApplyGray's Supergiant optimal rebuild after clearing {} lazy RecipeMap " +
                "patterns from the current chain", cleared);
        if (!applygray$startOptimalRebuildCalculation()) {
            ApplyGrayMod.LOGGER.warn("Could not start ApplyGray's Supergiant optimal rebuild because the current " +
                    "crafting target is unavailable");
        }
    }

    /**
     * Deliberately separate from ContainerCraftConfirm.replan(): the native AE2 button retains its normal behavior.
     * planJob is the container's public calculation launcher and preserves its job-cancellation and GUI state logic.
     */
    @Unique
    private boolean applygray$startOptimalRebuildCalculation() {
        return whatToCraft != null && planJob(whatToCraft, amount, strategy);
    }

    @Override
    public void applygray$rebuildOptimalRecipePlan() {
        AEBaseContainer container = (AEBaseContainer) (Object) this;
        if (container.isClientSide()) {
            ((InvokerAEBaseContainer) (Object) this).applygray$sendClientAction(
                    APPLYGRAY_REBUILD_RECIPE_MAP_PATTERNS, Boolean.TRUE);
        } else {
            applygray$performFullRecipeMapRebuild();
        }
    }
}
