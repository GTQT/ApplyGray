package applygray.client.mattermanipulator;

import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.item.MatterManipulatorItems;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.registry.MTERegistry;
import gregtech.client.model.SimpleStateMapper;
import gregtech.client.renderer.handler.MetaTileEntityRenderer;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.relauncher.Side;

/** Client model registration for every standalone Matter Manipulator item. */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID, value = Side.CLIENT)
public final class MatterManipulatorItemModels {

    private MatterManipulatorItemModels() {}

    @SubscribeEvent
    public static void register(ModelRegistryEvent event) {
        for (Item item : MatterManipulatorItems.allItems()) {
            ModelLoader.setCustomModelResourceLocation(item, 0,
                    new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }

        MTERegistry machineRegistry = GregTechAPI.mteManager.getRegistry(ApplyGrayAPI.MODID);
        Item machineItem = Item.getItemFromBlock(machineRegistry.getBlock());
        ModelLoader.setCustomStateMapper(machineRegistry.getBlock(),
                new SimpleStateMapper(MetaTileEntityRenderer.MODEL_LOCATION));
        ModelLoader.setCustomMeshDefinition(machineItem, stack -> MetaTileEntityRenderer.MODEL_LOCATION);
        ModelLoader.setCustomModelResourceLocation(machineItem, 0, MetaTileEntityRenderer.MODEL_LOCATION);
    }
}
