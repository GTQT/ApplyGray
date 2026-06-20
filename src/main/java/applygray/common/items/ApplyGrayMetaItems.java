package applygray.common.items;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.behaviors.OrderBehavior;

import gregtech.api.items.metaitem.MetaItem;
import gregtech.api.items.metaitem.StandardMetaItem;

import net.minecraft.item.Item;
import net.minecraftforge.registries.IForgeRegistry;

public final class ApplyGrayMetaItems {

    public static ApplyGrayMetaItem META_ITEM;
    public static MetaItem<?>.MetaValueItem ORDER;

    private ApplyGrayMetaItems() {}

    public static void init(IForgeRegistry<Item> registry) {
        META_ITEM = new ApplyGrayMetaItem();
        META_ITEM.setRegistryName(ApplyGrayAPI.MODID, "meta_item");
        registry.register(META_ITEM);
        META_ITEM.registerSubItems();
    }

    public static final class ApplyGrayMetaItem extends StandardMetaItem {

        @Override
        public void registerSubItems() {
            ORDER = addItem(0, "order").addComponents(new OrderBehavior());
        }
    }
}
