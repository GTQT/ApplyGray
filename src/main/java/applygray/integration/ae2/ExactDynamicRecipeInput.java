package applygray.integration.ae2;

import ae2.api.stacks.AEKey;

/** Marks a frozen dynamic RecipeMap input whose only valid key is known exactly. */
public interface ExactDynamicRecipeInput {

    /** True when the input has exactly one permitted key and may bypass AE2's fuzzy template enumeration. */
    boolean isExactDynamicRecipeInput();

    /** Returns the sole permitted input key when {@link #isExactDynamicRecipeInput()} is true. */
    AEKey getExactDynamicRecipeInputKey();

    /** Returns the per-template amount associated with the exact input key. */
    long getExactDynamicRecipeInputAmount();
}
