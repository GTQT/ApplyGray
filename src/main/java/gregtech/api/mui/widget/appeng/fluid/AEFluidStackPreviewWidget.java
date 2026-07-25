package gregtech.api.mui.widget.appeng.fluid;

import gregtech.api.mui.widget.appeng.AEStackPreviewWidget;
import gregtech.api.util.FluidTooltipUtil;
import gregtech.api.util.KeyUtil;

import net.minecraftforge.fluids.FluidStack;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.RichTooltip;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

class AEFluidStackPreviewWidget extends AEStackPreviewWidget {

    AEFluidStackPreviewWidget(@NotNull Supplier<GenericStack> stackToDraw) {
        super(stackToDraw);
    }

    @Override
    protected void buildTooltip(@NotNull RichTooltip tooltip) {
        GenericStack stack = stackToDraw.get();
        if (stack != null && stack.what() instanceof AEFluidKey fluidKey) {
            FluidStack fluidStack = fluidKey.toStack(saturatingInt(stack.amount()));
            tooltip.addLine(KeyUtil.fluid(fluidStack));
            FluidTooltipUtil.fluidInfo(fluidStack, tooltip, false, true, false);
            tooltip.addLine(FluidTooltipUtil.getFluidModNameKey(fluidStack));
        }
    }

    @Override
    public void draw(@Nullable GenericStack stackToDraw, int x, int y, int width, int height) {
        if (stackToDraw != null && stackToDraw.what() instanceof AEFluidKey fluidKey) {
            GuiDraw.drawFluidTexture(fluidKey.toStack(saturatingInt(stackToDraw.amount())), x, y, width, height, 0);
        }
    }

    @Override
    public @Nullable Object getIngredient() {
        GenericStack stack = stackToDraw.get();
        return stack != null && stack.what() instanceof AEFluidKey fluidKey
                ? fluidKey.toStack(saturatingInt(stack.amount())) : null;
    }

    private static int saturatingInt(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, amount);
    }
}
