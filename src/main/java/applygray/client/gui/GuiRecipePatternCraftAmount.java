package applygray.client.gui;

import applygray.integration.ae2.IRecipePatternGeneration;

import ae2.client.Point;
import ae2.client.gui.Icon;
import ae2.client.gui.me.crafting.GuiCraftAmount;
import ae2.client.gui.style.GuiStyle;
import ae2.client.gui.widgets.TabButton;
import ae2.client.gui.widgets.NumberEntryWidget;
import ae2.container.implementations.ContainerCraftAmount;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextComponentTranslation;

/** Quantity dialog with an independent entry point for RecipeMap pattern generation. */
public class GuiRecipePatternCraftAmount extends GuiCraftAmount {

    public GuiRecipePatternCraftAmount(ContainerCraftAmount container, InventoryPlayer playerInventory,
                                       GuiStyle style) {
        super(container, playerInventory, style);
        getWidgets().add("applygray_generate_recipe_map_patterns",
                new TabButton(Icon.PATTERN_UPLOAD,
                        new TextComponentTranslation("applygray.gui.generate_optimal_route_patterns"),
                        this::openPatternGenerationTree),
                new Point(108, -5));
    }

    private void openPatternGenerationTree() {
        NumberEntryWidget numberEntry = (NumberEntryWidget) getWidgets().getComposite("amountToCraft");
        int amount = numberEntry == null ? getContainer().getInitialAmount() :
                numberEntry.getIntValue().orElse(getContainer().getInitialAmount());
        boolean craftMissingAmount = numberEntry != null && numberEntry.startsWithEquals();
        if (amount <= 0) return;

        ((IRecipePatternGeneration) (Object) getContainer()).applygray$generateOptimalRoutePatterns(amount);
        switchToScreen(new GuiRecipePatternGenerationTree(getContainer(), playerInventory, this, amount,
                craftMissingAmount));
    }
}
