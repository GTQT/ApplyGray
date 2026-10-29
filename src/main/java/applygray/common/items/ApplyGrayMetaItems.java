package applygray.common.items;

import applygray.common.eco.EcoItems;
import applygray.common.eco.EcoTiers;
import java.util.List;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import applygray.api.ApplyGrayAPI;
import applygray.common.items.behaviors.AEMultiblockBuilderBehavior;
import applygray.common.items.behaviors.OrderBehavior;

import gregtech.api.items.metaitem.MetaItem;
import gregtech.api.items.metaitem.StandardMetaItem;
import gregtech.common.creativetab.GTCreativeTabs;

import net.minecraft.item.Item;
import net.minecraftforge.registries.IForgeRegistry;

public final class ApplyGrayMetaItems {

    /** AE 多方块构建器的 meta 值，客户端注册模型时也要用。 */
    public static final int AE_BUILDER_META = 1;

    public static ApplyGrayMetaItem META_ITEM;
    public static MetaItem<?>.MetaValueItem ORDER;
    public static MetaItem<?>.MetaValueItem AE_BUILDER;

    private ApplyGrayMetaItems() {}

    public static void init(IForgeRegistry<Item> registry) {
        META_ITEM = new ApplyGrayMetaItem();
        META_ITEM.setRegistryName(ApplyGrayAPI.MODID, "meta_item");
        registry.register(META_ITEM);
        META_ITEM.registerSubItems();
        EcoItems.registerCells(registry);
    }

    public static final class ApplyGrayMetaItem extends StandardMetaItem {

        @Override
        public net.minecraft.util.ResourceLocation createItemModelPath(MetaItem<?>.MetaValueItem item, String postfix) {
            for (int tier = 1; tier <= EcoTiers.COUNT; tier++) {
                if (EcoItems.MEMORY[tier - 1] == item)
                    return ApplyGrayAPI.id("metaitems/eco_memory_cell_" + tier + postfix);
            }
            return super.createItemModelPath(item, postfix);
        }

        @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
        @Override
        public void addInformation(ItemStack stack, World world,
                                   List<String> tooltip, ITooltipFlag flag) {
            super.addInformation(stack, world, tooltip, flag);
            int tier = EcoItems.memoryTier(stack);
            if (tier > 0) tooltip.add(I18n.format("applygray.eco.tooltip.memory",
                    EcoTiers.memory(tier)));
        }

        @Override
        public void registerSubItems() {
            EcoItems.registerMemory(this);
            ORDER = addItem(0, "order").addComponents(new OrderBehavior());
            AE_BUILDER = addItem(AE_BUILDER_META, "ae_multiblock_builder")
                    .addComponents(new AEMultiblockBuilderBehavior())
                    .setMaxStackSize(1)
                    .setCreativeTabs(GTCreativeTabs.TAB_GREGTECH_TOOLS);
        }
    }
}
