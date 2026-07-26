package applygray.mixins.supergiant;

import applygray.integration.ae2.RecipeMapPatternAccessActions;
import applygray.integration.ae2.RecipeMapPatternAccessDisplay;

import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.client.gui.Icon;
import ae2.client.gui.me.patternaccess.AbstractPatternAccessTerm;
import ae2.client.gui.widgets.SmallSquareButtonRenderer;

import net.minecraft.util.text.TextComponentTranslation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Draws a clear button for RecipeMap providers, which intentionally have no physical pattern slots. */
@Mixin(value = AbstractPatternAccessTerm.class, remap = false)
public abstract class MixinAbstractPatternAccessTermRecipeMapClear {

    private static final int GUI_PADDING_X = 17;
    private static final int GUI_HEADER_HEIGHT = 30;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_ACTION_BUTTON_WIDTH = 12;
    private static final int ROW_ACTION_BUTTON_HEIGHT = 12;
    private static final int ROW_ACTION_BUTTON_X = -14;
    private static final int ROW_ACTION_BUTTON_Y_OFFSET = 3;

    @Unique
    private final List<ClearButton> applygray$clearButtons = new ArrayList<>();
    @Unique
    private int applygray$localMouseX;
    @Unique
    private int applygray$localMouseY;
    @Unique
    private int applygray$guiLeft;
    @Unique
    private int applygray$guiTop;

    @Accessor("patternAccessDisplay")
    protected abstract Object applygray$getPatternAccessDisplay();

    @Inject(method = "drawFG", at = @At("HEAD"))
    private void applygray$beginRecipeMapClearButtons(int offsetX, int offsetY, int mouseX, int mouseY,
                                                      CallbackInfo ci) {
        applygray$clearButtons.clear();
        applygray$localMouseX = mouseX;
        applygray$localMouseY = mouseY;
        applygray$guiLeft = offsetX;
        applygray$guiTop = offsetY;
    }

    @Inject(method = "drawGroupHeader", at = @At("TAIL"))
    private void applygray$drawRecipeMapClearButton(PatternContainerGroup group, int rowIndex, CallbackInfo ci) {
        Object display = applygray$getPatternAccessDisplay();
        if (!(display instanceof RecipeMapPatternAccessDisplay recipeMapDisplay)) {
            return;
        }

        long inventoryId = recipeMapDisplay.applygray$getRecipeMapPatternProviderId(group);
        if (inventoryId == RecipeMapPatternAccessDisplay.NO_PROVIDER) {
            return;
        }

        int x = GUI_PADDING_X + ROW_ACTION_BUTTON_X;
        int y = GUI_HEADER_HEIGHT + rowIndex * ROW_HEIGHT + ROW_ACTION_BUTTON_Y_OFFSET;
        boolean hovered = applygray$localMouseX >= x && applygray$localMouseY >= y
                && applygray$localMouseX < x + ROW_ACTION_BUTTON_WIDTH
                && applygray$localMouseY < y + ROW_ACTION_BUTTON_HEIGHT;
        SmallSquareButtonRenderer.drawBackground(x, y, ROW_ACTION_BUTTON_WIDTH, ROW_ACTION_BUTTON_HEIGHT, hovered);
        SmallSquareButtonRenderer.drawIcon(x, y, ROW_ACTION_BUTTON_WIDTH, ROW_ACTION_BUTTON_HEIGHT, Icon.CLEAR, 0);
        applygray$clearButtons.add(new ClearButton(inventoryId, x, y));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void applygray$clearRecipeMapPatterns(int mouseX, int mouseY, int mouseButton, CallbackInfo ci)
            throws IOException {
        if (mouseButton != 0) {
            return;
        }

        int localMouseX = mouseX - applygray$guiLeft;
        int localMouseY = mouseY - applygray$guiTop;
        for (ClearButton button : applygray$clearButtons) {
            if (button.contains(localMouseX, localMouseY)) {
                RecipeMapPatternAccessActions.send(((InvokerAEBaseGui) (Object) this).applygray$getContainer(),
                        button.inventoryId());
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "renderHoveredToolTip", at = @At("HEAD"), cancellable = true)
    private void applygray$renderRecipeMapClearTooltip(int mouseX, int mouseY, CallbackInfo ci) {
        int localMouseX = mouseX - applygray$guiLeft;
        int localMouseY = mouseY - applygray$guiTop;
        for (ClearButton button : applygray$clearButtons) {
            if (button.contains(localMouseX, localMouseY)) {
                ((InvokerAEBaseGui) (Object) this).applygray$drawTooltipLines(mouseX, mouseY,
                        Collections.singletonList(new TextComponentTranslation(
                                "applygray.gui.pattern_access.clear_dynamic_patterns").getFormattedText()));
                ci.cancel();
                return;
            }
        }
    }

    @Unique
    private record ClearButton(long inventoryId, int x, int y) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseY >= y
                    && mouseX < x + ROW_ACTION_BUTTON_WIDTH && mouseY < y + ROW_ACTION_BUTTON_HEIGHT;
        }
    }
}
