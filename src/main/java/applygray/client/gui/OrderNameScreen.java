package applygray.client.gui;

import java.io.IOException;

import applygray.common.items.ApplyGrayOrderItem;
import applygray.common.network.PacketHandler;
import applygray.common.network.packet.SetOrderNameMessage;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import org.lwjgl.input.Keyboard;

/**
 * 订单改名界面。只用一个文本框加确认按钮，走原版 {@link GuiScreen}，不引入 ModularUI 的面板与同步机制。
 */
public final class OrderNameScreen extends GuiScreen {

    private static final int PANEL_WIDTH = 160;
    private static final int PANEL_HEIGHT = 92;
    private static final int SAVE_BUTTON_ID = 0;

    private final ItemStack stack;
    private final EnumHand hand;

    private GuiTextField nameField;

    public OrderNameScreen(ItemStack stack, EnumHand hand) {
        this.stack = stack;
        this.hand = hand;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - PANEL_HEIGHT / 2;
        nameField = new GuiTextField(0, fontRenderer, left + 8, top + 34, PANEL_WIDTH - 16, 18);
        nameField.setMaxStringLength(ApplyGrayOrderItem.MAX_ORDER_NAME_LENGTH);
        nameField.setText(ApplyGrayOrderItem.getOrderName(stack));
        nameField.setFocused(true);
        buttonList.add(new GuiButton(SAVE_BUTTON_ID, left + PANEL_WIDTH / 2 - 40, top + 62, 80, 20,
                I18n.format("applygray.item.order.save")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == SAVE_BUTTON_ID) {
            saveAndClose();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            saveAndClose();
            return;
        }
        nameField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int left = width / 2 - PANEL_WIDTH / 2;
        int top = height / 2 - PANEL_HEIGHT / 2;
        drawRect(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFFC6C6C6);
        drawRect(left + 1, top + 1, left + PANEL_WIDTH - 1, top + PANEL_HEIGHT - 1, 0xFFE8E8E8);
        drawCenteredString(fontRenderer, I18n.format("applygray.item.order.gui_title"), width / 2, top + 12, 0x404040);
        nameField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void saveAndClose() {
        // 先按同一规则写本地副本，服务端确认前 tooltip 就是新名字。
        ApplyGrayOrderItem.setOrderName(stack, nameField.getText());
        PacketHandler.CHANNEL.sendToServer(new SetOrderNameMessage(hand, nameField.getText()));
        mc.displayGuiScreen(null);
    }
}
