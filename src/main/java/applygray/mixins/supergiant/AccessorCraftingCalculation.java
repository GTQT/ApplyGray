package applygray.mixins.supergiant;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEKey;
import ae2.crafting.CraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;

/** Exposes AE2's package-private pattern lookup for a task-local candidate rewrite. */
@Mixin(value = CraftingCalculation.class, remap = false)
public interface AccessorCraftingCalculation {

    @Invoker("getCraftingFor")
    Collection<IPatternDetails> applygray$getCraftingFor(AEKey target);
}
