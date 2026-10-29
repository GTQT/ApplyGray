package applygray.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.style.GuiStyleManager;
import ae2.client.gui.widgets.TabButton;
import applygray.common.metatileentities.multi.electric.eco.gui.EcoContainer;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.List;

/**
 * ECO 仪表盘：容器与同步协议沿用 AE2，绘制层改为 NeoECOAEExtension 的浅灰面板风格。
 *
 * <p>NeoECO 的 GUI 贴图是 16px/20px 的小图，按九宫格拉伸，避免大面板被拉糊。</p>
 */
public final class EcoGui extends AEBaseGui<EcoContainer> {
    private static final ResourceLocation PANEL = texture("panel");
    private static final ResourceLocation CARD = texture("card");
    private static final ResourceLocation SLOT = texture("slot");
    private static final ResourceLocation BUTTON = texture("button");
    private static final ResourceLocation BUTTON_HIGHLIGHTED = texture("button_highlighted");
    private static final ResourceLocation INVENTORY_BORDER = texture("inventory_border");

    private static final int TEXT = 0xFF494650;
    private static final int MUTED = 0xFF858591;
    private static final int ACCENT = 0xFF8E71B2;
    private static final int ROW_EVEN = 0xFFD8D7DE;
    private static final int ROW_ODD = 0xFFCFCDD6;
    private static final int ROW_SELECTED = 0xFFC4B2DC;

    private GuiTextField search;
    private int searchDelay;
    private int selected = -1;
    private String shownIds = "";
    private final TabButton overview, contents, hardware;
    private GuiButton previous, next, toggle, cancel;

    public EcoGui(EcoContainer container, InventoryPlayer inventory) {
        super(container, inventory, GuiStyleManager.loadStyleDoc("/screens/applygray_eco.json"));
        xSize = 336; ySize = 264;
        overview = new TabButton(Icon.CRAFT_HAMMER, new TextComponentTranslation("applygray.eco.ui.overview"), () -> changeTab(0));
        contents = new TabButton(Icon.PATTERN_UPLOAD, new TextComponentTranslation("applygray.eco.ui.contents"), () -> changeTab(1));
        hardware = new TabButton(Icon.BACK, new TextComponentTranslation("applygray.eco.ui.hardware"), () -> changeTab(2));
        widgets.add("ecoOverview", overview, new Point(4, 27));
        widgets.add("ecoContents", contents, new Point(4, 53));
        widgets.add("ecoHardware", hardware, new Point(4, 79));
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation("applygray", "textures/gui/eco/" + name + ".png");
    }

