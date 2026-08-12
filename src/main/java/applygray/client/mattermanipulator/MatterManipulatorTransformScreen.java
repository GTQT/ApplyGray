package applygray.client.mattermanipulator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.network.ConfigureManipulatorMessage;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

/** Transparent full-screen transform editor using the source UI's dimensions and responsive columns. */
public final class MatterManipulatorTransformScreen extends GuiScreen {

    private static final int ROTATE_X_NEGATIVE = 0;
    private static final int ROTATE_X_POSITIVE = 1;
    private static final int ROTATE_Y_NEGATIVE = 2;
    private static final int ROTATE_Y_POSITIVE = 3;
    private static final int ROTATE_Z_NEGATIVE = 4;
    private static final int ROTATE_Z_POSITIVE = 5;
    private static final int FLIP_X = 6;
    private static final int FLIP_Y = 7;
    private static final int FLIP_Z = 8;
    private static final int RESET_TRANSFORM = 9;
    private static final int SWAP_REGION = 10;
    private static final int FIRST_ADJUST_BUTTON = 100;

    private static final int COLUMN_WIDTH = 130;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 2;
    private static final int GROUP_HEIGHT = 78;
    private static final int GROUP_GAP = 10;

    private final EnumHand hand;
    private final List<CoordinateField> fields = new ArrayList<>();
    private final List<AdjustButton> adjustButtons = new ArrayList<>();
    private final List<Header> headers = new ArrayList<>();
    private GuiTextField focusedField;
    private ManipulatorPlaceMode mode;

    public MatterManipulatorTransformScreen(EnumHand hand) {
        this.hand = hand;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        fields.clear();
        adjustButtons.clear();
        headers.clear();

        ManipulatorState state = state();
        mode = state == null ? ManipulatorPlaceMode.COPYING : state.placeMode();
        if (mode == ManipulatorPlaceMode.COPYING) addTransformControls();

        int right = width - COLUMN_WIDTH - 10;
        int lessRight = right - COLUMN_WIDTH - 10;
        boolean singleColumn = mc.gameSettings.guiScale <= 2 && height >= 5 * GROUP_HEIGHT + 4 * GROUP_GAP + 8;
        if (mode == ManipulatorPlaceMode.COPYING) {
            if (singleColumn) {
                int y = centeredY(5 * GROUP_HEIGHT + 4 * GROUP_GAP);
                y = addRegionGroup(right, y) + GROUP_GAP;
                y = addLocationGroup(right, y, "applygray.matter_manipulator.transform.source_a", state == null ? null
                        : state.selectionA(), ConfigureManipulatorMessage.Action.SET_SELECTION_A_X,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_A_Y,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_A_Z) + GROUP_GAP;
                y = addLocationGroup(right, y, "applygray.matter_manipulator.transform.source_b", state == null ? null
                        : state.selectionB(), ConfigureManipulatorMessage.Action.SET_SELECTION_B_X,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_B_Y,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_B_Z) + GROUP_GAP;
                y = addLocationGroup(right, y, "applygray.matter_manipulator.transform.destination", state == null ? null
                        : state.selectionC(), ConfigureManipulatorMessage.Action.SET_SELECTION_C_X,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_C_Y,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_C_Z) + GROUP_GAP;
                addRepeatGroup(right, y, state);
            } else {
                int rightY = centeredY(3 * GROUP_HEIGHT + 2 * GROUP_GAP);
                rightY = addLocationGroup(right, rightY, "applygray.matter_manipulator.transform.source_a",
                        state == null ? null : state.selectionA(),
                        ConfigureManipulatorMessage.Action.SET_SELECTION_A_X,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_A_Y,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_A_Z) + GROUP_GAP;
                rightY = addLocationGroup(right, rightY, "applygray.matter_manipulator.transform.source_b",
                        state == null ? null : state.selectionB(),
                        ConfigureManipulatorMessage.Action.SET_SELECTION_B_X,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_B_Y,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_B_Z) + GROUP_GAP;
                addLocationGroup(right, rightY, "applygray.matter_manipulator.transform.destination",
                        state == null ? null : state.selectionC(),
                        ConfigureManipulatorMessage.Action.SET_SELECTION_C_X,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_C_Y,
                        ConfigureManipulatorMessage.Action.SET_SELECTION_C_Z);

                int lessY = centeredY(2 * GROUP_HEIGHT + GROUP_GAP);
                lessY = addRegionGroup(lessRight, lessY) + GROUP_GAP;
                addRepeatGroup(lessRight, lessY, state);
            }
        } else {
            addMoveControls(state, right, lessRight, singleColumn);
        }
    }

