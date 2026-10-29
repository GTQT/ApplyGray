package applygray.common.items.eco;

import applygray.api.ApplyGrayAPI;
import applygray.common.metatileentities.multi.electric.eco.EcoTiers;

import gregtech.api.items.metaitem.MetaItem;
import gregtech.api.items.metaitem.StandardMetaItem;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Eco 内存芯片：一个等级一个独立物品，与 {@link EcoCellItem} 同构。
 * <p>
 * 等级存在字段上而不是 meta 值上：{@link #tierOf(ItemStack)} 只需要一次 instanceof，
 * 不再依赖 {@code MetaItem#getItem} 反查子项（查不到会返回 null，漏判就把等级算错）。
 */
public final class EcoMemoryItem extends StandardMetaItem {

    private final int tier;

    /** 子项句柄，供配方与 JEI 直接引用，避免运行时按名字反查。 */
    public MetaItem<?>.MetaValueItem stack;

    public EcoMemoryItem(int tier) {
        this.tier = tier;
        // 注册名带等级；模型按 unlocalizedName 取（metaitems/eco.memory_cell.<tier>），两者都由 tier 推出
        setRegistryName(ApplyGrayAPI.id("eco_memory_cell_" + tier));
    }

    public int ecoTier() { return tier; }

    @Override
    public void registerSubItems() {
        stack = addItem(0, "eco.memory_cell." + tier).setMaxStackSize(1);
    }

    /** 内存芯片等级；不是内存芯片（含空栈）时返回 0。 */
    public static int tierOf(ItemStack stack) {
        return stack.getItem() instanceof EcoMemoryItem memory ? memory.ecoTier() : 0;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        super.addInformation(stack, world, tooltip, flag);
        tooltip.add(I18n.format("applygray.eco.tooltip.memory", EcoTiers.memory(tier)));
    }
}
