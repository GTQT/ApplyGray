package applygray.mixins.gregtech;

import applygray.integration.ae2.DynamicRecipePatternRegistry;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps recipe-content fingerprints and dynamic output indexes coherent after script reloads. */
@Mixin(value = RecipeMap.class, remap = false)
public abstract class MixinRecipeMapBindingInvalidation {

    @Inject(method = {"compileRecipe", "removeRecipe"}, at = @At("RETURN"))
    private void applygray$invalidateBoundRecipes(Recipe recipe, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) {
            DynamicRecipePatternRegistry.invalidateRecipeMapContents((RecipeMap<?>) (Object) this);
        }
    }

    @Inject(method = "removeAllRecipes", at = @At("RETURN"))
    private void applygray$invalidateAllBoundRecipes(CallbackInfo callback) {
        DynamicRecipePatternRegistry.invalidateRecipeMapContents((RecipeMap<?>) (Object) this);
    }
}
