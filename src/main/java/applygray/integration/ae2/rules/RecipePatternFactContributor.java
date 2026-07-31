package applygray.integration.ae2.rules;

import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.recipes.Recipe;

/** SPI for third-party modules that need to contribute facts without taking over routing decisions. */
public interface RecipePatternFactContributor {

    default void contributeRecipeFacts(Recipe recipe, java.util.Map<String, Object> facts) {
    }

    default void contributeMachineFacts(MultiblockControllerBase controller, java.util.Map<String, Object> facts) {
    }
}
