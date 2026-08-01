package applygray.client.gui;

import applygray.integration.ae2.IRecipePatternGeneration;
import applygray.integration.ae2.PatternGenerationTreeData;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.client.gui.me.crafting.CraftingTreeWidget;
import ae2.client.gui.style.Blitter;
import ae2.client.gui.widgets.TabButton;
import ae2.container.implementations.ContainerCraftAmount;
import ae2.core.localization.GuiText;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextComponentTranslation;

/** Independent RecipeMap view that reuses AE2's crafting-tree renderer and interaction model. */
public class GuiRecipePatternGenerationTree extends AEBaseGui<ContainerCraftAmount> {

    private static final String BACKGROUND_TEXTURE = "guis/crafting_tree.png";
    private static final int TEXTURE_SIZE = 256;
    private static final int LEFT_BORDER = 7;
    private static final int RIGHT_BORDER = 7;
    private static final int TOP_BORDER = 25;
    private static final int BOTTOM_BORDER = 9;

    private static final BackgroundSize[] BACKGROUNDS = {
        new BackgroundSize(256, 256),
        new BackgroundSize(320, 256),
        new BackgroundSize(384, 320),
        new BackgroundSize(512, 320),
        new BackgroundSize(640, 384)
    };

    private final GuiRecipePatternCraftAmount parent;
    private final int requestedAmount;
    private final boolean craftMissingAmount;
    private final CraftingTreeWidget tree = new CraftingTreeWidget();
    private final TabButton next;
    private final TabButton refresh;
    private final TabButton back;
    private BackgroundSize background;
    private PatternGenerationTreeData displayedData;
    private PatternGenerationTreeData generationRequestData;
    private boolean awaitingGeneration = true;

    public GuiRecipePatternGenerationTree(ContainerCraftAmount container, InventoryPlayer playerInventory,
                                          GuiRecipePatternCraftAmount parent, int requestedAmount,
                                          boolean craftMissingAmount) {
        super(container, playerInventory);
        this.parent = parent;
        this.requestedAmount = requestedAmount;
        this.craftMissingAmount = craftMissingAmount;
        background = getLargestBackground(width, height);
        xSize = background.width();
        ySize = background.height();

        widgets.add("tree", tree);
        next = new TabButton(Icon.CRAFT_HAMMER, GuiText.Next.text(), this::startAe2Calculation);
        refresh = new TabButton(Icon.PATTERN_UPLOAD,
                new TextComponentTranslation("applygray.gui.generate_optimal_route_patterns"), this::generatePatterns);
        back = new TabButton(Icon.BACK, new TextComponentTranslation("applygray.gui.pattern_generation.back"),
                () -> switchToScreen(parent));
        widgets.add("applygray_pattern_generation_next", next, Point.ZERO);
        widgets.add("applygray_pattern_generation_refresh", refresh, Point.ZERO);
        widgets.add("applygray_pattern_generation_back", back, Point.ZERO);
        generationRequestData = getGenerationTreeData();
    }

    @Override
    public void initGui() {
        background = getLargestBackground(width, height);
        xSize = background.width();
        ySize = background.height();
        super.initGui();

        tree.setPosition(new Point(LEFT_BORDER, TOP_BORDER));
        tree.setSize(background.internalWidth(), background.internalHeight());
        placeButton(next, xSize - 86);
        placeButton(refresh, xSize - 58);
        placeButton(back, xSize - 30);
        invalidateExclusionZonesCache();
    }

    @Override
    protected void updateBeforeRender() {
        PatternGenerationTreeData current = getGenerationTreeData();
        if (awaitingGeneration && current != generationRequestData) {
            awaitingGeneration = false;
        }
        if (current != displayedData) {
            displayedData = current;
            tree.setRoot(current == null ? null : current.getRoot());
        }
        boolean generating = awaitingGeneration || current != null &&
                current.getStatus() == PatternGenerationTreeData.Status.GENERATING;
        refresh.enabled = !generating;
        next.enabled = !generating;
    }

    @Override
    public void drawBG(int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        drawBackgroundTexture(offsetX, offsetY, background.width(), background.height());
        fontRenderer.drawString(new TextComponentTranslation("applygray.gui.pattern_generation.title").getFormattedText(),
                offsetX + 6, offsetY + 9, 0x404040);

        PatternGenerationTreeData current = displayedData;
        PatternGenerationTreeData.Status status = awaitingGeneration ? PatternGenerationTreeData.Status.GENERATING :
                current == null ? PatternGenerationTreeData.Status.IDLE : current.getStatus();
        fontRenderer.drawString(new TextComponentTranslation("applygray.gui.pattern_generation.status." +
                status.name().toLowerCase()).getFormattedText(), offsetX + 6, offsetY + ySize - 16, 0x404040);
    }

