package applygray.mixins.supergiant;

import ae2.crafting.CraftBranchFailure;
import ae2.crafting.CraftingTreeProcess;
import ae2.crafting.inv.CraftingSimulationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes package-private process operations so a redirected call can retain AE2's original behavior. */
@Mixin(value = CraftingTreeProcess.class, remap = false)
public interface AccessorCraftingTreeProcess {

    @Invoker("getMaximumCraftableTimes")
    long applygray$getMaximumCraftableTimes(CraftingSimulationState inventory, long requestedPatternRuns)
            throws InterruptedException;

    @Invoker("request")
    void applygray$request(CraftingSimulationState inventory, long requestedPatternRuns)
            throws CraftBranchFailure, InterruptedException;
}
