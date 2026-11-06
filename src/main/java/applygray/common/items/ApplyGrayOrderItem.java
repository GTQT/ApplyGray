package applygray.common.items;

import java.util.List;

import applygray.ApplyGrayMod;
import applygray.api.ApplyGrayAPI;

import gregtech.common.creativetab.GTCreativeTabs;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 合成订单：AE 自动合成的占位产物。
 * <p>
 * 订单名称存在物品 NBT 上，供样板匹配与 tooltip 显示；名称编辑界面由客户端代理打开，
 * 服务端只负责校验并落盘，因此这里不依赖 ModularUI 的物品界面工厂。
 */
public class ApplyGrayOrderItem extends Item {

    /** 订单名称的 NBT 键；改名时写入，样板 token 也复用它。 */
    public static final String NBT_ORDER_NAME = "order_name";

    /** 未命名订单的显示名，同时也是空名称的兜底值。 */
    public static final String DEFAULT_ORDER_NAME = "订单";

    public static final int MAX_ORDER_NAME_LENGTH = 32;

    public ApplyGrayOrderItem() {
        setRegistryName(ApplyGrayAPI.id("order"));
        setTranslationKey(ApplyGrayAPI.MODID + ".order");
        setMaxStackSize(1);
        setCreativeTab(GTCreativeTabs.TAB_GREGTECH);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote && !player.isSneaking()) {
            ApplyGrayMod.proxy.openOrderNameScreen(stack, hand);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(I18n.format("applygray.item.order.name_label", getOrderName(stack)));
        tooltip.add(TextFormatting.GRAY + I18n.format("applygray.item.order.tooltip.edit"));
        tooltip.add(TextFormatting.GRAY + I18n.format("applygray.item.order.tooltip.autocraft"));
        tooltip.add(TextFormatting.GRAY + I18n.format("applygray.item.order.tooltip.auto_cancel"));
    }

    public static String getOrderName(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return DEFAULT_ORDER_NAME;
        return normalize(stack.getTagCompound().getString(NBT_ORDER_NAME));
    }

    /**
     * 写入订单名称。显示名与名称一起写入 NBT，因此客户端预测修改后不必再等服务端回包。
     */
    public static void setOrderName(ItemStack stack, String name) {
        String normalized = normalize(name);
        NBTTagCompound compound = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        compound.setString(NBT_ORDER_NAME, normalized);
        NBTTagCompound display = compound.getCompoundTag("display");
        display.setString("Name", normalized + "订单");
        compound.setTag("display", display);
        stack.setTagCompound(compound);
    }

    public static String normalize(String name) {
        if (name == null) return DEFAULT_ORDER_NAME;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return DEFAULT_ORDER_NAME;
        return trimmed.length() > MAX_ORDER_NAME_LENGTH ? trimmed.substring(0, MAX_ORDER_NAME_LENGTH) : trimmed;
    }
}
