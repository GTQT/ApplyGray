package applygray.common.eco;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.ApplyGrayMetaItems;
import gregtech.api.items.metaitem.MetaItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.registries.IForgeRegistry;

public final class EcoItems {
    public static final int MEMORY_META = 100;
    public static final MetaItem<?>.MetaValueItem[] MEMORY = new MetaItem<?>.MetaValueItem[EcoTiers.COUNT];
    private EcoItems() {}

    public static void registerMemory(ApplyGrayMetaItems.ApplyGrayMetaItem item) {
        for (int i = 0; i < EcoTiers.COUNT; i++) {
            MEMORY[i] = item.addItem(MEMORY_META + i, "eco.memory_cell." + (i + 1)).setMaxStackSize(1);
        }
    }

    public static void registerCells(IForgeRegistry<Item> registry) {
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
        if (stack.isEmpty() || stack.getItem() != ApplyGrayMetaItems.META_ITEM) return 0;
        int tier = stack.getMetadata() - MEMORY_META + 1;
        return tier >= 1 && tier <= EcoTiers.COUNT ? tier : 0;
    }
}
