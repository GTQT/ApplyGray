package applygray.mixins.supergiant;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternDetails;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IGrid;
import ae2.api.networking.crafting.CalculationStrategy;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.crafting.ICraftingSimulationRequester;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.crafting.CraftingCalculation;
import ae2.me.service.CraftingService;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Lazily exposes RecipeMap patterns and retries after removing a non-productive recursive pattern chain. */
@Mixin(value = CraftingService.class, remap = false)
public abstract class MixinCraftingGridCacheLazyRecipeMap {

    private static final int MAX_RECURSIVE_CYCLE_RECOVERY_ATTEMPTS = 4;

    @Shadow @Final
    private IGrid grid;

    @Inject(method = "getCraftingFor", at = @At("RETURN"), cancellable = true)
    private void applygray$appendLazyRecipeMapPatterns(AEKey requested,
                                                       CallbackInfoReturnable<java.util.Collection<IPatternDetails>> cir) {
        List<IPatternDetails> dynamic = DynamicRecipePatternRegistry.findPatterns(grid, requested);
        List<IPatternDetails> merged = new ArrayList<>(cir.getReturnValue().size() + dynamic.size());
        boolean changed = false;
        for (IPatternDetails detail : cir.getReturnValue()) {
            // Cached virtual patterns remain mounted for terminal visibility, but only the bounded lookup for the
            // current requested output may participate in this calculation.
            if (detail instanceof DynamicRecipePatternDetails) {
                changed = true;
                continue;
            }
            merged.add(detail);
        }
        for (IPatternDetails detail : dynamic) {
            if (DynamicRecipePatternRegistry.isPatternAvailableFor(requested, detail) &&
                    !merged.contains(detail)) {
                merged.add(detail);
                changed = true;
            }
        }
        if (!changed && dynamic.isEmpty()) return;

        merged.sort((left, right) -> {
            boolean leftDynamic = left instanceof DynamicRecipePatternDetails;
            boolean rightDynamic = right instanceof DynamicRecipePatternDetails;
            if (leftDynamic && rightDynamic) {
                DynamicRecipePatternDetails l = (DynamicRecipePatternDetails) left;
                DynamicRecipePatternDetails r = (DynamicRecipePatternDetails) right;
                return DynamicRecipePatternRegistry.compareDynamicPatternPriority(requested, l, r);
            }
            if (leftDynamic) return -1;
            if (rightDynamic) return 1;
            return 0;
        });
        cir.setReturnValue(java.util.Collections.unmodifiableList(merged));
    }

    @Redirect(
            method = "beginCraftingCalculation",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)" +
                            "Ljava/util/concurrent/Future;"))
    private Future<ICraftingPlan> applygray$retryAfterRecursivePatternCleanup(
            ExecutorService executor, Callable<ICraftingPlan> calculation, World world,
            ICraftingSimulationRequester simRequester, AEKey what, long amount, CalculationStrategy strategy) {
        return executor.submit(() -> {
            try {
                for (int recoveryAttempt = 0; ; recoveryAttempt++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    DynamicRecipePatternRegistry.clearRecursiveCycleRecovery();
                    try {
                        if (recoveryAttempt == 0) {
                            return calculation.call();
                        }
                        return new CraftingCalculation(world, grid, simRequester, new GenericStack(what, amount),
                                strategy).run();
                    } catch (RuntimeException failure) {
                        if (wasCancelled(failure)) {
                            Thread.currentThread().interrupt();
                            throw failure;
                        }
                        boolean cleanedRecursivePatterns =
                                DynamicRecipePatternRegistry.consumeRecursiveCycleRecovery();
                        if (!cleanedRecursivePatterns) {
                            throw failure;
                        }
                        if (recoveryAttempt >= MAX_RECURSIVE_CYCLE_RECOVERY_ATTEMPTS) {
                            ApplyGrayMod.LOGGER.warn("Stopped recursive lazy RecipeMap recovery for {} after {} " +
                                            "retries", what, MAX_RECURSIVE_CYCLE_RECOVERY_ATTEMPTS);
                            throw failure;
                        }
                        ApplyGrayMod.LOGGER.info("Retrying lazy RecipeMap crafting calculation for {} after " +
                                        "recursive pattern cleanup ({}/{})", what, recoveryAttempt + 1,
                                MAX_RECURSIVE_CYCLE_RECOVERY_ATTEMPTS);
                    }
                }
            } finally {
                DynamicRecipePatternRegistry.clearRecursiveCycleRecovery();
            }
        });
    }

    private static boolean wasCancelled(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "getProviders", at = @At("RETURN"), cancellable = true)
    private void applygray$getLazyRecipeMapProvider(IPatternDetails details,
                                                    CallbackInfoReturnable<Iterable<ICraftingProvider>> cir) {
        ICraftingProvider provider = DynamicRecipePatternRegistry.getProvider(details);
        if (provider != null) cir.setReturnValue(List.of(provider));
    }
}