    private void changeTab(int tab) { selected = -1; container.changeTab(tab); }
    @Override protected boolean shouldAddToolbar() { return false; }
    @Override protected boolean shouldDrawStyleBackground() { return false; }
    @Override protected boolean shouldDrawStyleText() { return false; }
    @Override public void initGui() {
        String oldSearch = search == null ? "" : search.getText();
        super.initGui();
        search = new GuiTextField(100, fontRenderer, guiLeft + 44, guiTop + 29, 197, 10);
        search.setEnableBackgroundDrawing(false);
        search.setTextColor(TEXT);
        search.setDisabledTextColour(MUTED);
        search.setMaxStringLength(96);
        search.setText(oldSearch);
        previous = addButton(new EcoButton(1, guiLeft + 255, guiTop + 25, 20, 16, "<"));
        next = addButton(new EcoButton(2, guiLeft + 306, guiTop + 25, 20, 16, ">"));
        toggle = addButton(new EcoButton(3, guiLeft + 218, guiTop + 183, 108, 20, ""));
        cancel = addButton(new EcoButton(4, guiLeft + 218, guiTop + 208, 108, 20,
                I18n.format("applygray.eco.ui.cancel")));
        cancel.enabled = false;
    }
    @Override public void updateScreen() {
        super.updateScreen();
        search.updateCursorCounter();
        if (searchDelay > 0 && --searchDelay == 0) container.search(search.getText());
    }
    @Override protected void updateBeforeRender() {
        if (!shownIds.equals(container.rowIds)) { shownIds = container.rowIds; selected = -1; }
        previous.enabled = container.page > 0;
        next.enabled = container.page + 1 < container.pageCount;
        toggle.displayString = I18n.format(container.enabled ? "applygray.eco.ui.disable" : "applygray.eco.ui.enable");
        String[] ids = container.rowIds.split("\n", -1);
        cancel.enabled = selected >= 0 && selected < ids.length && !ids[selected].isEmpty();
    }
    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (button == previous) { selected = -1; container.changePage(container.page - 1); }
        else if (button == next) { selected = -1; container.changePage(container.page + 1); }
        else if (button == toggle) container.toggle();
        else if (button == cancel) {
            String[] ids = container.rowIds.split("\n", -1);
            if (selected >= 0 && selected < ids.length) container.cancel(ids[selected]);
        } else super.actionPerformed(button);
    }
    @Override protected void keyTyped(char character, int key) throws IOException {
        if (key != 1 && search.textboxKeyTyped(character, key)) { searchDelay = 4; return; }
        super.keyTyped(character, key);
    }
    @Override protected void mouseClicked(int x, int y, int button) throws IOException {
        search.mouseClicked(x, y, button);
        if (button == 0 && x >= guiLeft + 38 && x < guiLeft + 326 && y >= guiTop + 72 && y < guiTop + 163)
            selected = (y - guiTop - 72) / 13;
        super.mouseClicked(x, y, button);
    }
    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partial) {
        drawNineSlice(PANEL, x, y, xSize, ySize, 3, 16);

        // 标题栏
        drawNineSlice(CARD, x + 1, y + 1, xSize - 2, 19, 2, 16);
        drawRect(x + 1, y + 20, x + xSize - 1, y + 22, ACCENT);

        // 列表卡片
        drawNineSlice(CARD, x + 33, y + 23, 297, 143, 2, 16);
        drawNineSlice(PANEL, x + 39, y + 25, 207, 16, 2, 16);

        if (!(container.host instanceof MetaTileEntityEcoPart part && part.inventory().getSlots() > 0)) {
            drawNineSlice(CARD, x + 38, y + 47, 288, 19, 2, 16);
            double fraction = container.capacity == 0 ? 0 : Math.clamp((double) container.used / container.capacity, 0, 1);
            drawRect(x + 40, y + 61, x + 324, y + 64, ROW_ODD);
            drawRect(x + 40, y + 61, x + 40 + (int) (284 * fraction), y + 64, ACCENT);
            fontRenderer.drawString(label() + " " + amount(container.used) + " / " + amount(container.capacity),
                    x + 44, y + 51, TEXT);
            fontRenderer.drawString(container.active + " / " + container.total, x + 280, y + 51, 0xFF655076);
            String[] rows = container.rows.isEmpty() ? new String[0] : localized(container.rows).split("\n");
            for (int row = 0; row < Math.min(EcoContainer.ROWS, rows.length); row++) {
                int top = y + 72 + row * 13;
                drawRect(x + 38, top, x + 326, top + 12,
                        row == selected ? ROW_SELECTED : (row % 2 == 0 ? ROW_EVEN : ROW_ODD));
                fontRenderer.drawString(fontRenderer.trimStringToWidth(rows[row], 280), x + 42, top + 2, TEXT);
            }
        }

        drawRect(x + 34, y + 166, x + 330, y + 168, ACCENT);
        drawNineSlice(INVENTORY_BORDER, x + 34, y + 167, 296, 92, 2, 16);

        for (var slot : container.inventorySlots) {
            drawTexture(SLOT, x + slot.xPos - 1, y + slot.yPos - 1, 18, 18, 18);
        }

        fontRenderer.drawString(fontRenderer.trimStringToWidth(container.getGuiTitle().getUnformattedText(), 285),
                x + 9, y + 7, TEXT);
        drawRect(x + 319, y + 7, x + 325, y + 13, container.online ? 0xFF7CCB95 : 0xFFDD946C);
        fontRenderer.drawString((container.page + 1) + "/" + container.pageCount, x + 280, y + 29, TEXT);
        fontRenderer.drawString(I18n.format("container.inventory"), x + 38, y + 170, TEXT);
        fontRenderer.drawString(I18n.format(container.online ? "applygray.eco.ui.online" : "applygray.eco.ui.offline"),
                x + 219, y + 236, TEXT);
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused())
            fontRenderer.drawString(I18n.format("applygray.eco.ui.search"), x + 44, y + 29, MUTED);
    }
    @Override public void drawFG(int offsetX, int offsetY, int mouseX, int mouseY) {
        if (mouseX >= guiLeft + 38 && mouseX < guiLeft + 327 && mouseY >= guiTop + 47 && mouseY < guiTop + 66) {
            List<String> lines = new java.util.ArrayList<>();
            lines.add(NumberFormat.getIntegerInstance().format(container.used) + " / "
                    + NumberFormat.getIntegerInstance().format(container.capacity));
            if (container.battery)
                lines.add(I18n.format("applygray.eco.ui.energy_rate",
                        NumberFormat.getIntegerInstance().format(container.energyRate)));
            lines.add(I18n.format("applygray.eco.ui.accelerators") + ": " + container.accelerators);
            lines.add(I18n.format("applygray.eco.ui.types") + ": " + container.secondaryUsed + " / " + container.secondaryCapacity);
            drawHoveringText(lines, mouseX - guiLeft, mouseY - guiTop);
        } else if (mouseX >= guiLeft + 38 && mouseX < guiLeft + 327 && mouseY >= guiTop + 72 && mouseY < guiTop + 163) {
            String[] rows = container.rows.isEmpty() ? new String[0] : localized(container.rows).split("\n");
            int index = (mouseY - guiTop - 72) / 13;
            if (index < rows.length && !rows[index].isEmpty())
                drawHoveringText(fontRenderer.listFormattedStringToWidth(rows[index], 250), mouseX - guiLeft, mouseY - guiTop);
        }
    }

    private String label() {
        return I18n.format(container.battery ? "applygray.eco.ui.energy" : "applygray.eco.ui.capacity");
    }

    private String amount(long value) {
        // AE 能量是十进制，存储字节是二进制；两种单位不要共用同一个后缀表。
        return container.battery ? NumberFormat.getIntegerInstance().format(value) : compact(value);
    }

    private static void bind(ResourceLocation texture) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private static void drawTexture(ResourceLocation texture, int x, int y, int width, int height, int textureSize) {
        bind(texture);
        // drawModalRectWithCustomSizedTexture 会把 width/height 同时当作 UV 范围，这里必须用
        // drawScaledCustomSizeModalRect 把 16/18px 的源图真正拉伸到目标尺寸，否则 GL_REPEAT 会铺成网格。
        drawScaledCustomSizeModalRect(x, y, 0, 0, textureSize, textureSize, width, height,
                textureSize, textureSize);
    }

    /** 九宫格拉伸：贴图只有 16/20 px，直接放大整张会糊掉边框。 */
    private static void drawNineSlice(ResourceLocation texture, int x, int y, int width, int height,
                                      int slice, int textureSize) {
        bind(texture);
        int inner = textureSize - slice * 2;
        int edge = textureSize - slice;
        int midWidth = Math.max(0, width - slice * 2);
        int midHeight = Math.max(0, height - slice * 2);
        drawScaledCustomSizeModalRect(x, y, 0, 0, slice, slice, slice, slice, textureSize, textureSize);
        drawScaledCustomSizeModalRect(x + width - slice, y, edge, 0, slice, slice, slice, slice,
                textureSize, textureSize);
        drawScaledCustomSizeModalRect(x, y + height - slice, 0, edge, slice, slice, slice, slice,
                textureSize, textureSize);
        drawScaledCustomSizeModalRect(x + width - slice, y + height - slice, edge, edge, slice, slice,
                slice, slice, textureSize, textureSize);
        if (midWidth > 0) {
            drawScaledCustomSizeModalRect(x + slice, y, slice, 0, inner, slice, midWidth, slice,
                    textureSize, textureSize);
            drawScaledCustomSizeModalRect(x + slice, y + height - slice, slice, edge, inner, slice,
                    midWidth, slice, textureSize, textureSize);
        }
        if (midHeight > 0) {
            drawScaledCustomSizeModalRect(x, y + slice, 0, slice, slice, inner, slice, midHeight,
                    textureSize, textureSize);
            drawScaledCustomSizeModalRect(x + width - slice, y + slice, edge, slice, slice, inner,
                    slice, midHeight, textureSize, textureSize);
        }
        if (midWidth > 0 && midHeight > 0) {
            drawScaledCustomSizeModalRect(x + slice, y + slice, slice, slice, inner, inner,
                    midWidth, midHeight, textureSize, textureSize);
        }
    }

    /** 沿用原版按钮交互，用 NeoECO 的按钮底图重绘。 */
    private static final class EcoButton extends GuiButton {
        private EcoButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, label);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            drawNineSlice(enabled && hovered ? BUTTON_HIGHLIGHTED : BUTTON, x, y, width, height, 3, 20);
            mc.fontRenderer.drawString(displayString, x + (width - mc.fontRenderer.getStringWidth(displayString)) / 2,
                    y + (height - 8) / 2, enabled ? TEXT : MUTED);
            mouseDragged(mc, mouseX, mouseY);
        }
    }

    private static String compact(long value) {
        double number = value; String[] units = {"", "K", "M", "G", "T", "P"}; int unit = 0;
        while (number >= 1024 && unit < units.length - 1) { number /= 1024; unit++; }
        return unit == 0 ? Long.toString(value) : String.format(java.util.Locale.ROOT, "%.2f%s", number, units[unit]);
    }
    private static String localized(String text) {
        var matcher = java.util.regex.Pattern.compile("\\{(applygray\\.eco\\.state\\.[a-z_]+)\\}").matcher(text);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(I18n.format(match.group(1))));
    }
}
