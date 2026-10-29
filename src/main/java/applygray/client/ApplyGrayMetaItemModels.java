package applygray.client;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.ApplyGrayMetaItems;

import gregtech.api.items.metaitem.MetaItem;

import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * ApplyGray 自定义 MetaItem 的客户端模型注册。
 *
 * <p>GT 的 {@code ClientProxy} 只调用它自己的 {@code MetaItems.registerModels()}；附加模组持有的
 * {@link MetaItem} 实例必须各自注册，否则会渲染成缺失模型。模型路径仍走 GT 的
 * {@code gregtech:metaitems/<unlocalizedName>} 约定，所以文件放在
 * {@code assets/gregtech/models/item/metaitems/}。</p>
 */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID, value = Side.CLIENT)
public final class ApplyGrayMetaItemModels {

    private ApplyGrayMetaItemModels() {}

    @SubscribeEvent
    public static void register(ModelRegistryEvent event) {
        register(ApplyGrayMetaItems.META_ITEM);

        register(ApplyGrayMetaItems.ECO_MEMORY_T1);
        register(ApplyGrayMetaItems.ECO_MEMORY_T2);
        register(ApplyGrayMetaItems.ECO_MEMORY_T3);
        register(ApplyGrayMetaItems.ECO_MEMORY_T4);
        register(ApplyGrayMetaItems.ECO_MEMORY_T5);

        register(ApplyGrayMetaItems.ECO_ITEM_CELL_1);
        register(ApplyGrayMetaItems.ECO_ITEM_CELL_2);
        register(ApplyGrayMetaItems.ECO_ITEM_CELL_3);
        register(ApplyGrayMetaItems.ECO_ITEM_CELL_4);
        register(ApplyGrayMetaItems.ECO_ITEM_CELL_5);

        register(ApplyGrayMetaItems.ECO_FLUID_CELL_1);
        register(ApplyGrayMetaItems.ECO_FLUID_CELL_2);
        register(ApplyGrayMetaItems.ECO_FLUID_CELL_3);
        register(ApplyGrayMetaItems.ECO_FLUID_CELL_4);
        register(ApplyGrayMetaItems.ECO_FLUID_CELL_5);
    }

    private static void register(MetaItem<?> item) {
        if (item != null) item.registerModels();
    }
}
