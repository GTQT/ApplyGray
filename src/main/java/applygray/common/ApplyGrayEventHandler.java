package applygray.common;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.ApplyGrayMetaItems;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.registry.MTEManager;

import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID)
public final class ApplyGrayEventHandler {

    private ApplyGrayEventHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void registerMTERegistry(MTEManager.MTERegistryEvent event) {
        GregTechAPI.mteManager.createRegistry(ApplyGrayAPI.MODID);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void registerItems(RegistryEvent.Register<Item> event) {
        ApplyGrayMetaItems.init(event.getRegistry());
    }
}
