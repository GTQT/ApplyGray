package applygray.common.items;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.behaviors.AEMultiblockBuilderBehavior;
import applygray.common.items.behaviors.OrderBehavior;

import gregtech.api.items.metaitem.StandardMetaItem;
import gregtech.common.creativetab.GTCreativeTabs;

/**
 * 本模组的基础 meta 物品（order / AE 多方块构建器）。
 * <p>
 * 注册名在构造期绑定，子项清单只写在这里；{@link ApplyGrayMetaItems} 持有字段并负责注册。
 * <p>
 * 模型不写路径：沿用 GT 的 {@code metaitems/<unlocalizedName>} 规则，模型文件放在
 * {@code assets/gregtech/models/item/metaitems/} 下。
 */
public class ApplyGrayMetaItem extends StandardMetaItem {

    public ApplyGrayMetaItem() {
        setRegistryName(ApplyGrayAPI.id("meta_item"));
    }

    @Override
    public void registerSubItems() {
        ApplyGrayMetaItems.ORDER = addItem(0, "order")
                .addComponents(new OrderBehavior());

        ApplyGrayMetaItems.AE_BUILDER = addItem(ApplyGrayMetaItems.AE_BUILDER_META, "ae_multiblock_builder")
                .addComponents(new AEMultiblockBuilderBehavior())
                .setMaxStackSize(1)
                .setCreativeTabs(GTCreativeTabs.TAB_GREGTECH_TOOLS);
    }
}
