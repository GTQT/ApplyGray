package applygray.mixins.ae2;

import ae2.api.crafting.IPatternDetails;
import ae2.crafting.execution.ExecutingCraftingJob;
import ae2.crafting.inv.ListCraftingInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface AccessorExecutingCraftingJob {

    @Accessor("waitingFor")
    ListCraftingInventory applygray$getWaitingFor();

    @Accessor("tasks")
    Map<IPatternDetails, ?> applygray$getTasks();
}
