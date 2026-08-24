package applygray.client.mattermanipulator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.network.ConfigureManipulatorMessage;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import org.lwjgl.opengl.GL11;

/** Source-faithful radial configuration menu for the Matter Manipulator. */
public final class MatterManipulatorConfigScreen extends GuiScreen {

    private static final double TAU = Math.PI * 2.0D;
    private static final double INNER_RADIUS = 0.25D;
    private static final double OUTER_RADIUS = 0.60D;
    private static final int LABEL_WRAP_WIDTH = 60;

    private final EnumHand hand;
    private List<MenuEntry> entries = List.of();

    public MatterManipulatorConfigScreen(EnumHand hand) {
        this.hand = hand;
    }

    @Override
    public void initGui() {
        entries = rootEntries();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (entries.isEmpty()) entries = rootEntries();
        List<Slice> slices = slices();
        PolarMouse mouse = mouse(mouseX, mouseY);

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ZERO);
        for (Slice slice : slices) {
            boolean hovered = hit(mouse, slice);
            drawSlice(slice, hovered ? 0.25F : 0.0F, hovered ? 0.25F : 0.0F, hovered ? 0.25F : 0.0F, 1.0F);
        }
        GlStateManager.enableTexture2D();

        for (Slice slice : slices) drawLabel(slice);
        drawCenterItem(partialTicks);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        PolarMouse mouse = mouse(mouseX, mouseY);
        for (Slice slice : slices()) {
            if (!hit(mouse, slice)) continue;
            playClick();
            if (!slice.entry.children.isEmpty()) {
                entries = slice.entry.children;
            } else if (slice.entry.action != null) {
                slice.entry.action.run();
                if (mc.currentScreen == this) mc.displayGuiScreen(null);
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private List<MenuEntry> rootEntries() {
        ManipulatorState state = state();
        ItemStack stack = heldStack();
        if (state == null || !(stack.getItem() instanceof ItemMatterManipulator manipulator)) return List.of();

        List<MenuEntry> root = new ArrayList<>();
        List<MenuEntry> modes = new ArrayList<>();
        if (has(manipulator, stack, ManipulatorCapability.REMOVAL)) modes.add(removalBranch());
        addMode(modes, manipulator, stack, ManipulatorPlaceMode.GEOMETRY, ManipulatorCapability.GEOMETRY);
        addMode(modes, manipulator, stack, ManipulatorPlaceMode.MOVING, ManipulatorCapability.MOVING);
        addMode(modes, manipulator, stack, ManipulatorPlaceMode.COPYING, ManipulatorCapability.COPYING);
        addMode(modes, manipulator, stack, ManipulatorPlaceMode.EXCHANGING, ManipulatorCapability.EXCHANGING);
        addMode(modes, manipulator, stack, ManipulatorPlaceMode.CABLES, ManipulatorCapability.CABLES);
        if (manipulator.tier() == applygray.mattermanipulator.state.ManipulatorTier.MK0) {
            if (has(manipulator, stack, ManipulatorCapability.REMOVAL)) root.add(removalBranch());
        } else {
            root.add(branch("applygray.matter_manipulator.radial.set_mode", modes));
        }

        switch (state.placeMode()) {
            case GEOMETRY -> addGeometry(root);
            case COPYING -> addCopying(root, manipulator, stack);
            case MOVING -> addMoving(root);
            case EXCHANGING -> addExchanging(root);
            case CABLES -> addCables(root);
        }
        if (hasBoundUplink(state, manipulator, stack)) root.add(uplinkBranch());
        return List.copyOf(root);
    }

    private void addGeometry(List<MenuEntry> root) {
        root.add(branch("applygray.matter_manipulator.radial.select_blocks", List.of(
                action("applygray.matter_manipulator.radial.set_corners", ConfigureManipulatorMessage.Action.PICK_CORNER),
                action("applygray.matter_manipulator.radial.set_edges", ConfigureManipulatorMessage.Action.PICK_EDGE),
                action("applygray.matter_manipulator.radial.set_faces", ConfigureManipulatorMessage.Action.PICK_FACE),
                action("applygray.matter_manipulator.radial.set_volumes", ConfigureManipulatorMessage.Action.PICK_VOLUME),
                action("applygray.matter_manipulator.radial.set_all", ConfigureManipulatorMessage.Action.PICK_ALL),
                action("applygray.matter_manipulator.radial.clear_all",
                        ConfigureManipulatorMessage.Action.CLEAR_ALL_GEOMETRY))));

        List<MenuEntry> shapes = new ArrayList<>();
        for (ManipulatorShape shape : ManipulatorShape.values()) {
            shapes.add(actionValue("applygray.matter_manipulator.shape." + shape.name().toLowerCase(),
                    ConfigureManipulatorMessage.Action.SET_SHAPE, shape.ordinal()));
        }
        root.add(branch("applygray.matter_manipulator.radial.set_shape", shapes));
        root.add(coordinatesBranch());
    }

    private void addCopying(List<MenuEntry> root, ItemMatterManipulator manipulator, ItemStack stack) {
        root.add(action("applygray.matter_manipulator.radial.mark_copy", ConfigureManipulatorMessage.Action.MARK_COPY));
        root.add(branch("applygray.matter_manipulator.radial.edit_stack", List.of(
                action("applygray.matter_manipulator.radial.mark_array", ConfigureManipulatorMessage.Action.MARK_ARRAY),
                action("applygray.matter_manipulator.radial.reset", ConfigureManipulatorMessage.Action.RESET_COPY_REPEATS))));
        root.add(entry("applygray.matter_manipulator.radial.edit_transform", this::openTransform));
        root.add(action("applygray.matter_manipulator.radial.mark_paste", ConfigureManipulatorMessage.Action.MARK_PASTE));
        if (has(manipulator, stack, ManipulatorCapability.SMART_COPY)) {
            root.add(branch("applygray.matter_manipulator.radial.advanced_options", List.of(
                    action("applygray.matter_manipulator.radial.smart_copy",
                            ConfigureManipulatorMessage.Action.TOGGLE_SMART_COPY),
                    action("applygray.matter_manipulator.radial.link_external_hubs",
                            ConfigureManipulatorMessage.Action.TOGGLE_EXTERNAL_HUBS),
                    action("applygray.matter_manipulator.radial.replace_cribs",
                            ConfigureManipulatorMessage.Action.TOGGLE_CRIB_PROXIES),
                    action("applygray.matter_manipulator.radial.replace_interfaces",
                            ConfigureManipulatorMessage.Action.TOGGLE_INTERFACE_P2P))));
        }
    }

    private void addMoving(List<MenuEntry> root) {
        root.add(action("applygray.matter_manipulator.radial.mark_cut", ConfigureManipulatorMessage.Action.MARK_CUT));
        root.add(action("applygray.matter_manipulator.radial.mark_paste", ConfigureManipulatorMessage.Action.MARK_PASTE));
        root.add(entry("applygray.matter_manipulator.radial.edit_transform", this::openTransform));
    }

    private void addExchanging(List<MenuEntry> root) {
        root.add(branch("applygray.matter_manipulator.radial.edit_whitelist", List.of(
                action("applygray.matter_manipulator.radial.clear", ConfigureManipulatorMessage.Action.CLEAR_EXCHANGE_WHITELIST),
                action("applygray.matter_manipulator.radial.add_block",
                        ConfigureManipulatorMessage.Action.PICK_EXCHANGE_WHITELIST_ADD),
                action("applygray.matter_manipulator.radial.set_block",
                        ConfigureManipulatorMessage.Action.PICK_EXCHANGE_WHITELIST_SET))));
        root.add(action("applygray.matter_manipulator.radial.set_replacement",
                ConfigureManipulatorMessage.Action.PICK_EXCHANGE_REPLACEMENT));
        root.add(coordinatesBranch());
    }

    private void addCables(List<MenuEntry> root) {
        root.add(action("applygray.matter_manipulator.radial.set_cable", ConfigureManipulatorMessage.Action.PICK_CABLE));
        root.add(coordinatesBranch());
    }

    private MenuEntry coordinatesBranch() {
        return branch("applygray.matter_manipulator.radial.move_coords", List.of(
                action("applygray.matter_manipulator.radial.move_a", ConfigureManipulatorMessage.Action.CLEAR_SELECTION_A),
                action("applygray.matter_manipulator.radial.move_all", ConfigureManipulatorMessage.Action.RESET_SELECTIONS),
                action("applygray.matter_manipulator.radial.move_b", ConfigureManipulatorMessage.Action.CLEAR_SELECTION_B),
                action("applygray.matter_manipulator.radial.move_c", ConfigureManipulatorMessage.Action.CLEAR_SELECTION_C)));
    }

    private MenuEntry removalBranch() {
        List<MenuEntry> modes = new ArrayList<>();
        for (ManipulatorRemovalMode mode : ManipulatorRemovalMode.values()) {
            modes.add(actionValue("applygray.matter_manipulator.removal." + mode.name().toLowerCase(),
                    ConfigureManipulatorMessage.Action.SET_REMOVAL_MODE, mode.ordinal()));
        }
        return branch("applygray.matter_manipulator.radial.set_remove_mode", modes);
    }

    private MenuEntry uplinkBranch() {
        return branch("applygray.matter_manipulator.radial.planning", List.of(
                action("applygray.matter_manipulator.config.uplink.cancel",
                        ConfigureManipulatorMessage.Action.CANCEL_UPLINK_CRAFTING),
                action("applygray.matter_manipulator.config.uplink.request_all",
                        ConfigureManipulatorMessage.Action.REQUEST_UPLINK_ALL),
                action("applygray.matter_manipulator.config.uplink.request_missing",
                        ConfigureManipulatorMessage.Action.REQUEST_UPLINK_MISSING)));
    }

    private void addMode(List<MenuEntry> entries, ItemMatterManipulator manipulator, ItemStack stack,
                         ManipulatorPlaceMode mode, ManipulatorCapability capability) {
        if (has(manipulator, stack, capability)) {
            entries.add(actionValue("applygray.matter_manipulator.mode." + mode.name().toLowerCase(),
                    ConfigureManipulatorMessage.Action.SET_PLACE_MODE, mode.ordinal()));
        }
    }

    private void openTransform() {
        mc.displayGuiScreen(new MatterManipulatorTransformScreen(hand));
    }

    private List<Slice> slices() {
        if (entries.isEmpty()) return List.of();
        double sliceSize = TAU / entries.size();
        double offset = sliceSize / 2.0D;
        List<Slice> slices = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            slices.add(new Slice(entries.get(index), index * sliceSize - offset, (index + 1) * sliceSize - offset));
        }
        return slices;
    }

    private void drawSlice(Slice slice, float red, float green, float blue, float alpha) {
        int dim = Math.min(width, height);
        double scale = dim / 2.0D;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        double step = Math.PI / 32.0D;
        for (int index = 0; ; index++) {
            double angle = Math.min(slice.start + index * step, slice.end);
            vertex(buffer, angle, OUTER_RADIUS, scale, red, green, blue, alpha);
            vertex(buffer, angle, INNER_RADIUS, scale, red, green, blue, alpha);
            if (angle >= slice.end) break;
        }
        tessellator.draw();
    }

    private void vertex(BufferBuilder buffer, double angle, double radius, double scale, float red, float green,
                        float blue, float alpha) {
        buffer.pos(width / 2.0D + Math.cos(angle) * radius * scale,
                height / 2.0D + Math.sin(angle) * radius * scale, 0.0D)
                .color(red, green, blue, alpha).endVertex();
    }

    private void drawLabel(Slice slice) {
        double theta = (slice.start + slice.end) / 2.0D;
        double radius = (INNER_RADIUS + OUTER_RADIUS) / 2.0D;
        int dim = Math.min(width, height);
        int x = (int) (width / 2.0D + Math.cos(theta) * radius * dim / 2.0D);
        int y = (int) (height / 2.0D + Math.sin(theta) * radius * dim / 2.0D);
        List<String> lines = fontRenderer.listFormattedStringToWidth(I18n.format(slice.entry.translationKey),
                LABEL_WRAP_WIDTH);
        int blockHeight = lines.size() * fontRenderer.FONT_HEIGHT;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            fontRenderer.drawString(line, x - fontRenderer.getStringWidth(line) / 2,
                    y - blockHeight / 2 + index * fontRenderer.FONT_HEIGHT, 0xCCCCCC);
        }
    }

