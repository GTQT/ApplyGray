package applygray.integration.ae2;

import applygray.ApplyGrayMod;
import applygray.mixins.supergiant.AccessorPatternAccessSupport;
import applygray.mixins.supergiant.AccessorPatternAccessSupportContainerTracker;
import applygray.mixins.supergiant.InvokerAEBaseContainer;

import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;

import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.container.AEBaseContainer;
import ae2.container.implementations.PatternAccessSupport;
import ae2.helpers.patternprovider.PatternContainer;

import net.minecraft.util.text.TextComponentTranslation;

/** Shared terminal action for clearing the visible RecipeMap provider group. */
public final class RecipeMapPatternAccessActions {

    public static final String CLEAR_DYNAMIC_PATTERNS = "applygray.clear_recipe_map_patterns";

    private RecipeMapPatternAccessActions() {
    }

    public static void register(AEBaseContainer container, PatternAccessSupport<?> patternAccessSupport) {
        ((InvokerAEBaseContainer) container).applygray$registerClientAction(
                CLEAR_DYNAMIC_PATTERNS,
                Long.class,
                inventoryId -> clear(container, patternAccessSupport, inventoryId));
    }

    public static void send(AEBaseContainer container, long inventoryId) {
        ApplyGrayMod.LOGGER.info("Pattern access terminal requested a dynamic RecipeMap pattern clear for provider {}",
                inventoryId);
        ((InvokerAEBaseContainer) container).applygray$sendClientAction(CLEAR_DYNAMIC_PATTERNS, inventoryId);
    }

    private static void clear(AEBaseContainer container, PatternAccessSupport<?> patternAccessSupport,
                              long inventoryId) {
        var providerTrackers = ((AccessorPatternAccessSupport) (Object) patternAccessSupport)
                .applygray$getProviderTrackers();
        Object tracker = providerTrackers.get(inventoryId);
        if (!(tracker instanceof AccessorPatternAccessSupportContainerTracker trackerAccessor)) {
            ApplyGrayMod.LOGGER.warn("Ignored RecipeMap pattern clear request for unavailable terminal provider {}",
                    inventoryId);
            return;
        }

        PatternContainer provider = trackerAccessor.applygray$getPatternContainer();
        if (!(provider instanceof MetaTileEntityMERecipeMapPatternProvider)) {
            ApplyGrayMod.LOGGER.warn("Ignored RecipeMap pattern clear request for non-RecipeMap terminal provider {}",
                    inventoryId);
            return;
        }

        PatternContainerGroup group = provider.getTerminalGroup();
        int providerCount = 0;
        int patternCount = 0;
        for (Object candidate : providerTrackers.values()) {
            if (!(candidate instanceof AccessorPatternAccessSupportContainerTracker candidateAccessor)) {
                continue;
            }

            PatternContainer candidateProvider = candidateAccessor.applygray$getPatternContainer();
            if (candidateProvider instanceof MetaTileEntityMERecipeMapPatternProvider recipeMapProvider
                    && group.equals(candidateProvider.getTerminalGroup())) {
                providerCount++;
                patternCount += recipeMapProvider.clearDynamicPatterns();
            }
        }
        ApplyGrayMod.LOGGER.info("Pattern access terminal cleared {} dynamic RecipeMap pattern(s) from {} provider(s)",
                patternCount, providerCount);
        container.getPlayer().sendStatusMessage(new TextComponentTranslation(
                "applygray.gui.pattern_access.cleared_dynamic_patterns", patternCount), true);
    }
}
