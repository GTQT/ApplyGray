package applygray.mixins.supergiantfc;

import gregtech.integration.ae2.GTCircuitHelper;
import gregtech.integration.jei.utils.render.ItemStackTextRenderer;
import gregtech.mixins.jei.GuiIngredientAccessor;

import net.minecraft.item.ItemStack;

import ae2.api.stacks.GenericStack;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.integration.modules.hei.GenericIngredientHelper;
import ae2.integration.modules.hei.PatternEncodingRecipeTransferHandler;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.VanillaTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replaces non-consumed GT item inputs with programmable circuits before Supergiant
 * serializes HEI processing-pattern inputs.
 */
@Mixin(value = PatternEncodingRecipeTransferHandler.class, remap = false)
public abstract class MixinRecipeTransferBuilder {

    @Redirect(
            method = "encodeProcessingRecipe",
            at = @At(value = "INVOKE", target =
                    "Lae2/integration/modules/hei/PatternEncodingRecipeTransferHandler;getGenericInputs(Lmezz/jei/api/gui/IRecipeLayout;)Ljava/util/List;"),
            remap = false)
    private static List<List<GenericStack>> applygray$encodeNonConsumedInputs(IRecipeLayout recipeLayout) {
        List<List<GenericStack>> inputs = new ArrayList<>(GenericIngredientHelper.getIngredients(recipeLayout, true,
                false, AEProcessingPattern.MAX_INPUT_SLOTS));
        if (!GTCircuitHelper.isPatternTransferEnabled()) {
            return inputs;
        }

        IGuiIngredientGroup<ItemStack> itemGroup = recipeLayout.getIngredientsGroup(VanillaTypes.ITEM);
        if (itemGroup == null) {
            return inputs;
        }

        Map<Integer, ? extends IGuiIngredient<ItemStack>> ingredients = itemGroup.getGuiIngredients();
        if (ingredients == null || ingredients.isEmpty()) {
            return inputs;
        }

        int inputSlot = 0;
        for (IGuiIngredient<ItemStack> ingredient : ingredients.values()) {
            if (ingredient == null || !ingredient.isInput()) {
                continue;
            }

            if (applygray$isNotConsumed(ingredient)) {
                List<GenericStack> circuits = applygray$wrapCircuitAlternatives(ingredient);
                if (!circuits.isEmpty()) {
                    while (inputs.size() <= inputSlot) {
                        inputs.add(new ArrayList<>());
                    }
                    inputs.set(inputSlot, circuits);
                }
            }
            inputSlot++;
        }
        return inputs;
    }

    private static boolean applygray$isNotConsumed(IGuiIngredient<ItemStack> ingredient) {
        if (!(ingredient instanceof GuiIngredientAccessor<?> accessor)) {
            return false;
        }
        IIngredientRenderer<?> renderer = accessor.getIngredientRenderer();
        return renderer instanceof ItemStackTextRenderer textRenderer && textRenderer.isNotConsumed();
    }

    private static List<GenericStack> applygray$wrapCircuitAlternatives(IGuiIngredient<ItemStack> ingredient) {
        List<GenericStack> circuits = new ArrayList<>();
        List<ItemStack> alternatives = ingredient.getAllIngredients();
        if (alternatives == null || alternatives.isEmpty()) {
            ItemStack displayed = ingredient.getDisplayedIngredient();
            alternatives = displayed == null ? List.of() : List.of(displayed);
        }

        for (ItemStack alternative : alternatives) {
            if (alternative == null || alternative.isEmpty()) {
                continue;
            }
            ItemStack circuit = GTCircuitHelper.isProgrammableCircuit(alternative) ? alternative.copy()
                    : GTCircuitHelper.wrapItemAsProgrammableStack(alternative);
            GenericStack genericCircuit = circuit == null ? null : GenericStack.fromItemStack(circuit);
            if (genericCircuit != null && !circuits.contains(genericCircuit)) {
                circuits.add(genericCircuit);
            }
        }
        return circuits;
    }
}
