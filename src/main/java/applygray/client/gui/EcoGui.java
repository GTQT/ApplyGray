package applygray.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.style.GuiStyleManager;
import ae2.client.gui.widgets.TabButton;
import applygray.common.eco.EcoPart;
import applygray.common.eco.gui.EcoContainer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextComponentTranslation;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.List;

/** AE2 dashboard with a consistent light gray and muted purple control palette. */
public final class EcoGui extends AEBaseGui<EcoContainer> {
    private static final int PANEL = 0xFFC7C7CB;
    private static final int EDGE = 0xFF73717C;
    private static final int LIGHT = 0xFFEEEEF1;
    private static final int TEXT = 0xFF494650;
    private static final int ACCENT = 0xFF8E71B2;

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
        search.setDisabledTextColour(0xFF858591);
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
        drawFrame(x, y, xSize, ySize, PANEL, false);

        drawRect(x + 1, y + 1, x + xSize - 1, y + 20, 0xFFB6B3C1);
        drawRect(x + 1, y + 20, x + xSize - 1, y + 22, ACCENT);
        drawFrame(x + 33, y + 23, 297, 143, 0xFFB8B7BF, true);
        drawFrame(x + 39, y + 25, 207, 16, 0xFFE1E0E5, true);
        drawRect(x + 34, y + 168, x + 330, y + 170, 0xFF8E71B2);
        if (!(container.host instanceof EcoPart part && part.inventory.getSlots() > 0)) {
            drawRect(x + 40, y + 47, x + 324, y + 66, 0xFFCDCCD4);
            double fraction = container.capacity == 0 ? 0 : Math.clamp((double) container.used / container.capacity, 0, 1);
            drawRect(x + 41, y + 63, x + 41 + (int) (282 * fraction), y + 65, ACCENT);
            fontRenderer.drawString(I18n.format("applygray.eco.ui.capacity") + " " + compact(container.used) + " / "
                    + compact(container.capacity), x + 44, y + 49, TEXT);
            fontRenderer.drawString(container.active + " / " + container.total, x + 250, y + 49, 0xFF655076);
            String[] rows = container.rows.isEmpty() ? new String[0] : localized(container.rows).split("\n");
            for (int row = 0; row < Math.min(EcoContainer.ROWS, rows.length); row++) {
                int top = y + 72 + row * 13;
                drawRect(x + 38, top, x + 326, top + 12, row == selected ? 0xFFC4B2DC : (row % 2 == 0 ? 0xFFCFCDD6 : 0xFFC2C0CA));
                fontRenderer.drawString(fontRenderer.trimStringToWidth(rows[row], 280), x + 42, top + 2, TEXT);
            }
        }
        for (var slot : container.inventorySlots) {
            int sx = x + slot.xPos, sy = y + slot.yPos;
            drawFrame(sx - 1, sy - 1, 18, 18, 0xFFA8A7AE, true);
        }
        fontRenderer.drawString(fontRenderer.trimStringToWidth(container.getGuiTitle().getUnformattedText(), 285),
                x + 9, y + 7, TEXT);
        drawRect(x + 319, y + 7, x + 325, y + 13, container.online ? 0xFF7CCB95 : 0xFFDD946C);
        fontRenderer.drawString((container.page + 1) + "/" + container.pageCount, x + 280, y + 29, TEXT);
        fontRenderer.drawString(I18n.format("container.inventory"), x + 38, y + 169, 0xFF494650);
        fontRenderer.drawString(I18n.format(container.online ? "applygray.eco.ui.online" : "applygray.eco.ui.offline"),
                x + 219, y + 236, 0xFF494650);
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused())
            fontRenderer.drawString(I18n.format("applygray.eco.ui.search"), x + 44, y + 29, 0xFF858591);
    }
    @Override public void drawFG(int offsetX, int offsetY, int mouseX, int mouseY) {
        if (mouseX >= guiLeft + 38 && mouseX < guiLeft + 327 && mouseY >= guiTop + 47 && mouseY < guiTop + 66) {
            drawHoveringText(List.of(NumberFormat.getIntegerInstance().format(container.used) + " / "
                    + NumberFormat.getIntegerInstance().format(container.capacity),
                    I18n.format("applygray.eco.ui.accelerators") + ": " + container.accelerators,
                    I18n.format("applygray.eco.ui.types") + ": " + container.secondaryUsed + " / " + container.secondaryCapacity,
                    I18n.format("applygray.eco.ui.energy") + ": " + container.energyStored + " / " + container.energyCapacity),
                    mouseX - guiLeft, mouseY - guiTop);
        } else if (mouseX >= guiLeft + 38 && mouseX < guiLeft + 327 && mouseY >= guiTop + 72 && mouseY < guiTop + 163) {
            String[] rows = container.rows.isEmpty() ? new String[0] : localized(container.rows).split("\n");
            int index = (mouseY - guiTop - 72) / 13;
            if (index < rows.length && !rows[index].isEmpty())
                drawHoveringText(fontRenderer.listFormattedStringToWidth(rows[index], 250), mouseX - guiLeft, mouseY - guiTop);
        }
    }
    private static void drawFrame(int x, int y, int width, int height, int fill, boolean inset) {
        drawRect(x, y, x + width, y + height, inset ? LIGHT : EDGE);
        drawRect(x, y, x + width - 1, y + height - 1, inset ? EDGE : LIGHT);
        drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    /** Keep vanilla button interaction, with the same bevel and colors as the AE2 panel. */
    private static final class EcoButton extends GuiButton {
        private EcoButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, label);
        }

        @Override
        public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            drawFrame(x, y, width, height, !enabled ? 0xFFB8B7BF : hovered ? 0xFFC4B2DC : PANEL, false);
            mc.fontRenderer.drawString(displayString, x + (width - mc.fontRenderer.getStringWidth(displayString)) / 2,
                    y + (height - 8) / 2, enabled ? TEXT : 0xFF85818E);
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
