package applygray.common.items;

import applygray.common.items.eco.EcoCellItem;
import applygray.common.items.eco.EcoMemoryItem;

import gregtech.api.items.metaitem.MetaItem;
import gregtech.api.items.metaitem.StandardMetaItem;

import net.minecraft.item.Item;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * ApplyGray meta 物品持有类。
 * <p>
 * 这里只放字段与注册入口；{@code addItem} 清单写在各自的 MetaItem 子类
 * （{@link ApplyGrayMetaItem}、{@link EcoMemoryItem}、{@link EcoCellItem}）里。每个物品一个字段、一行注册，
 * 禁止用数组/集合/循环批量生成：注册名、meta ID 与字段顺序都要能被单独审阅，发布后不得挪动。
 * <p>
 * 模型不写路径：GT 的 {@code MetaItem} 默认按 {@code metaitems/<unlocalizedName>} 取模型，对应文件放在
 * {@code assets/gregtech/models/item/metaitems/} 下（与 GTQT 一致）。
 */
public final class ApplyGrayMetaItems {

    /** AE 多方块构建器的 meta 值；字段注释里引用它，避免同一个数字写两遍。 */
    public static final int AE_BUILDER_META = 1;

    public static ApplyGrayMetaItem META_ITEM;
    public static MetaItem<?>.MetaValueItem ORDER; // meta 0
    public static MetaItem<?>.MetaValueItem AE_BUILDER; // meta AE_BUILDER_META

    // Eco 内存芯片：每个等级一个独立 MetaItem，等级存在物品上
    public static EcoMemoryItem ECO_MEMORY_T1;
    public static EcoMemoryItem ECO_MEMORY_T2;
    public static EcoMemoryItem ECO_MEMORY_T3;
    public static EcoMemoryItem ECO_MEMORY_T4;
    public static EcoMemoryItem ECO_MEMORY_T5;

    // Eco 存储元件：每个 tier、每种类型各注册一个独立 MetaItem
    public static EcoCellItem ECO_ITEM_CELL_1;
    public static EcoCellItem ECO_ITEM_CELL_2;
    public static EcoCellItem ECO_ITEM_CELL_3;
    public static EcoCellItem ECO_ITEM_CELL_4;
    public static EcoCellItem ECO_ITEM_CELL_5;

    public static EcoCellItem ECO_FLUID_CELL_1;
    public static EcoCellItem ECO_FLUID_CELL_2;
    public static EcoCellItem ECO_FLUID_CELL_3;
    public static EcoCellItem ECO_FLUID_CELL_4;
    public static EcoCellItem ECO_FLUID_CELL_5;

    private ApplyGrayMetaItems() {}

    /** 物品注册入口：一个物品一行，构造 → 交给 Forge → 展开子项。 */
    public static void register(IForgeRegistry<Item> registry) {
        META_ITEM = register(registry, new ApplyGrayMetaItem());

        ECO_MEMORY_T1 = register(registry, new EcoMemoryItem(1));
        ECO_MEMORY_T2 = register(registry, new EcoMemoryItem(2));
        ECO_MEMORY_T3 = register(registry, new EcoMemoryItem(3));
        ECO_MEMORY_T4 = register(registry, new EcoMemoryItem(4));
        ECO_MEMORY_T5 = register(registry, new EcoMemoryItem(5));

        ECO_ITEM_CELL_1 = register(registry, new EcoCellItem(1, false));
        ECO_ITEM_CELL_2 = register(registry, new EcoCellItem(2, false));
        ECO_ITEM_CELL_3 = register(registry, new EcoCellItem(3, false));
        ECO_ITEM_CELL_4 = register(registry, new EcoCellItem(4, false));
        ECO_ITEM_CELL_5 = register(registry, new EcoCellItem(5, false));

        ECO_FLUID_CELL_1 = register(registry, new EcoCellItem(1, true));
        ECO_FLUID_CELL_2 = register(registry, new EcoCellItem(2, true));
        ECO_FLUID_CELL_3 = register(registry, new EcoCellItem(3, true));
        ECO_FLUID_CELL_4 = register(registry, new EcoCellItem(4, true));
        ECO_FLUID_CELL_5 = register(registry, new EcoCellItem(5, true));
    }

    /** 注册名已在构造器绑定；顺序固定为先进注册表、再展开该物品的 meta 子项。 */
    private static <T extends StandardMetaItem> T register(IForgeRegistry<Item> registry, T item) {
        registry.register(item);
        item.registerSubItems();
        return item;
    }
}
