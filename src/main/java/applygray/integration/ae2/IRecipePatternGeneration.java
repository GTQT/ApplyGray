package applygray.integration.ae2;

/** Starts and exposes independent RecipeMap pattern generation for the current crafting target. */
public interface IRecipePatternGeneration {

    void applygray$generateOptimalRoutePatterns(int amount);

    PatternGenerationTreeData applygray$getRecipePatternGenerationTree();
}
