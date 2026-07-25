package gregtech.api.mui.widget.appeng.item;

import gregtech.api.mui.widget.appeng.AEStackPreviewWidget;
import gregtech.client.utils.RenderUtil;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.screen.RichTooltip;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

class AEItemStackPreviewWidget extends AEStackPreviewWidget {

    AEItemStackPreviewWidget(@NotNull Supplier<GenericStack> stackToDraw) {
        super(stackToDraw);
    }

    @Override
    protected void buildTooltip(@NotNull RichTooltip tooltip) {
        GenericStack stack = stackToDraw.get();
        if (stack != null && stack.what() instanceof AEItemKey itemKey) {
            tooltip.addFromItem(itemKey.getReadOnlyStack());
        }
    }

    @Override
    public void draw(@Nullable GenericStack stackToDraw, int x, int y, int width, int height) {
        if (stackToDraw != null && stackToDraw.what() instanceof AEItemKey itemKey) {
            RenderUtil.drawItemStack(itemKey.getReadOnlyStack(), x, y, false);
        }
    }

    @Override
    public @Nullable Object getIngredient() {
        GenericStack stack = stackToDraw.get();
        return stack != null && stack.what() instanceof AEItemKey itemKey ? itemKey.toStack() : null;
    }
}
