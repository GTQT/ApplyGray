package gregtech.api.mui.widget.appeng.item;

import gregtech.api.mui.sync.appeng.AEItemSyncHandler;
import gregtech.api.mui.widget.appeng.AEDisplaySlot;
import gregtech.api.util.TextFormattingUtil;
import gregtech.client.utils.RenderUtil;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AEItemDisplaySlot extends AEDisplaySlot {

    public AEItemDisplaySlot(int index) {
        super(index);
        tooltipAutoUpdate(true);
    }

    @Override
    protected void buildTooltip(@NotNull RichTooltip tooltip) {
        GenericStack stock = getSyncHandler().getStock(index);
        if (stock != null && stock.what() instanceof AEItemKey itemKey) {
            tooltip.addFromItem(itemKey.getReadOnlyStack());
        }
    }

    @Override
    public @NotNull AEItemSyncHandler getSyncHandler() {
        return (AEItemSyncHandler) super.getSyncHandler();
    }

    @Override
    public boolean isValidSyncHandler(SyncHandler syncHandler) {
        return syncHandler instanceof AEItemSyncHandler;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        GenericStack stock = getSyncHandler().getStock(index);
        if (stock != null && stock.what() instanceof AEItemKey itemKey) {
            RenderUtil.drawItemStack(itemKey.getReadOnlyStack(), 1, 1, false);
            RenderUtil.renderTextFixedCorner(TextFormattingUtil.formatLongToCompactString(stock.amount(), 4), 17d,
                    18d, 0xFFFFFF, true, 0.5f);
        }

        RenderUtil.handleSlotOverlay(this, widgetTheme);
    }

    @Override
    public @Nullable Object getIngredient() {
        GenericStack stock = getSyncHandler().getStock(index);
        return stock != null && stock.what() instanceof AEItemKey itemKey
                ? itemKey.toStack(saturatingInt(stock.amount())) : null;
    }

    private static int saturatingInt(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, amount);
    }
}
