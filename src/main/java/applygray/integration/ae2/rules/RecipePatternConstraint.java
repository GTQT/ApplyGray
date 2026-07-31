package applygray.integration.ae2.rules;

import applygray.integration.ae2.recipe.NormalizedRecipe;

import ae2.api.stacks.AEKey;

/** SPI for a constraint that cannot be expressed through the JSON predicate vocabulary. */
public interface RecipePatternConstraint {

    /**
     * Declares that this constraint can safely evaluate a third-party recipe property. Properties without an
     * affirmative declaration remain conservatively rejected before a virtual pattern reaches AE2.
     */
    default boolean supportsRecipeProperty(NormalizedRecipe recipe, String propertyKey) {
        return false;
    }

    /**
     * Returns an adapter-proven, per-execution lower bound for a chanced target, or zero when no proof exists.
     *
     * <p>This is consulted only with the {@link OutputPolicy#GUARANTEED_LOWER_BOUND} policy. Implementors must not
     * return an expected value: every successful execution must physically produce at least the returned amount.</p>
     */
    default long getGuaranteedOutputLowerBound(RuleContext context, AEKey target) {
        return 0;
    }

    void evaluate(RuleContext context, RuleDecision decision);
}
