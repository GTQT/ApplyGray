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

    private static final int MAX_RECURSIVE_CYCLE_RECOVERY_ATTEMPTS = 16;
    // GridState and the active rule package already apply the per-target exposure cap. Keep this as a hard ceiling
    // only, so a rule can deliberately expose a bounded Pareto frontier instead of being silently reduced to one.
    private static final int MAX_DYNAMIC_PATTERNS_PER_TARGET = 8;

    @Shadow @Final
    private IGrid grid;

    @Inject(method = "getCraftingFor", at = @At("RETURN"), cancellable = true)
    private void applygray$appendLazyRecipeMapPatterns(AEKey requested,
                                                       CallbackInfoReturnable<java.util.Collection<IPatternDetails>> cir) {
        List<IPatternDetails> normalPatterns = new ArrayList<>(cir.getReturnValue().size());
        List<IPatternDetails> cachedDynamicPatterns = new ArrayList<>();
        for (IPatternDetails detail : cir.getReturnValue()) {
            if (detail instanceof DynamicRecipePatternDetails) {
                if (DynamicRecipePatternRegistry.isRegisteredPatternAvailableFor(grid, requested, detail)) {
                    cachedDynamicPatterns.add(detail);
                }
                continue;
            }
            normalPatterns.add(detail);
        }

        // CraftingCalculation builds its root node before its worker-thread context exists. Returning a dynamic
        // route here would freeze a provisional choice in AE2's per-calculation cache before an optimal rebuild
        // can refresh RecipeMap indexes. The cache is cleared when run() starts and the route is then evaluated.
        if (DynamicRecipePatternRegistry.isCraftingCalculationConstruction()) {
            cir.setReturnValue(java.util.Collections.unmodifiableList(normalPatterns));
            return;
        }

        if (DynamicRecipePatternRegistry.isNormalPatternCostLookup()) {
            cir.setReturnValue(java.util.Collections.unmodifiableList(normalPatterns));
            return;
        }

        // A regular synthesis pattern remains authoritative. A same-material form conversion such as block -> plate
        // is only a fallback and may yield to a direct RecipeMap source.
        if (!normalPatterns.isEmpty()) {
            List<IPatternDetails> preferredDynamicPatterns =
                    DynamicRecipePatternRegistry.findDynamicPatternsForMaterialFormFallback(grid, requested,
                            normalPatterns);
            if (!preferredDynamicPatterns.isEmpty()) {
                cir.setReturnValue(java.util.Collections.unmodifiableList(preferredDynamicPatterns));
                return;
            }
            cir.setReturnValue(java.util.Collections.unmodifiableList(normalPatterns));
            return;
        }

        // A cached virtual pattern is reusable only while the registry still owns it. An optimal rebuild can evict
        // a detail before AE2 refreshes its own cache, in which case a fresh RecipeMap scan is required.
        if (cachedDynamicPatterns.isEmpty()) {
            cachedDynamicPatterns.addAll(DynamicRecipePatternRegistry.findPatterns(grid, requested));
        }
        DynamicRecipePatternRegistry.sortPatternsForCrafting(grid, requested, cachedDynamicPatterns);
        if (cachedDynamicPatterns.size() > MAX_DYNAMIC_PATTERNS_PER_TARGET) {
            cachedDynamicPatterns.subList(MAX_DYNAMIC_PATTERNS_PER_TARGET, cachedDynamicPatterns.size()).clear();
        }
        cir.setReturnValue(java.util.Collections.unmodifiableList(cachedDynamicPatterns));
    }

    @Redirect(
            method = "beginCraftingCalculation",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)" +
                            "Ljava/util/concurrent/Future;"))
    private Future<ICraftingPlan> applygray$retryAfterRecursivePatternCleanup(
            ExecutorService executor, Callable<ICraftingPlan> calculation, World world,
            ICraftingSimulationRequester simRequester, AEKey what, long amount, CalculationStrategy strategy) {
        boolean optimalRebuild = DynamicRecipePatternRegistry.reserveOptimalRebuild(grid, what, amount);
        return executor.submit(() -> {
            if (optimalRebuild) {
                DynamicRecipePatternRegistry.enterOptimalRebuild(what, amount);
            }
            try {
                for (int recoveryAttempt = 0; ; recoveryAttempt++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    DynamicRecipePatternRegistry.clearRecursiveCycleRecovery();
                    try {
                        ICraftingPlan plan = recoveryAttempt == 0 ? calculation.call() :
                                new CraftingCalculation(world, grid, simRequester,
                                        new GenericStack(what, amount), strategy).run();
                        DynamicRecipePatternRegistry.recordOptimalRebuildPlan(plan);
                        if (recoveryAttempt > 0) {
                            ApplyGrayMod.LOGGER.info("Recovered lazy RecipeMap crafting calculation for {} after {} " +
                                    "recursive cleanup attempt(s)", what, recoveryAttempt);
                        }
                        return plan;
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
                        ApplyGrayMod.LOGGER.debug("Retrying lazy RecipeMap crafting calculation for {} after " +
                                        "recursive pattern cleanup ({}/{})", what, recoveryAttempt + 1,
                                MAX_RECURSIVE_CYCLE_RECOVERY_ATTEMPTS);
                    }
                }
            } finally {
                DynamicRecipePatternRegistry.clearRecursiveCycleRecovery();
                DynamicRecipePatternRegistry.finishCraftingCalculationSession();
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
