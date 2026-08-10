package applygray.client.mattermanipulator;

import java.io.IOException;
import java.util.List;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.WeightedBlockList;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.network.ConfigureManipulatorMessage;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.planning.VoxelRole;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/** Inventory-backed, server-confirmed configuration screen for Matter Manipulator operations. */
public final class MatterManipulatorConfigScreen extends GuiScreen {

    private static final int MODE_BUTTON = 0;
    private static final int SHAPE_BUTTON = 1;
    private static final int REMOVAL_BUTTON = 2;
    private static final int ROTATION_BUTTON = 3;
    private static final int MIRROR_BUTTON = 4;
    private static final int REPEAT_X_BUTTON = 5;
    private static final int REPEAT_Y_BUTTON = 6;
    private static final int REPEAT_Z_BUTTON = 7;
    private static final int ROLE_CORNER_BUTTON = 8;
    private static final int ROLE_EDGE_BUTTON = 9;
    private static final int ROLE_FACE_BUTTON = 10;
    private static final int ROLE_VOLUME_BUTTON = 11;
    private static final int CLEAR_ROLE_BUTTON = 12;
    private static final int SET_EXCHANGE_WHITELIST_BUTTON = 13;
    private static final int ADD_EXCHANGE_WHITELIST_BUTTON = 14;
    private static final int CLEAR_EXCHANGE_WHITELIST_BUTTON = 15;
    private static final int SET_EXCHANGE_REPLACEMENT_BUTTON = 16;
    private static final int ADD_EXCHANGE_REPLACEMENT_BUTTON = 17;
    private static final int CLEAR_EXCHANGE_REPLACEMENT_BUTTON = 18;
    private static final int SET_CABLE_MATERIAL_BUTTON = 19;
    private static final int CLEAR_CABLE_MATERIAL_BUTTON = 20;
    private static final int REQUEST_UPLINK_MISSING_BUTTON = 21;
    private static final int REQUEST_UPLINK_ALL_BUTTON = 22;
    private static final int CANCEL_UPLINK_CRAFTING_BUTTON = 23;
    private static final int SMART_COPY_BUTTON = 24;

    private static final int INVENTORY_COLUMNS = 9;
    private static final int INVENTORY_ROWS = 4;
    private static final int SLOT_SIZE = 20;

    private final EnumHand hand;
    private VoxelRole selectedRole = VoxelRole.VOLUME;
    private MaterialInput selectedInput = MaterialInput.EXCHANGE_WHITELIST_SET;
    private int left;
    private int top;

