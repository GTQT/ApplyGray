package applygray.integration.ae2;

import applygray.mixins.supergiant.AccessorPatternAccessSupport;
import applygray.mixins.supergiant.AccessorPatternAccessSupportContainerTracker;
import applygray.mixins.supergiant.InvokerAEBaseContainer;

import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;

import ae2.container.AEBaseContainer;
import ae2.container.implementations.PatternAccessSupport;
import ae2.helpers.patternprovider.PatternContainer;

/** Shared terminal action for clearing one visible RecipeMap pattern provider. */
public final class RecipeMapPatternAccessActions {

    public static final String CLEAR_DYNAMIC_PATTERNS = "applygray.clear_recipe_map_patterns";

    private RecipeMapPatternAccessActions() {
    }

    public static void register(AEBaseContainer container, PatternAccessSupport<?> patternAccessSupport) {
        ((InvokerAEBaseContainer) container).applygray$registerClientAction(
                CLEAR_DYNAMIC_PATTERNS,
                Long.class,
                inventoryId -> clear(patternAccessSupport, inventoryId));
    }

    public static void send(AEBaseContainer container, long inventoryId) {
        ((InvokerAEBaseContainer) container).applygray$sendClientAction(CLEAR_DYNAMIC_PATTERNS, inventoryId);
    }

    private static void clear(PatternAccessSupport<?> patternAccessSupport, long inventoryId) {
        Object tracker = ((AccessorPatternAccessSupport) (Object) patternAccessSupport)
                .applygray$getProviderTrackers()
                .get(inventoryId);
        if (!(tracker instanceof AccessorPatternAccessSupportContainerTracker trackerAccessor)) {
            return;
        }

        PatternContainer provider = trackerAccessor.applygray$getPatternContainer();
        if (provider instanceof MetaTileEntityMERecipeMapPatternProvider recipeMapProvider) {
            recipeMapProvider.clearDynamicPatterns();
        }
    }
}
