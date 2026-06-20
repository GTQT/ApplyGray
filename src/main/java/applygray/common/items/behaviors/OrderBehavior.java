package applygray.common.items.behaviors;

import gregtech.api.items.gui.ItemUIFactory;
import gregtech.api.items.metaitem.stats.IItemBehaviour;
import gregtech.api.mui.GTGuiTextures;
import gregtech.api.mui.GTGuis;
import gregtech.api.mui.factory.MetaItemGuiFactory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.HandGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import java.util.List;

public class OrderBehavior implements IItemBehaviour, ItemUIFactory {

    private static final String KEY_ORDER_NAME = "order_name";
    private static final String DEFAULT_NAME = "订单";

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        if (!world.isRemote) MetaItemGuiFactory.open(player, hand);
        return ActionResult.newResult(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }

    @Override
    public ModularPanel buildUI(HandGuiData data, PanelSyncManager syncManager, UISettings settings) {
        ItemStack stack = data.getUsedItemStack();
        StringSyncValue name = new StringSyncValue(() -> getName(stack), value -> setName(stack, value));
        return GTGuis.createPanel(stack, 80, 60)
                .child(IKey.str("设置订单名称").asWidget().pos(5, 5))
                .child(new TextFieldWidget().widthRel(0.8f).height(20).pos(5, 20)
                        .setTextColor(Color.WHITE.darker(1)).setValidator(this::normalize).value(name)
                        .background(GTGuiTextures.DISPLAY));
    }

    private String normalize(String value) {
        return value == null || value.isEmpty() ? DEFAULT_NAME : value;
    }

    private String getName(ItemStack stack) {
        return stack.hasTagCompound() ? normalize(stack.getTagCompound().getString(KEY_ORDER_NAME)) : DEFAULT_NAME;
    }

    private void setName(ItemStack stack, String value) {
        String name = normalize(value);
        NBTTagCompound compound = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        compound.setString(KEY_ORDER_NAME, name);
        NBTTagCompound display = compound.getCompoundTag("display");
        display.setString("Name", name + "订单");
        compound.setTag("display", display);
        stack.setTagCompound(compound);
    }

    @Override
    public void addInformation(ItemStack stack, List<String> lines) {
        lines.add(TextFormatting.AQUA + "订单名称：" + TextFormatting.RESET + getName(stack));
        lines.add(TextFormatting.DARK_GRAY + "右键打开界面修改名称");
        lines.add(TextFormatting.DARK_GRAY + "可以作为AE自动合成的大型机器产物");
        lines.add(TextFormatting.DARK_GRAY + "当此合成完成时，会自动取消，无需手动取消");
    }
}
