package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.CraftBranchFailure;
import ae2.crafting.CraftingCalculation;
import ae2.crafting.CraftingTreeNode;
import ae2.crafting.CraftingTreeProcess;
import ae2.crafting.inv.CraftingSimulationState;
import com.google.common.math.LongMath;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/** Inserts bounded large RecipeMap candidates before AE2 expands their input branches. */
@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class MixinCraftingTreeNodeLargePattern {

    @Shadow @Final private AEKey what;
    @Shadow @Final private long amount;

    /**
     * This runs after AE2 has removed directly available items from the node request. The remaining amount is the
     * only amount the crafting tree must supply. The candidate redirect below therefore scales only material which
     * AE2 did not already satisfy from network stock.
     */
    @Inject(method = "requestInner", at = @At(value = "INVOKE",
            target = "Lae2/crafting/CraftingTreeNode;buildChildPatterns()V", shift = At.Shift.BEFORE))
    private void applygray$beginLargePatternSelection(CraftingSimulationState inventory, long requestedAmount,
                                                       KeyCounter containerItems, CallbackInfo ci) {
        DynamicRecipePatternRegistry.beginLargePatternSelection(this, what,
                LongMath.saturatedMultiply(amount, requestedAmount));
    }

    /**
     * AE2 normally initializes child patterns while it previews available crafting before {@code requestInner()}.
     * Start selection at that earlier call as well, using only the demand left after the preview consumed network
     * stock. Otherwise the ordinary candidate list becomes permanent before the later request-side hook runs.
     */
    @Inject(method = "extractAvailableForCraftingInner", at = @At(value = "INVOKE",
            target = "Lae2/crafting/CraftingTreeNode;buildChildPatterns()V", shift = At.Shift.BEFORE),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void applygray$beginLargePatternSelectionDuringAvailability(CraftingSimulationState inventory,
                                                                         long maximumAmount,
                                                                         CallbackInfoReturnable<Long> cir,
                                                                         long available) {
        long remainingAmount = maximumAmount > available ? maximumAmount - available : 0;
        DynamicRecipePatternRegistry.beginLargePatternSelection(this, what,
                LongMath.saturatedMultiply(amount, remainingAmount));
    }

    /** Prevents an interrupted previous build on the shared planner worker from affecting another tree node. */
    @Inject(method = "buildChildPatterns", at = @At("HEAD"))
    private void applygray$clearStaleLargePatternSelection(CallbackInfo ci) {
        DynamicRecipePatternRegistry.clearStaleLargePatternSelection(this);
    }

    /**
     * Replace candidates before {@link CraftingTreeProcess} construction, so its scaled inputs become child nodes.
     * An eligible ordinary dynamic candidate is replaced rather than retained beside its large variant, because AE2
     * recursively preflights every candidate it receives.
     */
    @Redirect(method = "buildChildPatterns", at = @At(value = "INVOKE",
            target = "Lae2/crafting/CraftingCalculation;getCraftingFor(Lae2/api/stacks/AEKey;)Ljava/util/Collection;"))
    private Collection<IPatternDetails> applygray$expandLargePatternCandidates(CraftingCalculation calculation,
                                                                                 AEKey target) {
        return DynamicRecipePatternRegistry.expandLargePatternCandidatesForCurrentSelection(this, target,
                ((AccessorCraftingCalculation) calculation).applygray$getCraftingFor(target));
    }

    @Inject(method = "buildChildPatterns", at = @At("RETURN"))
    private void applygray$finishLargePatternSelection(CallbackInfo ci) {
        DynamicRecipePatternRegistry.finishLargePatternSelection(this);
    }

    /**
     * A one-run temporary batch is an atomic availability question: its actual request already runs in AE2's child
     * simulation state, which is applied only after output was produced. Avoid the generic maximum-craftable preview
     * rebuilding the same recursive tree immediately before that request. Multi-run batches retain AE2's normal
     * partial-availability behavior.
     */
    @Inject(method = "lambda$requestCraftingBranch$0", at = @At("HEAD"), cancellable = true)
    private static void applygray$useAtomicLargePatternAttempt(CraftingTreeProcess process,
                                                                 CraftingSimulationState inventory,
                                                                 long requestedPatternRuns,
                                                                 CallbackInfoReturnable<Long> cir) {
        if (!DynamicRecipePatternRegistry.canBypassLargePatternMaximumCraftablePreview(
                process.getDetails(), requestedPatternRuns)) {
            return;
        }

        DynamicRecipePatternRegistry.recordLargePatternMaximumCraftablePreviewBypass();
        cir.setReturnValue(requestedPatternRuns);
    }

    /** Times only the generic AE2 preview calls that remain after the one-run large-pattern bypass. */
    @Redirect(method = "lambda$requestCraftingBranch$0", at = @At(value = "INVOKE",
            target = "Lae2/crafting/CraftingTreeProcess;getMaximumCraftableTimes(Lae2/crafting/inv/CraftingSimulationState;J)J"))
    private static long applygray$timeMaximumCraftablePreview(CraftingTreeProcess process,
                                                                CraftingSimulationState inventory,
                                                                long requestedPatternRuns)
            throws InterruptedException {
        long startedAtNanos = System.nanoTime();
        try {
            return ((AccessorCraftingTreeProcess) process).applygray$getMaximumCraftableTimes(inventory,
                    requestedPatternRuns);
        } finally {
            DynamicRecipePatternRegistry.recordMaximumCraftablePreview(process.getDetails(),
                    System.nanoTime() - startedAtNanos);
        }
    }

    /** Attributes process-request work to the direct process rather than all of its recursive descendants. */
    @Redirect(method = { "lambda$requestCraftingBranch$1", "lambda$requestMissingBranch$0",
            "lambda$extractAvailableForCraftingInner$1" }, at = @At(value = "INVOKE",
            target = "Lae2/crafting/CraftingTreeProcess;request(Lae2/crafting/inv/CraftingSimulationState;J)V"))
    private static void applygray$timeCraftingProcessRequest(CraftingTreeProcess process,
                                                               CraftingSimulationState inventory,
                                                               long requestedPatternRuns)
            throws CraftBranchFailure, InterruptedException {
        boolean timing = DynamicRecipePatternRegistry.beginCraftingProcessRequest(process.getDetails());
        try {
            ((AccessorCraftingTreeProcess) process).applygray$request(inventory, requestedPatternRuns);
        } finally {
            if (timing) DynamicRecipePatternRegistry.finishCraftingProcessRequest();
        }
    }
}