    private void generatePatterns() {
        beginGeneration();
        ((IRecipePatternGeneration) (Object) container).applygray$generateOptimalRoutePatterns(requestedAmount);
    }

    private void beginGeneration() {
        generationRequestData = getGenerationTreeData();
        awaitingGeneration = true;
        tree.setRoot(null);
    }

    private PatternGenerationTreeData getGenerationTreeData() {
        return ((IRecipePatternGeneration) (Object) container).applygray$getRecipePatternGenerationTree();
    }

    private void startAe2Calculation() {
        container.confirm(requestedAmount, craftMissingAmount, GuiScreen.isShiftKeyDown());
    }

    private void placeButton(GuiButton button, int x) {
        button.x = guiLeft + x;
        button.y = guiTop + 3;
    }

    private static BackgroundSize getLargestBackground(int screenWidth, int screenHeight) {
        for (int index = BACKGROUNDS.length - 1; index >= 0; index--) {
            BackgroundSize candidate = BACKGROUNDS[index];
            if (screenWidth >= candidate.width() * 1.5 && screenHeight >= candidate.height() * 1.5) {
                return candidate;
            }
        }
        return BACKGROUNDS[0];
    }

    private static void drawBackgroundTexture(int x, int y, int width, int height) {
        int sourceCenterWidth = TEXTURE_SIZE - LEFT_BORDER - RIGHT_BORDER;
        int sourceCenterHeight = TEXTURE_SIZE - TOP_BORDER - BOTTOM_BORDER;
        int targetCenterWidth = width - LEFT_BORDER - RIGHT_BORDER;
        int targetCenterHeight = height - TOP_BORDER - BOTTOM_BORDER;
        int sourceRightX = TEXTURE_SIZE - RIGHT_BORDER;
        int sourceBottomY = TEXTURE_SIZE - BOTTOM_BORDER;
        int targetRightX = x + width - RIGHT_BORDER;
        int targetBottomY = y + height - BOTTOM_BORDER;

        drawPart(0, 0, LEFT_BORDER, TOP_BORDER, x, y, LEFT_BORDER, TOP_BORDER);
        drawPart(LEFT_BORDER, 0, sourceCenterWidth, TOP_BORDER, x + LEFT_BORDER, y, targetCenterWidth, TOP_BORDER);
        drawPart(sourceRightX, 0, RIGHT_BORDER, TOP_BORDER, targetRightX, y, RIGHT_BORDER, TOP_BORDER);
        drawPart(0, TOP_BORDER, LEFT_BORDER, sourceCenterHeight, x, y + TOP_BORDER, LEFT_BORDER, targetCenterHeight);
        drawPart(LEFT_BORDER, TOP_BORDER, sourceCenterWidth, sourceCenterHeight, x + LEFT_BORDER, y + TOP_BORDER,
                targetCenterWidth, targetCenterHeight);
        drawPart(sourceRightX, TOP_BORDER, RIGHT_BORDER, sourceCenterHeight, targetRightX, y + TOP_BORDER,
                RIGHT_BORDER, targetCenterHeight);
        drawPart(0, sourceBottomY, LEFT_BORDER, BOTTOM_BORDER, x, targetBottomY, LEFT_BORDER, BOTTOM_BORDER);
        drawPart(LEFT_BORDER, sourceBottomY, sourceCenterWidth, BOTTOM_BORDER, x + LEFT_BORDER, targetBottomY,
                targetCenterWidth, BOTTOM_BORDER);
        drawPart(sourceRightX, sourceBottomY, RIGHT_BORDER, BOTTOM_BORDER, targetRightX, targetBottomY,
                RIGHT_BORDER, BOTTOM_BORDER);
    }

    private static void drawPart(int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                 int targetX, int targetY, int targetWidth, int targetHeight) {
        for (int y = 0; y < targetHeight; y += sourceHeight) {
            int height = Math.min(sourceHeight, targetHeight - y);
            for (int x = 0; x < targetWidth; x += sourceWidth) {
                int width = Math.min(sourceWidth, targetWidth - x);
                Blitter.texture(BACKGROUND_TEXTURE, TEXTURE_SIZE, TEXTURE_SIZE)
                        .src(sourceX, sourceY, width, height)
                        .dest(targetX + x, targetY + y, width, height)
                        .blit();
            }
        }
    }

    private record BackgroundSize(int width, int height) {
        private int internalWidth() {
            return width - LEFT_BORDER - RIGHT_BORDER;
        }

        private int internalHeight() {
            return height - TOP_BORDER - BOTTOM_BORDER;
        }
    }
}
