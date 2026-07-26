package applygray.integration.ae2;

import ae2.api.implementations.blockentities.PatternContainerGroup;

/** Client bridge for zero-slot RecipeMap provider entries in the pattern access terminal. */
public interface RecipeMapPatternAccessDisplay {

    long NO_PROVIDER = Long.MIN_VALUE;

    long applygray$getRecipeMapPatternProviderId(PatternContainerGroup group);
}