    private void drawCenterItem(float partialTicks) {
        ItemStack stack = heldStack();
        if (stack.isEmpty()) return;
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.pushMatrix();
        GlStateManager.translate(width / 2.0F - 16.0F, height / 2.0F - 16.0F, 0.0F);
        GlStateManager.scale(2.0F, 2.0F, 1.0F);
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();
    }

    private PolarMouse mouse(int mouseX, int mouseY) {
        int dim = Math.min(width, height);
        double x = (mouseX - width / 2.0D) / (dim / 2.0D);
        double y = (mouseY - height / 2.0D) / (dim / 2.0D);
        return new PolarMouse(Math.sqrt(x * x + y * y), modTau(Math.atan2(y, x)));
    }

    private static boolean hit(PolarMouse mouse, Slice slice) {
        return mouse.radius >= INNER_RADIUS && mouse.radius <= OUTER_RADIUS &&
                modTau(mouse.theta - slice.start) < slice.end - slice.start;
    }

    private static double modTau(double angle) {
        return (angle % TAU + TAU) % TAU;
    }

    private void playClick() {
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(
                net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private ManipulatorState state() {
        ItemStack stack = heldStack();
        return stack.getItem() instanceof ItemMatterManipulator manipulator ? manipulator.state(stack) : null;
    }

    private ItemStack heldStack() {
        return mc.player == null ? ItemStack.EMPTY : mc.player.getHeldItem(hand);
    }

    private static boolean has(ItemMatterManipulator manipulator, ItemStack stack, ManipulatorCapability capability) {
        return manipulator.hasCapability(stack, capability);
    }

    private static boolean hasBoundUplink(ManipulatorState state, ItemMatterManipulator manipulator, ItemStack stack) {
        return state.uplinkAddress() != null && manipulator.hasCapability(stack, ManipulatorCapability.UPLINK);
    }

    private MenuEntry action(String key, ConfigureManipulatorMessage.Action action) {
        return entry(key, () -> send(action));
    }

    private MenuEntry actionValue(String key, ConfigureManipulatorMessage.Action action, int value) {
        return entry(key, () -> send(action, value));
    }

    private static MenuEntry branch(String key, List<MenuEntry> children) {
        return new MenuEntry(key, null, List.copyOf(children));
    }

    private static MenuEntry entry(String key, Runnable action) {
        return new MenuEntry(key, action, List.of());
    }

    private void send(ConfigureManipulatorMessage.Action action) {
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, action));
    }

    private void send(ConfigureManipulatorMessage.Action action, int value) {
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, action, value));
    }

    private record MenuEntry(String translationKey, Runnable action, List<MenuEntry> children) {}
    private record Slice(MenuEntry entry, double start, double end) {}
    private record PolarMouse(double radius, double theta) {}
}
