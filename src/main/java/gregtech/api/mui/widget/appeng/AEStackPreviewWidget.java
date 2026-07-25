package gregtech.api.mui.widget.appeng;

import ae2.api.stacks.GenericStack;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public abstract class AEStackPreviewWidget extends Widget<AEStackPreviewWidget>
        implements RecipeViewerIngredientProvider {

    @NotNull
    protected final Supplier<GenericStack> stackToDraw;

    public AEStackPreviewWidget(@NotNull Supplier<GenericStack> stackToDraw) {
        this.stackToDraw = stackToDraw;
        tooltipAutoUpdate(true);
        tooltipBuilder(this::buildTooltip);
    }

    protected abstract void buildTooltip(@NotNull RichTooltip tooltip);

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        draw(stackToDraw.get(), 1, 1, getArea().w() - 2, getArea().h() - 2);
    }

    public abstract void draw(@Nullable GenericStack stackToDraw, int x, int y, int width, int height);
}