    public MatterManipulatorConfigScreen(EnumHand hand) {
        this.hand = hand;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        left = width / 2 - 110;
        top = Math.max(20, (height - 372) / 2);
        buttonList.add(new GuiButton(MODE_BUTTON, left, top, 220, 20, ""));
        buttonList.add(new GuiButton(SHAPE_BUTTON, left, top + 24, 108, 20, ""));
        buttonList.add(new GuiButton(REMOVAL_BUTTON, left + 112, top + 24, 108, 20, ""));
        buttonList.add(new GuiButton(ROTATION_BUTTON, left, top + 48, 108, 20, ""));
        buttonList.add(new GuiButton(MIRROR_BUTTON, left + 112, top + 48, 108, 20, ""));
        buttonList.add(new GuiButton(REPEAT_X_BUTTON, left, top + 72, 70, 20, ""));
        buttonList.add(new GuiButton(REPEAT_Y_BUTTON, left + 75, top + 72, 70, 20, ""));
        buttonList.add(new GuiButton(REPEAT_Z_BUTTON, left + 150, top + 72, 70, 20, ""));
        buttonList.add(new GuiButton(SMART_COPY_BUTTON, left, top + 96, 220, 20, ""));

        buttonList.add(new GuiButton(ROLE_CORNER_BUTTON, left, top + 100, 108, 20, ""));
        buttonList.add(new GuiButton(ROLE_EDGE_BUTTON, left + 112, top + 100, 108, 20, ""));
        buttonList.add(new GuiButton(ROLE_FACE_BUTTON, left, top + 124, 108, 20, ""));
        buttonList.add(new GuiButton(ROLE_VOLUME_BUTTON, left + 112, top + 124, 108, 20, ""));
        buttonList.add(new GuiButton(CLEAR_ROLE_BUTTON, left, top + 148, 220, 20, ""));

        buttonList.add(new GuiButton(SET_EXCHANGE_WHITELIST_BUTTON, left, top + 100, 108, 20, ""));
        buttonList.add(new GuiButton(ADD_EXCHANGE_WHITELIST_BUTTON, left + 112, top + 100, 108, 20, ""));
        buttonList.add(new GuiButton(CLEAR_EXCHANGE_WHITELIST_BUTTON, left, top + 124, 220, 20, ""));
        buttonList.add(new GuiButton(SET_EXCHANGE_REPLACEMENT_BUTTON, left, top + 148, 108, 20, ""));
        buttonList.add(new GuiButton(ADD_EXCHANGE_REPLACEMENT_BUTTON, left + 112, top + 148, 108, 20, ""));
        buttonList.add(new GuiButton(CLEAR_EXCHANGE_REPLACEMENT_BUTTON, left, top + 172, 220, 20, ""));

        buttonList.add(new GuiButton(SET_CABLE_MATERIAL_BUTTON, left, top + 100, 108, 20, ""));
        buttonList.add(new GuiButton(CLEAR_CABLE_MATERIAL_BUTTON, left + 112, top + 100, 108, 20, ""));
        buttonList.add(new GuiButton(REQUEST_UPLINK_MISSING_BUTTON, left, top + 176, 108, 20, ""));
        buttonList.add(new GuiButton(REQUEST_UPLINK_ALL_BUTTON, left + 112, top + 176, 108, 20, ""));
        buttonList.add(new GuiButton(CANCEL_UPLINK_CRAFTING_BUTTON, left, top + 200, 220, 20, ""));
        refreshButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case MODE_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_PLACE_MODE);
            case SHAPE_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_SHAPE);
            case REMOVAL_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_REMOVAL_MODE);
            case ROTATION_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_COPY_ROTATION);
            case MIRROR_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_COPY_MIRROR);
            case REPEAT_X_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_COPY_REPEAT_X);
            case REPEAT_Y_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_COPY_REPEAT_Y);
            case REPEAT_Z_BUTTON -> send(ConfigureManipulatorMessage.Action.NEXT_COPY_REPEAT_Z);
            case SMART_COPY_BUTTON -> send(ConfigureManipulatorMessage.Action.TOGGLE_SMART_COPY);
            case ROLE_CORNER_BUTTON -> selectedRole = VoxelRole.CORNER;
            case ROLE_EDGE_BUTTON -> selectedRole = VoxelRole.EDGE;
            case ROLE_FACE_BUTTON -> selectedRole = VoxelRole.FACE;
            case ROLE_VOLUME_BUTTON -> selectedRole = VoxelRole.VOLUME;
            case CLEAR_ROLE_BUTTON -> send(ConfigureManipulatorMessage.Action.CLEAR_ROLE_MATERIAL, selectedRole);
            case SET_EXCHANGE_WHITELIST_BUTTON -> selectedInput = MaterialInput.EXCHANGE_WHITELIST_SET;
            case ADD_EXCHANGE_WHITELIST_BUTTON -> selectedInput = MaterialInput.EXCHANGE_WHITELIST_ADD;
            case CLEAR_EXCHANGE_WHITELIST_BUTTON -> send(ConfigureManipulatorMessage.Action.CLEAR_EXCHANGE_WHITELIST);
            case SET_EXCHANGE_REPLACEMENT_BUTTON -> selectedInput = MaterialInput.EXCHANGE_REPLACEMENT_SET;
            case ADD_EXCHANGE_REPLACEMENT_BUTTON -> selectedInput = MaterialInput.EXCHANGE_REPLACEMENT_ADD;
            case CLEAR_EXCHANGE_REPLACEMENT_BUTTON -> send(ConfigureManipulatorMessage.Action.CLEAR_EXCHANGE_REPLACEMENT);
            case SET_CABLE_MATERIAL_BUTTON -> selectedInput = MaterialInput.CABLE;
            case CLEAR_CABLE_MATERIAL_BUTTON -> send(ConfigureManipulatorMessage.Action.CLEAR_CABLE_MATERIAL);
            case REQUEST_UPLINK_MISSING_BUTTON -> send(ConfigureManipulatorMessage.Action.REQUEST_UPLINK_MISSING);
            case REQUEST_UPLINK_ALL_BUTTON -> send(ConfigureManipulatorMessage.Action.REQUEST_UPLINK_ALL);
            case CANCEL_UPLINK_CRAFTING_BUTTON -> send(ConfigureManipulatorMessage.Action.CANCEL_UPLINK_CRAFTING);
            default -> {}
        }
        refreshButtons();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int slot = slotAt(mouseX, mouseY);
        ManipulatorState state = state();
        if (slot >= 0 && mouseButton == 0 && state != null) {
            MaterialInput input = activeInput(state);
            if (input != null) {
                MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, input.action,
                        input == MaterialInput.GEOMETRY_ROLE ? selectedRole : null, slot));
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        refreshButtons();
        drawCenteredString(fontRenderer, I18n.format("applygray.matter_manipulator.config.title"), width / 2, top - 18,
                0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawInventory(mouseX, mouseY);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void refreshButtons() {
        ManipulatorState state = state();
        if (state == null) {
            for (GuiButton button : buttonList) {
                button.visible = false;
            }
            return;
        }

        ManipulatorPlaceMode mode = state.placeMode();
        boolean geometry = mode == ManipulatorPlaceMode.GEOMETRY;
        boolean copying = mode == ManipulatorPlaceMode.COPYING;
        boolean exchanging = mode == ManipulatorPlaceMode.EXCHANGING;
        boolean cables = mode == ManipulatorPlaceMode.CABLES;
        boolean smartCopy = copying && supportsSmartCopy();
        boolean boundUplink = hasBoundUplink();

        setVisible(MODE_BUTTON, true);
        setVisible(SHAPE_BUTTON, geometry);
        setVisible(REMOVAL_BUTTON, true);
        setVisible(ROTATION_BUTTON, copying);
        setVisible(MIRROR_BUTTON, copying);
        setVisible(REPEAT_X_BUTTON, copying);
        setVisible(REPEAT_Y_BUTTON, copying);
        setVisible(REPEAT_Z_BUTTON, copying);
        setVisible(SMART_COPY_BUTTON, smartCopy);
        setVisible(ROLE_CORNER_BUTTON, geometry);
        setVisible(ROLE_EDGE_BUTTON, geometry);
        setVisible(ROLE_FACE_BUTTON, geometry);
        setVisible(ROLE_VOLUME_BUTTON, geometry);
        setVisible(CLEAR_ROLE_BUTTON, geometry);
        setVisible(SET_EXCHANGE_WHITELIST_BUTTON, exchanging);
        setVisible(ADD_EXCHANGE_WHITELIST_BUTTON, exchanging);
        setVisible(CLEAR_EXCHANGE_WHITELIST_BUTTON, exchanging);
        setVisible(SET_EXCHANGE_REPLACEMENT_BUTTON, exchanging);
        setVisible(ADD_EXCHANGE_REPLACEMENT_BUTTON, exchanging);
        setVisible(CLEAR_EXCHANGE_REPLACEMENT_BUTTON, exchanging);
        setVisible(SET_CABLE_MATERIAL_BUTTON, cables);
        setVisible(CLEAR_CABLE_MATERIAL_BUTTON, cables);
        positionUplinkButtons(state);
        setVisible(REQUEST_UPLINK_MISSING_BUTTON, boundUplink);
        setVisible(REQUEST_UPLINK_ALL_BUTTON, boundUplink);
        setVisible(CANCEL_UPLINK_CRAFTING_BUTTON, boundUplink);

        setLabel(MODE_BUTTON, I18n.format("applygray.matter_manipulator.config.mode",
                I18n.format("applygray.matter_manipulator.mode." + mode.name().toLowerCase())));
        setLabel(SHAPE_BUTTON, I18n.format("applygray.matter_manipulator.config.shape", shapeName(state)));
        setLabel(REMOVAL_BUTTON, I18n.format("applygray.matter_manipulator.config.removal", removalName(state)));
        setLabel(ROTATION_BUTTON, I18n.format("applygray.matter_manipulator.config.rotation",
                I18n.format("applygray.matter_manipulator.rotation." + state.copyRotation().name().toLowerCase())));
        setLabel(MIRROR_BUTTON, I18n.format("applygray.matter_manipulator.config.mirror",
                I18n.format("applygray.matter_manipulator.mirror." + state.copyMirror().name().toLowerCase())));
        setLabel(REPEAT_X_BUTTON, I18n.format("applygray.matter_manipulator.config.repeat.x", state.copyRepeatX()));
        setLabel(REPEAT_Y_BUTTON, I18n.format("applygray.matter_manipulator.config.repeat.y", state.copyRepeatY()));
        setLabel(REPEAT_Z_BUTTON, I18n.format("applygray.matter_manipulator.config.repeat.z", state.copyRepeatZ()));
        setLabel(SMART_COPY_BUTTON, I18n.format("applygray.matter_manipulator.config.smart_copy",
                I18n.format("applygray.matter_manipulator.config." + (state.smartCopy() ? "on" : "off"))));

        setRoleLabel(ROLE_CORNER_BUTTON, state, VoxelRole.CORNER);
        setRoleLabel(ROLE_EDGE_BUTTON, state, VoxelRole.EDGE);
        setRoleLabel(ROLE_FACE_BUTTON, state, VoxelRole.FACE);
        setRoleLabel(ROLE_VOLUME_BUTTON, state, VoxelRole.VOLUME);
        setLabel(CLEAR_ROLE_BUTTON, I18n.format("applygray.matter_manipulator.config.clear", roleName(selectedRole)));

        setMaterialChoiceLabel(SET_EXCHANGE_WHITELIST_BUTTON,
                "applygray.matter_manipulator.config.exchange.whitelist.set", state.exchangeWhitelist(),
                selectedInput == MaterialInput.EXCHANGE_WHITELIST_SET);
        setMaterialChoiceLabel(ADD_EXCHANGE_WHITELIST_BUTTON,
                "applygray.matter_manipulator.config.exchange.whitelist.add", state.exchangeWhitelist(),
                selectedInput == MaterialInput.EXCHANGE_WHITELIST_ADD);
        setLabel(CLEAR_EXCHANGE_WHITELIST_BUTTON,
                I18n.format("applygray.matter_manipulator.config.exchange.whitelist.clear"));
        setMaterialChoiceLabel(SET_EXCHANGE_REPLACEMENT_BUTTON,
                "applygray.matter_manipulator.config.exchange.replacement.set", state.exchangeReplacement(),
                selectedInput == MaterialInput.EXCHANGE_REPLACEMENT_SET);
        setMaterialChoiceLabel(ADD_EXCHANGE_REPLACEMENT_BUTTON,
                "applygray.matter_manipulator.config.exchange.replacement.add", state.exchangeReplacement(),
                selectedInput == MaterialInput.EXCHANGE_REPLACEMENT_ADD);
        setLabel(CLEAR_EXCHANGE_REPLACEMENT_BUTTON,
                I18n.format("applygray.matter_manipulator.config.exchange.replacement.clear"));
        setMaterialChoiceLabel(SET_CABLE_MATERIAL_BUTTON, "applygray.matter_manipulator.config.cable.set",
                state.cableMaterial(), selectedInput == MaterialInput.CABLE);
        setLabel(CLEAR_CABLE_MATERIAL_BUTTON, I18n.format("applygray.matter_manipulator.config.cable.clear"));
        setLabel(REQUEST_UPLINK_MISSING_BUTTON,
                I18n.format("applygray.matter_manipulator.config.uplink.request_missing"));
        setLabel(REQUEST_UPLINK_ALL_BUTTON, I18n.format("applygray.matter_manipulator.config.uplink.request_all"));
        setLabel(CANCEL_UPLINK_CRAFTING_BUTTON, I18n.format("applygray.matter_manipulator.config.uplink.cancel"));
    }

    private void setRoleLabel(int buttonId, ManipulatorState state, VoxelRole role) {
        GuiButton button = button(buttonId);
        if (button == null) return;
        button.displayString = trim(roleName(role) + ": " + materialName(listFor(state, role)), 100);
        button.packedFGColour = role == selectedRole ? 0xFFFF55 : 0xE0E0E0;
    }

    private void setMaterialChoiceLabel(int buttonId, String translationKey, WeightedBlockList list, boolean selected) {
        setChoiceLabel(buttonId, I18n.format(translationKey, materialName(list)), selected);
    }

    private void setMaterialChoiceLabel(int buttonId, String translationKey, BlockSpec specification, boolean selected) {
        setChoiceLabel(buttonId, I18n.format(translationKey, materialName(specification)), selected);
    }

    private void setChoiceLabel(int buttonId, String label, boolean selected) {
        GuiButton button = button(buttonId);
        if (button == null) return;
        button.displayString = trim(label, button.width - 8);
        button.packedFGColour = selected ? 0xFFFF55 : 0xE0E0E0;
    }

    private void setLabel(int buttonId, String label) {
        GuiButton button = button(buttonId);
        if (button != null) button.displayString = trim(label, button.width - 8);
    }

    private void setVisible(int buttonId, boolean visible) {
        GuiButton button = button(buttonId);
        if (button != null) button.visible = visible;
    }

    private GuiButton button(int id) {
        for (GuiButton button : buttonList) {
            if (button.id == id) return button;
        }
        return null;
    }

    private void drawInventory(int mouseX, int mouseY) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return;

        int inventoryLeft = width / 2 - INVENTORY_COLUMNS * SLOT_SIZE / 2;
        int inventoryTop = inventoryTop();
        for (int slot = 0; slot < INVENTORY_COLUMNS * inventoryRows(); slot++) {
            int x = inventoryLeft + slot % INVENTORY_COLUMNS * SLOT_SIZE;
            int y = inventoryTop + slot / INVENTORY_COLUMNS * SLOT_SIZE;
            drawRect(x, y, x + 18, y + 18, 0xFF555555);
            drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF222222);
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            RenderHelper.enableGUIStandardItemLighting();
            Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, x + 1, y + 1);
            Minecraft.getMinecraft().getRenderItem().renderItemOverlays(fontRenderer, stack, x + 1, y + 1);
            RenderHelper.disableStandardItemLighting();
            if (slotAt(mouseX, mouseY) == slot) renderToolTip(stack, mouseX, mouseY);
        }
    }

    private int slotAt(int mouseX, int mouseY) {
        int inventoryLeft = width / 2 - INVENTORY_COLUMNS * SLOT_SIZE / 2;
        int inventoryTop = inventoryTop();
        if (mouseX < inventoryLeft || mouseY < inventoryTop ||
                mouseX >= inventoryLeft + INVENTORY_COLUMNS * SLOT_SIZE ||
                mouseY >= inventoryTop + inventoryRows() * SLOT_SIZE) {
            return -1;
        }
        int column = (mouseX - inventoryLeft) / SLOT_SIZE;
        int row = (mouseY - inventoryTop) / SLOT_SIZE;
        return column + row * INVENTORY_COLUMNS;
    }

    private int inventoryTop() {
        ManipulatorState state = state();
        if (state == null) return top + 176;
        int operationControlsBottom = switch (state.placeMode()) {
            case GEOMETRY -> top + 176;
            case EXCHANGING -> top + 200;
            case CABLES -> top + 128;
            case COPYING -> top + (supportsSmartCopy() ? 128 : 104);
            case MOVING -> top + 104;
        };
        return hasBoundUplink() ? operationControlsBottom + 48 : operationControlsBottom;
    }

    private int inventoryRows() {
        int available = (height - inventoryTop() - 2) / SLOT_SIZE;
        return Math.max(1, Math.min(INVENTORY_ROWS, available));
    }

    private void send(ConfigureManipulatorMessage.Action action) {
        send(action, null);
    }

    private void send(ConfigureManipulatorMessage.Action action, VoxelRole role) {
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, action, role, -1));
    }

    private ManipulatorState state() {
        ItemStack stack = Minecraft.getMinecraft().player == null ? ItemStack.EMPTY
                : Minecraft.getMinecraft().player.getHeldItem(hand);
        return stack.getItem() instanceof ItemMatterManipulator manipulator ? manipulator.state(stack) : null;
    }

    private boolean hasBoundUplink() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return false;
        ItemStack stack = player.getHeldItem(hand);
        return stack.getItem() instanceof ItemMatterManipulator manipulator &&
                manipulator.hasCapability(stack, applygray.mattermanipulator.state.ManipulatorCapability.UPLINK) &&
                manipulator.state(stack).uplinkAddress() != null;
    }

    private boolean supportsSmartCopy() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return false;
        ItemStack stack = player.getHeldItem(hand);
        return stack.getItem() instanceof ItemMatterManipulator manipulator &&
                manipulator.hasCapability(stack, applygray.mattermanipulator.state.ManipulatorCapability.SMART_COPY);
    }

    private void positionUplinkButtons(ManipulatorState state) {
        int top = switch (state.placeMode()) {
            case GEOMETRY -> this.top + 176;
            case EXCHANGING -> this.top + 200;
            case CABLES -> this.top + 128;
            case COPYING -> this.top + 128;
            case MOVING -> this.top + 104;
        };
        GuiButton missing = button(REQUEST_UPLINK_MISSING_BUTTON);
        GuiButton all = button(REQUEST_UPLINK_ALL_BUTTON);
        GuiButton cancel = button(CANCEL_UPLINK_CRAFTING_BUTTON);
        if (missing != null) missing.y = top;
        if (all != null) all.y = top;
        if (cancel != null) cancel.y = top + 24;
    }

    private MaterialInput activeInput(ManipulatorState state) {
        return switch (state.placeMode()) {
            case GEOMETRY -> MaterialInput.GEOMETRY_ROLE;
            case CABLES -> MaterialInput.CABLE;
            case EXCHANGING -> selectedInput.isExchange() ? selectedInput : MaterialInput.EXCHANGE_WHITELIST_SET;
            case COPYING, MOVING -> null;
        };
    }

    private String shapeName(ManipulatorState state) {
        return I18n.format("applygray.matter_manipulator.shape." + state.shape().name().toLowerCase());
    }

    private String removalName(ManipulatorState state) {
        return I18n.format("applygray.matter_manipulator.removal." + state.removalMode().name().toLowerCase());
    }

    private String roleName(VoxelRole role) {
        return I18n.format("applygray.matter_manipulator.role." + role.name().toLowerCase());
    }

    private String trim(String value, int maximumWidth) {
        return fontRenderer.trimStringToWidth(value, maximumWidth);
    }

    private static WeightedBlockList listFor(ManipulatorState state, VoxelRole role) {
        return switch (role) {
            case CORNER -> state.geometryConfiguration().corners();
            case EDGE -> state.geometryConfiguration().edges();
            case FACE -> state.geometryConfiguration().faces();
            case VOLUME -> state.geometryConfiguration().volumes();
        };
    }

    private static String materialName(WeightedBlockList list) {
        List<WeightedBlockList.Entry> entries = list.entries();
        return entries.isEmpty() ? I18n.format("applygray.matter_manipulator.config.empty")
                : materialName(entries.getFirst().spec());
    }

    private static String materialName(BlockSpec specification) {
        return specification.isAir() ? I18n.format("applygray.matter_manipulator.config.empty")
                : specification.toStack().getDisplayName();
    }

    private enum MaterialInput {
        GEOMETRY_ROLE(ConfigureManipulatorMessage.Action.SET_ROLE_MATERIAL),
        EXCHANGE_WHITELIST_SET(ConfigureManipulatorMessage.Action.SET_EXCHANGE_WHITELIST),
        EXCHANGE_WHITELIST_ADD(ConfigureManipulatorMessage.Action.ADD_EXCHANGE_WHITELIST),
        EXCHANGE_REPLACEMENT_SET(ConfigureManipulatorMessage.Action.SET_EXCHANGE_REPLACEMENT),
        EXCHANGE_REPLACEMENT_ADD(ConfigureManipulatorMessage.Action.ADD_EXCHANGE_REPLACEMENT),
        CABLE(ConfigureManipulatorMessage.Action.SET_CABLE_MATERIAL);

        private final ConfigureManipulatorMessage.Action action;

        MaterialInput(ConfigureManipulatorMessage.Action action) {
            this.action = action;
        }

        private boolean isExchange() {
            return this == EXCHANGE_WHITELIST_SET || this == EXCHANGE_WHITELIST_ADD ||
                    this == EXCHANGE_REPLACEMENT_SET || this == EXCHANGE_REPLACEMENT_ADD;
        }
    }
}
