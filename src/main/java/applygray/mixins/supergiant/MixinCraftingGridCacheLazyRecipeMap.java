package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipePatternDetails;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IGrid;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.stacks.AEKey;
import ae2.me.service.CraftingService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Adds lazy RecipeMap patterns only when Supergiant's crafting service asks for an output. */
@Mixin(value = CraftingService.class, remap = false)
public abstract class MixinCraftingGridCacheLazyRecipeMap {

    @Shadow @Final
    private IGrid grid;

    @Inject(method = "getCraftingFor", at = @At("RETURN"), cancellable = true)
    private void applygray$appendLazyRecipeMapPatterns(AEKey requested,
                                                       CallbackInfoReturnable<java.util.Collection<IPatternDetails>> cir) {
        List<IPatternDetails> dynamic = DynamicRecipePatternRegistry.findPatterns(grid, requested);
        if (dynamic.isEmpty()) return;

        List<IPatternDetails> merged = new ArrayList<>(cir.getReturnValue());
        for (IPatternDetails detail : dynamic) {
            if (!merged.contains(detail)) {
                merged.add(detail);
            }
        }
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
            return 0;
        });
        cir.setReturnValue(java.util.Collections.unmodifiableList(merged));
    }

    @Inject(method = "getProviders", at = @At("RETURN"), cancellable = true)
    private void applygray$getLazyRecipeMapProvider(IPatternDetails details,
                                                    CallbackInfoReturnable<Iterable<ICraftingProvider>> cir) {
        ICraftingProvider provider = DynamicRecipePatternRegistry.getProvider(details);
        if (provider != null) cir.setReturnValue(List.of(provider));
    }
}
