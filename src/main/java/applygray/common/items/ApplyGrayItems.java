package applygray.common.items;

import net.minecraft.item.Item;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * 不依附于 GT {@code MetaItem} 的独立物品。
 * <p>
 * 订单物品的名称与样板匹配都按 NBT 工作，且需要自己的模型路径，因此保留为独立 {@link Item}；
 * GT meta 物品仍在 {@link ApplyGrayMetaItems} 注册。
 */
public final class ApplyGrayItems {

    public static final ApplyGrayOrderItem ORDER = new ApplyGrayOrderItem();

    private ApplyGrayItems() {}

    public static void register(IForgeRegistry<Item> registry) {
        registry.register(ORDER);
    }
}
