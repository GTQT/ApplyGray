
package gregtech.api.mui.widget.appeng;

import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.widget.Widget;
import org.jetbrains.annotations.NotNull;

public abstract class AEDisplaySlot extends Widget<AEDisplaySlot>
        implements RecipeViewerIngredientProvider {

    protected final int index;

    public AEDisplaySlot(int index) {
        this.index = index;
        size(18);
        tooltipBuilder(this::buildTooltip);
    }

    protected abstract void buildTooltip(@NotNull RichTooltip tooltip);
}
