package applygray.integration.ae2;

import applygray.integration.ae2.recipe.RecipeBinding;

import gregtech.api.capability.IRecipeMapBoundInput;

import org.jetbrains.annotations.Nullable;

/** A buffer input that may execute only the exact recipe described by its binding. */
public interface IRecipeBoundInput extends IRecipeMapBoundInput {

    @Nullable
    RecipeBinding getRecipeBinding();

    /**
     * Lets a bound buffer reject execution after its provider's rule or machine-capability snapshot changed.
     * Unbound legacy buffers retain the RecipeMap-only behavior from {@link IRecipeMapBoundInput}.
     */
    default boolean isRecipeBindingCurrent() {
        return true;
    }
}
