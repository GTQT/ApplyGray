package applygray.client;

import applygray.api.ApplyGrayAPI;
import applygray.common.ApplyGrayBlocks;

import gregtech.common.blocks.MetaBlocks;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client model registration for the quantum storage unit block: every tier maps
 * its item meta to the matching blockstate variant.
 */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID, value = Side.CLIENT)
public final class QuantumStorageUnitItemModels {

    private QuantumStorageUnitItemModels() {}

    @SubscribeEvent
    public static void register(ModelRegistryEvent event) {
        var block = ApplyGrayBlocks.QUANTUM_STORAGE_UNIT;
        Item item = Item.getItemFromBlock(block);
        for (IBlockState state : block.getBlockState().getValidStates()) {
            ModelLoader.setCustomModelResourceLocation(item,
                    block.getMetaFromState(state),
                    new ModelResourceLocation(block.getRegistryName(),
                            MetaBlocks.statePropertiesToString(state.getProperties())));
        }
    }
}
