package applygray.integration.jei;

import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import ae2.integration.modules.hei.PatternEncodingRecipeTransferHandler;

import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;

import net.minecraft.entity.player.EntityPlayer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Assembly line recipes may repeat the same item (motors, circuits, ...), so pattern import has to keep them in
 * separate slots and rename them; this wrapper turns that on before AE2 plans the transfer.
 */
public final class HeiAssemblyLineRecipeTransferHandler<C extends ContainerPatternEncodingTerm>
    implements IRecipeTransferHandler<C> {

    private final Class<C> containerClass;
    private final PatternEncodingRecipeTransferHandler<C> delegate;

    public HeiAssemblyLineRecipeTransferHandler(Class<C> containerClass) {
        this.containerClass = containerClass;
        this.delegate = new PatternEncodingRecipeTransferHandler<>(containerClass);
    }

    @Override
    @NotNull
    public Class<C> getContainerClass() {
        return this.containerClass;
    }

    @Override
    @Nullable
    public IRecipeTransferError transferRecipe(@NotNull C container, @NotNull IRecipeLayout recipeLayout,
                                               @NotNull EntityPlayer player, boolean maxTransfer,
                                               boolean doTransfer) {
        if (doTransfer && container instanceof AutoPatternItemNaming naming) {
            naming.applygray$setAutoNamePatternItems(true);
            container.setMergeHeiProcessingInputs(false);
        }
        return this.delegate.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
    }
}
