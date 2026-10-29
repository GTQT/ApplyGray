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

/** Native AE2 dashboard with narrow tool tabs and a compact, high-contrast status surface. */
public final class EcoGui extends AEBaseGui<EcoContainer> {
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
        search = new GuiTextField(100, fontRenderer, guiLeft + 40, guiTop + 26, 205, 14);
        search.setMaxStringLength(96);
        search.setText(oldSearch);
        previous = addButton(new GuiButton(1, guiLeft + 255, guiTop + 25, 20, 16, "<"));
        next = addButton(new GuiButton(2, guiLeft + 306, guiTop + 25, 20, 16, ">"));
        toggle = addButton(new GuiButton(3, guiLeft + 218, guiTop + 183, 108, 20, ""));
        cancel = addButton(new GuiButton(4, guiLeft + 218, guiTop + 208, 108, 20,
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
        drawRect(x, y, x + xSize, y + ySize, 0xFF3B3B43);
        drawRect(x + 1, y + 1, x + xSize - 1, y + ySize - 1, 0xFFC7C7CB);
        drawRect(x + 1, y + 1, x + xSize - 1, y + 20, 0xFF42424C);
        drawRect(x + 1, y + 20, x + xSize - 1, y + 22, 0xFF9D79CE);
        drawRect(x + 33, y + 23, x + 330, y + 166, 0xFF272932);
        drawRect(x + 34, y + 168, x + 330, y + 170, 0xFF8E71B2);
        if (!(container.host instanceof EcoPart part && part.inventory.getSlots() > 0)) {
            drawRect(x + 40, y + 47, x + 324, y + 66, 0xFF363945);
            double fraction = container.capacity == 0 ? 0 : Math.clamp((double) container.used / container.capacity, 0, 1);
            drawRect(x + 41, y + 63, x + 41 + (int) (282 * fraction), y + 65, 0xFFB18AE1);
            fontRenderer.drawString(I18n.format("applygray.eco.ui.capacity") + " " + compact(container.used) + " / "
                    + compact(container.capacity), x + 44, y + 49, 0xFFE0E2EB);
            fontRenderer.drawString(container.active + " / " + container.total, x + 250, y + 49, 0xFFBBA0DC);
            String[] rows = localized(container.rows).split("\n");
            for (int row = 0; row < Math.min(EcoContainer.ROWS, rows.length); row++) {
                int top = y + 72 + row * 13;
                drawRect(x + 38, top, x + 326, top + 12, row == selected ? 0xFF655076 : (row % 2 == 0 ? 0xFF30333D : 0xFF292C35));
                fontRenderer.drawString(fontRenderer.trimStringToWidth(rows[row], 280), x + 42, top + 2, 0xFFD1D6DF);
            }
        }
        for (var slot : container.inventorySlots) {
            int sx = x + slot.xPos, sy = y + slot.yPos;
            drawRect(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF73717C);
            drawRect(sx, sy, sx + 16, sy + 16, 0xFFA8A7AE);
        }
        fontRenderer.drawString(fontRenderer.trimStringToWidth(container.getGuiTitle().getUnformattedText(), 285),
                x + 9, y + 7, 0xFFEEEAF5);
        drawRect(x + 319, y + 7, x + 325, y + 13, container.online ? 0xFF7CCB95 : 0xFFDD946C);
        fontRenderer.drawString((container.page + 1) + "/" + container.pageCount, x + 280, y + 29, 0xFFE1D2EF);
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
            String[] rows = localized(container.rows).split("\n");
            int index = (mouseY - guiTop - 72) / 13;
            if (index < rows.length && !rows[index].isEmpty())
                drawHoveringText(fontRenderer.listFormattedStringToWidth(rows[index], 250), mouseX - guiLeft, mouseY - guiTop);
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
