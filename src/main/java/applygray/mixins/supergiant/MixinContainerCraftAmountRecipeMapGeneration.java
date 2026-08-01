package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipePatternRegistry;
import applygray.integration.ae2.IRecipePatternGeneration;
import applygray.integration.ae2.PatternGenerationTreeData;

import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.security.IActionHost;
import ae2.api.stacks.AEKey;
import ae2.api.storage.ISubGuiHost;
import ae2.container.AEBaseContainer;
import ae2.container.implementations.ContainerCraftAmount;
import ae2.container.guisync.GuiSync;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a standalone RecipeMap pattern-generation action to AE2's quantity selection dialog. */
@Mixin(value = ContainerCraftAmount.class, remap = false)
public abstract class MixinContainerCraftAmountRecipeMapGeneration implements IRecipePatternGeneration {

    @Unique private static final String APPLYGRAY_GENERATE_RECIPE_MAP_PATTERNS =
            "applygray.generate_recipe_map_patterns";

    @Shadow @Nullable private AEKey whatToCraft;

    @GuiSync(31)
    @Unique private volatile PatternGenerationTreeData applygray$generationTree = PatternGenerationTreeData.idle();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void applygray$registerRecipeMapGenerationAction(InventoryPlayer playerInventory, ISubGuiHost host,
                                                             CallbackInfo ci) {
        ((InvokerAEBaseContainer) (Object) this).applygray$registerClientAction(
                APPLYGRAY_GENERATE_RECIPE_MAP_PATTERNS,
                Integer.class,
                this::applygray$startRecipeMapPatternGeneration);
    }

    @Override
    public void applygray$generateOptimalRoutePatterns(int amount) {
        AEBaseContainer container = (AEBaseContainer) (Object) this;
        if (container.isClientSide()) {
            ((InvokerAEBaseContainer) container).applygray$sendClientAction(
                    APPLYGRAY_GENERATE_RECIPE_MAP_PATTERNS, amount);
            return;
        }
        applygray$startRecipeMapPatternGeneration(amount);
    }

    @Override
    public PatternGenerationTreeData applygray$getRecipePatternGenerationTree() {
        return applygray$generationTree;
    }

    @Unique
    private void applygray$startRecipeMapPatternGeneration(int amount) {
        AEBaseContainer container = (AEBaseContainer) (Object) this;
        if (container.isClientSide()) return;
        if (applygray$generationTree.getStatus() == PatternGenerationTreeData.Status.GENERATING) return;

        AEKey target = whatToCraft;
        Object containerTarget = container.getTarget();
        IActionHost actionHost = containerTarget instanceof IActionHost host ? host : null;
        IGridNode node = actionHost == null ? null : actionHost.getActionableNode();
        IGrid grid = node == null ? null : node.grid();
        World world = node == null ? null : node.getLevel();
        if (target == null || grid == null || amount <= 0 || amount > ContainerCraftAmount.MAX_AUTO_CRAFT_AMOUNT) {
            applygray$generationTree = PatternGenerationTreeData.unavailable();
            return;
        }

        applygray$generationTree = PatternGenerationTreeData.generating();
        DynamicRecipePatternRegistry.generatePatternTreeAsync(grid, world, target, amount,
                generatedTree -> applygray$generationTree = generatedTree);
    }
}
