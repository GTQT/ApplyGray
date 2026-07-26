package gregtech.api.mui.widget.appeng.item;

import gregtech.api.mui.GTGuiTextures;
import gregtech.api.mui.sync.appeng.AEItemSyncHandler;
import gregtech.api.mui.widget.appeng.AEConfigSlot;
import gregtech.api.mui.widget.appeng.AEStackPreviewWidget;
import gregtech.api.util.TextFormattingUtil;
import gregtech.client.utils.RenderUtil;

import net.minecraft.item.ItemStack;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import codechicken.lib.gui.GuiDraw;
import com.cleanroommc.modularui.api.value.ISyncOrValue;
import com.cleanroommc.modularui.drawable.text.TextRenderer;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerGhostIngredientSlot;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

public class AEItemConfigSlot extends AEConfigSlot implements RecipeViewerGhostIngredientSlot<ItemStack> {

    public AEItemConfigSlot(boolean isStocking, int index, @NotNull BooleanSupplier isAutoPull) {
        super(isStocking, index, isAutoPull);
        tooltipAutoUpdate(true);
    }

    @Override
    public void onInit() {
        super.onInit();
        getContext().getRecipeViewerSettings().addGhostIngredientSlot(this);
    }

    @Override
    protected void buildTooltip(@NotNull RichTooltip tooltip) {
        GenericStack config = getSyncHandler().getConfig(index);
        if (config != null && config.what() instanceof AEItemKey itemKey) {
            tooltip.addFromItem(itemKey.getReadOnlyStack());
            tooltip.addLine((context, x, y, width, height, widgetTheme) -> {
                int color = Color.GREY.darker(2);
                GuiDraw.drawRect(x, y + 3, (int) TextRenderer.SHARED.getLastActualWidth(), 2, color);
            });
        }

        super.buildTooltip(tooltip);
    }

    @Override
    public @NotNull AEItemSyncHandler getSyncHandler() {
        return (AEItemSyncHandler) super.getSyncHandler();
    }

    @Override
    public boolean isValidSyncOrValue(@NotNull ISyncOrValue syncOrValue) {
        return syncOrValue.isTypeOrEmpty(AEItemSyncHandler.class);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        GenericStack config = getSyncHandler().getConfig(index);
        if (config != null && config.what() instanceof AEItemKey itemKey) {
            RenderUtil.drawItemStack(itemKey.getReadOnlyStack(), 1, 1, false);
            if (!isStocking) {
                RenderUtil.renderTextFixedCorner(TextFormattingUtil.formatLongToCompactString(config.amount(), 4),
                        17d, 18d, 0xFFFFFF, true, 0.5f);
            }
        }

        RenderUtil.handleJEIGhostSlotOverlay(this, widgetTheme);
    }

    @Override
    public @NotNull Result onMousePressed(int mouseButton) {
        if (isAutoPull.getAsBoolean()) {
            return Result.IGNORE;
        }

        if (mouseButton == 0) {
            ItemStack heldItem = getSyncHandler().getSyncManager().getCursorItem();
            if (!heldItem.isEmpty()) {
                getSyncHandler().setConfig(index, heldItem);
                return Result.SUCCESS;
            }
        }

        return super.onMousePressed(mouseButton);
    }

    @Override
    public void setGhostIngredient(@NotNull ItemStack ingredient) {
        getSyncHandler().setConfig(index, ingredient);
    }

    @Override
    public @Nullable ItemStack castGhostIngredientIfValid(@NotNull Object ingredient) {
        return !isAutoPull.getAsBoolean() && ingredient instanceof ItemStack stack ? stack : null;
    }

    @Override
    public @Nullable Object getIngredient() {
        GenericStack config = getSyncHandler().getConfig(index);
        return config != null && config.what() instanceof AEItemKey itemKey
                ? itemKey.toStack(saturatingInt(config.amount())) : null;
    }

    @Override
    protected @NotNull AEStackPreviewWidget createPopupDrawable() {
        return new AEItemStackPreviewWidget(() -> getSyncHandler().getConfig(index))
                .background(GTGuiTextures.SLOT);
    }

    private static int saturatingInt(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, amount);
    }
}
