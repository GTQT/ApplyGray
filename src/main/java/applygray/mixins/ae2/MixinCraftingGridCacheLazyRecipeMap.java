package applygray.mixins.ae2;

import applygray.integration.ae2.DynamicRecipePatternDetails;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingGridCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Adds lazy RecipeMap patterns only when AE2's crafting tree asks for an output. */
@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class MixinCraftingGridCacheLazyRecipeMap {

    @Shadow @Final
    private IGrid grid;

    @Inject(method = "getCraftingFor", at = @At("RETURN"), cancellable = true)
    private void applygray$appendLazyRecipeMapPatterns(IAEItemStack requested,
                                                       ICraftingPatternDetails parent,
                                                       int depth,
                                                       World world,
                                                       CallbackInfoReturnable<ImmutableCollection<ICraftingPatternDetails>> cir) {
        List<ICraftingPatternDetails> dynamic = DynamicRecipePatternRegistry.findPatterns(grid, requested);
        if (dynamic.isEmpty()) return;

        List<ICraftingPatternDetails> merged = new ArrayList<>(cir.getReturnValue());
        merged.addAll(dynamic);
        merged.sort((left, right) -> {
            boolean leftDynamic = left instanceof DynamicRecipePatternDetails;
            boolean rightDynamic = right instanceof DynamicRecipePatternDetails;
            if (leftDynamic && rightDynamic) {
                DynamicRecipePatternDetails l = (DynamicRecipePatternDetails) left;
                DynamicRecipePatternDetails r = (DynamicRecipePatternDetails) right;
                int raw = Long.compare(l.getRawMaterialCost(), r.getRawMaterialCost());
                if (raw != 0) return raw;
                int steps = Integer.compare(l.getStepCost(), r.getStepCost());
                return steps != 0 ? steps : l.getRecipeKey().compareTo(r.getRecipeKey());
            }
            if (leftDynamic) return -1;
            if (rightDynamic) return 1;
            return Integer.compare(right.getPriority(), left.getPriority());
        });
        cir.setReturnValue(ImmutableList.copyOf(merged));
    }

    @Inject(method = "getMediums", at = @At("RETURN"), cancellable = true)
    private void applygray$getLazyRecipeMapMedium(ICraftingPatternDetails details,
                                                  CallbackInfoReturnable<List<ICraftingMedium>> cir) {
        ICraftingMedium medium = DynamicRecipePatternRegistry.getMedium(details);
        if (medium != null) cir.setReturnValue(ImmutableList.of(medium));
    }
}
