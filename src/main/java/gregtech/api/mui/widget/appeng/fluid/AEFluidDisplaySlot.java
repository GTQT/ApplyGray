package gregtech.api.mui.widget.appeng.fluid;

import gregtech.api.mui.sync.appeng.AEFluidSyncHandler;
import gregtech.api.mui.widget.appeng.AEDisplaySlot;
import gregtech.api.util.FluidTooltipUtil;
import gregtech.api.util.KeyUtil;
import gregtech.api.util.TextFormattingUtil;
import gregtech.client.utils.RenderUtil;

import net.minecraftforge.fluids.FluidStack;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.api.value.ISyncOrValue;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AEFluidDisplaySlot extends AEDisplaySlot {

    public AEFluidDisplaySlot(int index) {
        super(index);
        tooltipAutoUpdate(true);
    }

    @Override
    protected void buildTooltip(@NotNull RichTooltip tooltip) {
        GenericStack stock = getSyncHandler().getStock(index);
        if (stock != null && stock.what() instanceof AEFluidKey fluidKey) {
            FluidStack stack = fluidKey.toStack(saturatingInt(stock.amount()));
            tooltip.addLine(KeyUtil.fluid(stack));
            FluidTooltipUtil.fluidInfo(stack, tooltip, false, true, true);
            tooltip.addLine(FluidTooltipUtil.getFluidModNameKey(stack));
        }
    }

    @Override
    public @NotNull AEFluidSyncHandler getSyncHandler() {
        return (AEFluidSyncHandler) super.getSyncHandler();
    }

    @Override
    public boolean isValidSyncOrValue(@NotNull ISyncOrValue syncOrValue) {
        return syncOrValue.isTypeOrEmpty(AEFluidSyncHandler.class);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        GenericStack stock = getSyncHandler().getStock(index);
        if (stock != null && stock.what() instanceof AEFluidKey fluidKey) {
            GuiDraw.drawFluidTexture(fluidKey.toStack(saturatingInt(stock.amount())), 1, 1, getArea().w() - 2,
                    getArea().h() - 2, 0);
            RenderUtil.renderTextFixedCorner(TextFormattingUtil.formatLongToCompactString(stock.amount(), 4), 17d,
                    18d, 0xFFFFFF, true, 0.5f);
        }

        RenderUtil.handleSlotOverlay(this, widgetTheme);
    }

    @Override
    public @Nullable Object getIngredient() {
        GenericStack stock = getSyncHandler().getStock(index);
        return stock != null && stock.what() instanceof AEFluidKey fluidKey
                ? fluidKey.toStack(saturatingInt(stock.amount())) : null;
    }

    private static int saturatingInt(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, amount);
    }
}
