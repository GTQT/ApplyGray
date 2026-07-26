package applygray.mixins.supergiant;

import ae2.crafting.CraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets lazy RecipeMap lookup cooperate with Supergiant's per-tick crafting calculation budget.
 */
@Mixin(value = CraftingCalculation.class, remap = false)
public interface InvokerCraftingCalculation {

    @Invoker("handlePausing")
    void applygray$handlePausing() throws InterruptedException;
}
