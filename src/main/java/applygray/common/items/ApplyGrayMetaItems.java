package applygray.common.items;

import applygray.common.eco.EcoCellItem;
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
    public static final int ECO_MEMORY_META = 100;
    public static final MetaItem<?>.MetaValueItem[] ECO_MEMORY = new MetaItem<?>.MetaValueItem[EcoTiers.COUNT];

    private ApplyGrayMetaItems() {}

    public static void init(IForgeRegistry<Item> registry) {
        META_ITEM = new ApplyGrayMetaItem();
        META_ITEM.setRegistryName(ApplyGrayAPI.MODID, "meta_item");
        registry.register(META_ITEM);
        META_ITEM.registerSubItems();
        for (int tier = 1; tier <= EcoTiers.COUNT; tier++) {
            for (boolean fluid : new boolean[]{false, true}) {
                EcoCellItem item = new EcoCellItem(tier, fluid);
                item.setRegistryName(ApplyGrayAPI.MODID, "eco_" + (fluid ? "fluid" : "item") + "_cell_" + tier);
                registry.register(item);
                item.registerSubItems();
            }
        }
    }

    public static int memoryTier(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        for (int i = 0; i < ECO_MEMORY.length; i++) {
            if (ECO_MEMORY[i] != null && ECO_MEMORY[i].isItemEqual(stack)) return i + 1;
        }
        return 0;
    }

    public static final class ApplyGrayMetaItem extends StandardMetaItem {

        @Override
        public net.minecraft.util.ResourceLocation createItemModelPath(MetaItem<?>.MetaValueItem item, String postfix) {
            for (int tier = 1; tier <= EcoTiers.COUNT; tier++) {
                if (ECO_MEMORY[tier - 1] == item)
                    return ApplyGrayAPI.id("metaitems/eco_memory_cell_" + tier + postfix);
            }
            return super.createItemModelPath(item, postfix);
        }

        @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
        @Override
        public void addInformation(ItemStack stack, World world,
                                   List<String> tooltip, ITooltipFlag flag) {
            super.addInformation(stack, world, tooltip, flag);
            int tier = memoryTier(stack);
            if (tier > 0) tooltip.add(I18n.format("applygray.eco.tooltip.memory",
                    EcoTiers.memory(tier)));
        }

        @Override
        public void registerSubItems() {
            for (int i = 0; i < EcoTiers.COUNT; i++) {
                ECO_MEMORY[i] = addItem(ECO_MEMORY_META + i, "eco.memory_cell." + (i + 1)).setMaxStackSize(1);
            }
            ORDER = addItem(0, "order").addComponents(new OrderBehavior());
            AE_BUILDER = addItem(AE_BUILDER_META, "ae_multiblock_builder")
                    .addComponents(new AEMultiblockBuilderBehavior())
                    .setMaxStackSize(1)
                    .setCreativeTabs(GTCreativeTabs.TAB_GREGTECH_TOOLS);
        }
    }
}
