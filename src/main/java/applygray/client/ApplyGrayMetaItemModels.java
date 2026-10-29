package applygray.client;

import ae2.api.stacks.AEKeyType;
import applygray.common.eco.EcoItems;
import applygray.common.eco.EcoTiers;
import applygray.api.ApplyGrayAPI;
import applygray.common.items.ApplyGrayMetaItems;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * ApplyGray meta 物品的客户端模型注册。
 *
 * <p>GT 的 {@code MetaItem#registerModels()} 只会为 {@code gregtech:metaitems/...} 注册模型，
 * 附加模组的 meta 物品必须自己指定模型位置，否则会渲染成缺失模型。
 */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID, value = Side.CLIENT)
public final class ApplyGrayMetaItemModels {

    private ApplyGrayMetaItemModels() {}

    @SubscribeEvent
    public static void register(ModelRegistryEvent event) {
        Item item = ApplyGrayMetaItems.META_ITEM;
        if (item == null) return;
        ModelLoader.setCustomModelResourceLocation(item, ApplyGrayMetaItems.AE_BUILDER_META,
                new ModelResourceLocation(ApplyGrayAPI.MODID + ":metaitems/ae_multiblock_builder",
                        "inventory"));
        for (int tier = 1; tier <= EcoTiers.COUNT; tier++) {
            ModelLoader.setCustomModelResourceLocation(item, EcoItems.MEMORY_META + tier - 1,
                    new ModelResourceLocation(ApplyGrayAPI.MODID + ":metaitems/eco_memory_cell_" + tier, "inventory"));
        }
        for (var cell : EcoItems.CELLS) {
            String family = cell.getKeyType() == AEKeyType.fluids() ? "fluid" : "item";
            ModelLoader.setCustomModelResourceLocation(cell, 0,
                    new ModelResourceLocation(ApplyGrayAPI.MODID + ":metaitems/eco_" + family + "_cell_" + cell.ecoTier(), "inventory"));
        }
    }
}