    @Override
    public void onGuiClosed() {
        commitFocused();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (state() == null) return;
        switch (button.id) {
            case ROTATE_X_NEGATIVE -> send(ConfigureManipulatorMessage.Action.ROTATE_X_NEGATIVE);
            case ROTATE_X_POSITIVE -> send(ConfigureManipulatorMessage.Action.ROTATE_X_POSITIVE);
            case ROTATE_Y_NEGATIVE -> send(ConfigureManipulatorMessage.Action.ROTATE_Y_NEGATIVE);
            case ROTATE_Y_POSITIVE -> send(ConfigureManipulatorMessage.Action.ROTATE_Y_POSITIVE);
            case ROTATE_Z_NEGATIVE -> send(ConfigureManipulatorMessage.Action.ROTATE_Z_NEGATIVE);
            case ROTATE_Z_POSITIVE -> send(ConfigureManipulatorMessage.Action.ROTATE_Z_POSITIVE);
            case FLIP_X -> send(ConfigureManipulatorMessage.Action.FLIP_X);
            case FLIP_Y -> send(ConfigureManipulatorMessage.Action.FLIP_Y);
            case FLIP_Z -> send(ConfigureManipulatorMessage.Action.FLIP_Z);
            case RESET_TRANSFORM -> send(ConfigureManipulatorMessage.Action.RESET_COPY_TRANSFORM);
            case SWAP_REGION -> send(ConfigureManipulatorMessage.Action.SWAP_MOVE_REGION);
            default -> adjust(button);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        GuiTextField previous = focusedField;
        focusedField = null;
        for (CoordinateField field : fields) {
            field.widget.mouseClicked(mouseX, mouseY, mouseButton);
            if (field.widget.isFocused()) focusedField = field.widget;
        }
        if (previous != null && previous != focusedField) commit(previous);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (focusedField != null && focusedField.textboxKeyTyped(typedChar, keyCode)) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                commit(focusedField);
                focusedField.setFocused(false);
                focusedField = null;
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        for (CoordinateField field : fields) field.widget.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        for (Header header : headers) drawHeader(header);
        if (mode == ManipulatorPlaceMode.COPYING) drawTransformPanel();
        for (CoordinateField field : fields) field.widget.drawTextBox();
        for (AdjustButton adjust : adjustButtons) adjust.button.displayString = adjust.label(step(adjust));
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void addTransformControls() {
        int x = 10;
        int y = centeredY(178);
        addRotationRow(x, y, ROTATE_X_NEGATIVE, ROTATE_X_POSITIVE, "x");
        addRotationRow(x, y + 28, ROTATE_Y_NEGATIVE, ROTATE_Y_POSITIVE, "y");
        addRotationRow(x, y + 56, ROTATE_Z_NEGATIVE, ROTATE_Z_POSITIVE, "z");
        buttonList.add(new GuiButton(FLIP_X, x, y + 84, 40, ROW_HEIGHT,
                I18n.format("applygray.matter_manipulator.transform.flip_x")));
        buttonList.add(new GuiButton(FLIP_Y, x + 45, y + 84, 40, ROW_HEIGHT,
                I18n.format("applygray.matter_manipulator.transform.flip_y")));
        buttonList.add(new GuiButton(FLIP_Z, x + 90, y + 84, 40, ROW_HEIGHT,
                I18n.format("applygray.matter_manipulator.transform.flip_z")));
        buttonList.add(new GuiButton(RESET_TRANSFORM, x + 90, y + 150, 40, ROW_HEIGHT,
                I18n.format("applygray.matter_manipulator.transform.reset")));
    }

    private void addMoveControls(ManipulatorState state, int right, int lessRight, boolean singleColumn) {
        if (singleColumn) {
            int y = centeredY(4 * GROUP_HEIGHT + 3 * GROUP_GAP + 28);
            y = addLocationGroup(right, y, "applygray.matter_manipulator.transform.source_a",
                    state == null ? null : state.selectionA(), ConfigureManipulatorMessage.Action.SET_SELECTION_A_X,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_A_Y,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_A_Z) + GROUP_GAP;
            y = addLocationGroup(right, y, "applygray.matter_manipulator.transform.source_b",
                    state == null ? null : state.selectionB(), ConfigureManipulatorMessage.Action.SET_SELECTION_B_X,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_B_Y,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_B_Z) + GROUP_GAP;
            y = addRegionGroup(right, y) + GROUP_GAP;
            buttonList.add(new GuiButton(SWAP_REGION, right, y, COLUMN_WIDTH, ROW_HEIGHT,
                    I18n.format("applygray.matter_manipulator.transform.swap")));
            y += 28;
            addLocationGroup(right, y, "applygray.matter_manipulator.transform.destination",
                    state == null ? null : state.selectionC(), ConfigureManipulatorMessage.Action.SET_SELECTION_C_X,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_C_Y,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_C_Z);
        } else {
            int rightY = centeredY(2 * GROUP_HEIGHT + GROUP_GAP + 28);
            rightY = addRegionGroup(right, rightY) + GROUP_GAP;
            buttonList.add(new GuiButton(SWAP_REGION, right, rightY, COLUMN_WIDTH, ROW_HEIGHT,
                    I18n.format("applygray.matter_manipulator.transform.swap")));
            rightY += 28;
            addLocationGroup(right, rightY, "applygray.matter_manipulator.transform.destination",
                    state == null ? null : state.selectionC(), ConfigureManipulatorMessage.Action.SET_SELECTION_C_X,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_C_Y,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_C_Z);

            int lessY = centeredY(2 * GROUP_HEIGHT + GROUP_GAP);
            lessY = addLocationGroup(lessRight, lessY, "applygray.matter_manipulator.transform.source_a",
                    state == null ? null : state.selectionA(), ConfigureManipulatorMessage.Action.SET_SELECTION_A_X,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_A_Y,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_A_Z) + GROUP_GAP;
            addLocationGroup(lessRight, lessY, "applygray.matter_manipulator.transform.source_b",
                    state == null ? null : state.selectionB(), ConfigureManipulatorMessage.Action.SET_SELECTION_B_X,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_B_Y,
                    ConfigureManipulatorMessage.Action.SET_SELECTION_B_Z);
        }
    }

    private int addRegionGroup(int x, int y) {
        headers.add(new Header(x + 35, y, I18n.format("applygray.matter_manipulator.transform.region")));
        addRegionRow(x, y + 20, "X", ConfigureManipulatorMessage.Action.SHIFT_SELECTIONS_X);
        addRegionRow(x, y + 40, "Y", ConfigureManipulatorMessage.Action.SHIFT_SELECTIONS_Y);
        addRegionRow(x, y + 60, "Z", ConfigureManipulatorMessage.Action.SHIFT_SELECTIONS_Z);
        return y + GROUP_HEIGHT;
    }

    private int addLocationGroup(int x, int y, String titleKey, ManipulatorLocation location,
                                 ConfigureManipulatorMessage.Action actionX,
                                 ConfigureManipulatorMessage.Action actionY,
                                 ConfigureManipulatorMessage.Action actionZ) {
        headers.add(new Header(x + 35, y, I18n.format(titleKey)));
        BlockPos position = location == null ? BlockPos.ORIGIN : location.position();
        addEditorRow(x, y + 20, "X", position.getX(), actionX, false);
        addEditorRow(x, y + 40, "Y", position.getY(), actionY, false);
        addEditorRow(x, y + 60, "Z", position.getZ(), actionZ, false);
        return y + GROUP_HEIGHT;
    }

    private int addRepeatGroup(int x, int y, ManipulatorState state) {
        headers.add(new Header(x + 35, y, I18n.format("applygray.matter_manipulator.transform.stacking")));
        addEditorRow(x, y + 20, "X", state == null ? 1 : state.copyRepeatX(),
                ConfigureManipulatorMessage.Action.SET_COPY_REPEAT_X, true);
        addEditorRow(x, y + 40, "Y", state == null ? 1 : state.copyRepeatY(),
                ConfigureManipulatorMessage.Action.SET_COPY_REPEAT_Y, true);
        addEditorRow(x, y + 60, "Z", state == null ? 1 : state.copyRepeatZ(),
                ConfigureManipulatorMessage.Action.SET_COPY_REPEAT_Z, true);
        return y + GROUP_HEIGHT;
    }

    private void addEditorRow(int x, int y, String axis, int value, ConfigureManipulatorMessage.Action action,
                              boolean repeat) {
        GuiTextField field = new GuiTextField(2000 + fields.size(), fontRenderer, x + 47, y + 2, 36, 14);
        field.setMaxStringLength(11);
        field.setText(Integer.toString(value));
        fields.add(new CoordinateField(field, action, repeat));
        addAdjustButton(x, y, axis, action, -1, repeat, false);
        addAdjustButton(x + 90, y, axis, action, 1, repeat, false);
    }

    private void addRegionRow(int x, int y, String axis, ConfigureManipulatorMessage.Action action) {
        headers.add(new Header(x + 45, y, "N/A", 40));
        addAdjustButton(x, y, axis, action, -1, false, true);
        addAdjustButton(x + 90, y, axis, action, 1, false, true);
    }

    private void addAdjustButton(int x, int y, String axis, ConfigureManipulatorMessage.Action action, int sign,
                                 boolean repeat, boolean relative) {
        GuiButton button = new GuiButton(FIRST_ADJUST_BUTTON + adjustButtons.size(), x, y, 40, ROW_HEIGHT, "");
        AdjustButton binding = new AdjustButton(button, axis, action, sign, repeat, relative);
        adjustButtons.add(binding);
        button.displayString = binding.label(1);
        buttonList.add(button);
    }

    private void addRotationRow(int x, int y, int negativeId, int positiveId, String axis) {
        buttonList.add(new GuiButton(negativeId, x, y, 62, ROW_HEIGHT,
                I18n.format("applygray.matter_manipulator.transform.rotate_" + axis + "_minus")));
        buttonList.add(new GuiButton(positiveId, x + 68, y, 62, ROW_HEIGHT,
                I18n.format("applygray.matter_manipulator.transform.rotate_" + axis + "_plus")));
    }

    private void adjust(GuiButton button) {
        AdjustButton binding = adjustButtons.stream().filter(candidate -> candidate.button == button).findFirst()
                .orElse(null);
        if (binding == null) return;
        int amount = binding.sign * step(binding);
        if (binding.relative) {
            send(binding.action, amount);
            return;
        }
        ManipulatorState state = state();
        CoordinateField field = fields.stream().filter(candidate -> candidate.action == binding.action).findFirst()
                .orElse(null);
        int value = displayedValue(field, state, binding.action) + amount;
        if (binding.repeat) value = Math.max(1, Math.min(64, value));
        send(binding.action, value);
        if (field != null) field.widget.setText(Integer.toString(value));
    }

    private static int displayedValue(CoordinateField field, ManipulatorState state,
                                      ConfigureManipulatorMessage.Action action) {
        if (field != null) {
            try {
                return Integer.parseInt(field.widget.getText());
            } catch (NumberFormatException ignored) {
            }
        }
        return valueFor(state, action);
    }

    private int step(AdjustButton button) {
        if (GuiScreen.isShiftKeyDown()) return 10;
        if (!button.repeat && GuiScreen.isCtrlKeyDown()) {
            ManipulatorState state = state();
            if (state != null && state.selectionA() != null && state.selectionB() != null) {
                BlockPos a = state.selectionA().position();
                BlockPos b = state.selectionB().position();
                return switch (button.axis) {
                    case "X" -> Math.abs(b.getX() - a.getX()) + 1;
                    case "Y" -> Math.abs(b.getY() - a.getY()) + 1;
                    case "Z" -> Math.abs(b.getZ() - a.getZ()) + 1;
                    default -> 1;
                };
            }
        }
        return 1;
    }

    private void drawHeader(Header header) {
        drawRect(header.x, header.y, header.x + header.width, header.y + ROW_HEIGHT, 0xFF888888);
        drawRect(header.x + 2, header.y + 2, header.x + header.width - 2, header.y + ROW_HEIGHT - 2, 0xFF111111);
        fontRenderer.drawString(header.text, header.x + (header.width - fontRenderer.getStringWidth(header.text)) / 2,
                header.y + 5, 0xBFBFBF);
    }

    private void drawTransformPanel() {
        int x = 10;
        int y = centeredY(178) + 112;
        drawRect(x, y, x + 88, y + 66, 0xFF888888);
        drawRect(x + 2, y + 2, x + 86, y + 64, 0xFF111111);
        ManipulatorState state = state();
        if (state == null) return;
        List<String> lines = fontRenderer.listFormattedStringToWidth(
                I18n.format("applygray.matter_manipulator.transform.info", state.copyTransform().axisSummary()), 80);
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            fontRenderer.drawString(lines.get(index), x + 4, y + 4 + index * fontRenderer.FONT_HEIGHT, 0xBFBFBF);
        }
        fontRenderer.drawString("\u00a7cX+ \u00a7aY+ \u00a79Z+", x + 34, y + 43, 0xFFFFFF);
    }

    private int centeredY(int contentHeight) {
        return Math.max(4, (height - contentHeight) / 2);
    }

    private void commitFocused() {
        if (focusedField != null) commit(focusedField);
    }

    private void commit(GuiTextField widget) {
        CoordinateField binding = fields.stream().filter(field -> field.widget == widget).findFirst().orElse(null);
        if (binding == null) return;
        try {
            int value = Integer.parseInt(widget.getText());
            if (binding.repeat && (value < 1 || value > 64)) throw new NumberFormatException();
            send(binding.action, value);
        } catch (NumberFormatException exception) {
            widget.setText(Integer.toString(valueFor(state(), binding.action)));
        }
    }

    private static int valueFor(ManipulatorState state, ConfigureManipulatorMessage.Action action) {
        if (state == null) return 0;
        return switch (action) {
            case SET_COPY_REPEAT_X -> state.copyRepeatX();
            case SET_COPY_REPEAT_Y -> state.copyRepeatY();
            case SET_COPY_REPEAT_Z -> state.copyRepeatZ();
            case SET_SELECTION_A_X -> coordinate(state.selectionA(), 0);
            case SET_SELECTION_A_Y -> coordinate(state.selectionA(), 1);
            case SET_SELECTION_A_Z -> coordinate(state.selectionA(), 2);
            case SET_SELECTION_B_X -> coordinate(state.selectionB(), 0);
            case SET_SELECTION_B_Y -> coordinate(state.selectionB(), 1);
            case SET_SELECTION_B_Z -> coordinate(state.selectionB(), 2);
            case SET_SELECTION_C_X -> coordinate(state.selectionC(), 0);
            case SET_SELECTION_C_Y -> coordinate(state.selectionC(), 1);
            case SET_SELECTION_C_Z -> coordinate(state.selectionC(), 2);
            default -> 0;
        };
    }

    private static int coordinate(ManipulatorLocation location, int component) {
        if (location == null) return 0;
        return switch (component) {
            case 0 -> location.position().getX();
            case 1 -> location.position().getY();
            case 2 -> location.position().getZ();
            default -> throw new AssertionError();
        };
    }

    private ManipulatorState state() {
        ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getHeldItem(hand);
        return stack.getItem() instanceof ItemMatterManipulator manipulator ? manipulator.state(stack) : null;
    }

    private void send(ConfigureManipulatorMessage.Action action) {
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, action));
    }

    private void send(ConfigureManipulatorMessage.Action action, int value) {
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, action, value));
    }

    private record CoordinateField(GuiTextField widget, ConfigureManipulatorMessage.Action action, boolean repeat) {}

    private record AdjustButton(GuiButton button, String axis, ConfigureManipulatorMessage.Action action, int sign,
                                boolean repeat, boolean relative) {
        private String label(int amount) {
            return axis + (sign < 0 ? " - " : " + ") + amount;
        }
    }

    private record Header(int x, int y, String text, int width) {
        private Header(int x, int y, String text) {
            this(x, y, text, 60);
        }
    }
}
