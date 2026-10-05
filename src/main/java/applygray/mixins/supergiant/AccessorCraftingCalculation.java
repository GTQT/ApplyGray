package applygray.mixins.supergiant;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEKey;
import ae2.crafting.CraftingCalculation;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes AE2's package-private pattern lookup for a task-local candidate rewrite. */
@Mixin(value = CraftingCalculation.class, remap = false)
public interface AccessorCraftingCalculation {

    @Invoker("getCraftingFor")
    ObjectList<IPatternDetails> applygray$getCraftingFor(AEKey target);
}
