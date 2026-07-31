package applygray.mixins.gregtech;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.IRecipeBoundInput;
import applygray.integration.ae2.recipe.RecipeBinding;
import applygray.integration.ae2.recipe.RecipeBindingResolver;

import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.impl.MultiblockRecipeLogic;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;

import net.minecraftforge.items.IItemHandlerModifiable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents a bound dynamic buffer from falling back to an arbitrary same-map matching recipe. */
@Mixin(value = MultiblockRecipeLogic.class, remap = false)
public abstract class MixinMultiblockRecipeLogicRecipeBinding {

    private static final long WARN_INTERVAL_NANOS = 5_000_000_000L;
    private static final Map<String, Long> LAST_WARN_NANOS = new ConcurrentHashMap<>();

    @Shadow
    public abstract boolean checkRecipe(Recipe recipe);

    @Inject(method = "findRecipe", at = @At("HEAD"), cancellable = true)
    private void applygray$findExactBoundRecipe(RecipeMap<?> recipeMap, long maxVoltage,
                                                IItemHandlerModifiable itemInputs,
                                                IMultipleTankHandler fluidInputs,
                                                CallbackInfoReturnable<Recipe> callback) {
        if (!(itemInputs instanceof IRecipeBoundInput boundInput)) return;
        RecipeBinding binding = boundInput.getRecipeBinding();
        if (binding == null) return;

        if (!boundInput.isRecipeBindingCurrent()) {
            warn(binding, "BINDING_CONTEXT_STALE");
            callback.setReturnValue(null);
            return;
        }

        RecipeBindingResolver.Resolution resolution = RecipeBindingResolver.resolve(binding, recipeMap);
        if (!resolution.isResolved()) {
            warn(binding, resolution.getReasonCode());
            callback.setReturnValue(null);
            return;
        }

        Recipe recipe = resolution.getRecipe();
        if (recipe == null || !recipe.matches(false, itemInputs, fluidInputs)) {
            warn(binding, "BOUND_INPUT_MISMATCH");
            callback.setReturnValue(null);
            return;
        }
        if (!checkRecipe(recipe)) {
            warn(binding, "CONTROLLER_CAPABILITY_REJECTED");
            callback.setReturnValue(null);
            return;
        }
        callback.setReturnValue(recipe);
    }

    private static void warn(RecipeBinding binding, String reasonCode) {
        String key = binding.describe() + ':' + reasonCode;
        long now = System.nanoTime();
        Long previous = LAST_WARN_NANOS.put(key, now);
        if (previous == null || now - previous >= WARN_INTERVAL_NANOS) {
            ApplyGrayMod.LOGGER.warn("Rejected exact RecipeMap execution recipeMapId={} recipeFingerprint={} " +
                            "target={} ruleSetVersion={} decision=rejected reasonCode={}",
                    binding.getRecipeMapId(), binding.getRecipeFingerprint(), binding.getTargetKey(),
                    binding.getRuleSetVersion(), reasonCode);
        }
    }
}
