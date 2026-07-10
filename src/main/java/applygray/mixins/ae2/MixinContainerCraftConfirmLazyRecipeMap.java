package applygray.mixins.ae2;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternRegistry;
import applygray.integration.ae2.IRecipePatternRebuildable;

import net.minecraft.world.World;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.AEBaseContainer;
import appeng.container.implementations.ContainerCraftConfirm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.Future;

@Mixin(value = ContainerCraftConfirm.class, remap = false)
public abstract class MixinContainerCraftConfirmLazyRecipeMap implements IRecipePatternRebuildable {

    @Shadow private ICraftingJob result;
    @Shadow public abstract World getWorld();
    @Shadow public abstract void setJob(Future<ICraftingJob> job);

    @Override
    public void applygray$clearTargetPatternsAndRecalculate() {
        if (result == null || result.getOutput() == null) return;
        AEBaseContainer container = (AEBaseContainer) (Object) this;
        Object target = container.getTarget();
        if (!(target instanceof IActionHost)) return;

        IGridNode node = ((IActionHost) target).getActionableNode();
        if (node == null || node.getGrid() == null) return;
        IGrid grid = node.getGrid();
        IAEItemStack output = result.getOutput().copy();
        output.reset();

        DynamicRecipePatternRegistry.invalidateTarget(grid, output);
        ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        setJob(craftingGrid.beginCraftingJob(getWorld(), grid, container.getActionSource(), output, null));
        result = null;
        ApplyGrayMod.LOGGER.info("Rebuilding AE2 crafting calculation after clearing lazy patterns for {}", output);
    }
}