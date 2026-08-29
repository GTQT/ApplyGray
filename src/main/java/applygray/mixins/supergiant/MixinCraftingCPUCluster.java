package applygray.mixins.supergiant;

import applygray.common.items.ApplyGrayMetaItems;

import ae2.api.networking.energy.IEnergyService;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.me.cluster.implementations.CraftingCPUCluster;
import ae2.crafting.execution.CraftingCpuLogic;
import ae2.crafting.execution.ExecutingCraftingJob;
import ae2.me.service.CraftingService;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes the special ApplyGray order-token jobs in Supergiant's CPU logic. */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class MixinCraftingCPUCluster {

    @Shadow @Final private CraftingCPUCluster cluster;
    @Shadow private ExecutingCraftingJob job;

    @Shadow
    private void finishJob(boolean success) {
        throw new AssertionError();
    }

    @Inject(method = "tickCraftingLogic", at = @At("RETURN"))
    private void applygray$finishOrderOnlyJob(IEnergyService energyService, CraftingService craftingService,
                                              CallbackInfo ci) {
        if (job == null) return;

        AccessorExecutingCraftingJob accessor = (AccessorExecutingCraftingJob) (Object) job;
        if (!accessor.applygray$getTasks().isEmpty()) return;

        var waitingFor = accessor.applygray$getWaitingFor().list;
        if (waitingFor.size() != 1) return;

        var only = waitingFor.getFirstEntry();
        if (only == null || !applygray$isOrderToken(only.getKey())) return;

        finishJob(true);
        cluster.updateOutput(null);
    }

    /**
     * Order tokens carry per-request names and discriminators, so they are matched by item and metadata only; the tag
     * is what distinguishes one plan's token from another's and must be ignored here.
     */
    private static boolean applygray$isOrderToken(AEKey key) {
        if (!(key instanceof AEItemKey itemKey)) return false;
        ItemStack order = ApplyGrayMetaItems.ORDER.getStackForm();
        return itemKey.getItem() == order.getItem() &&
                itemKey.getReadOnlyStack().getItemDamage() == order.getItemDamage();
    }
}
