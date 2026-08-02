package applygray.common;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.ApplyGrayMetaItems;
import applygray.integration.ae2.rules.RecipePatternRules;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.registry.MTEManager;

import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID)
public final class ApplyGrayEventHandler {

    private static final int RECIPE_PATTERN_RULE_CHECK_INTERVAL_TICKS = 20 * 10;
    private static int recipePatternRuleCheckTicks;

    private ApplyGrayEventHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void registerMTERegistry(MTEManager.MTERegistryEvent event) {
        GregTechAPI.mteManager.createRegistry(ApplyGrayAPI.MODID);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void registerItems(RegistryEvent.Register<Item> event) {
        ApplyGrayMetaItems.init(event.getRegistry());
    }

    /** One global hot-reload probe; RecipeMap providers must not each walk the rules directory. */
    @SubscribeEvent
    public static void checkRecipePatternRules(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++recipePatternRuleCheckTicks < RECIPE_PATTERN_RULE_CHECK_INTERVAL_TICKS) return;

        recipePatternRuleCheckTicks = 0;
        RecipePatternRules.reloadIfChanged();
    }
}
