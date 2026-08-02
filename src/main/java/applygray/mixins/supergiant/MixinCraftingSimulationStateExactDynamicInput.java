package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipeInputPreview;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import ae2.api.config.FuzzyMode;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.inv.CraftingSimulationState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Keeps frozen dynamic inputs exact until an ordinary fuzzy input genuinely needs the full AE2 cache. */
@Mixin(value = CraftingSimulationState.class, remap = false)
public abstract class MixinCraftingSimulationStateExactDynamicInput {

    @Shadow @Final private KeyCounter unmodifiedCache;
    @Shadow @Final private KeyCounter modifiableCache;

    @Shadow protected abstract long simulateExtractParent(AEKey key);
    @Shadow protected abstract Iterable<AEKey> findFuzzyParent(AEKey key);

    /**
     * Exact keys cached without enumerating all variants, grouped by AE2's identity-based primary key.
     * A key stays here even when its parent inventory amount is zero: AE2's normal fuzzy cache also keeps
     * zero-valued entries as negative-cache markers.
     */
    @Unique
    private final Map<Object, Set<AEKey>> applygray$exactOnlyKeysByPrimary = new IdentityHashMap<>();

    @Inject(method = "cacheFuzzy", at = @At("HEAD"), cancellable = true)
    private void applygray$cacheFrozenDynamicInputExactly(AEKey key, CallbackInfo ci) {
        if (key == null) return;

        Object primary = key.getPrimaryKey();
        Set<AEKey> exactOnlyKeys = applygray$exactOnlyKeysByPrimary.get(primary);
        if (DynamicRecipeInputPreview.isExtractingExactDynamicInput(key)) {
            if (exactOnlyKeys != null && exactOnlyKeys.contains(key)) {
                ci.cancel();
                return;
            }
            // An ordinary AE2 path may already have completed the fuzzy cache for this primary key. Retain that
            // state rather than loading a single variant from the parent inventory again. We cannot use this
            // shortcut while exact-only variants are pending, because each distinct programmable-circuit tag still
            // needs its own direct lookup.
            if (exactOnlyKeys == null &&
                    !unmodifiedCache.findFuzzy(key, FuzzyMode.IGNORE_ALL).isEmpty()) {
                ci.cancel();
                return;
            }

            long available = simulateExtractParent(key);
            // Match AE2's cacheFuzzy behavior exactly: add a zero-valued entry too, otherwise every failed
            // availability preview repeats the parent-network lookup.
            unmodifiedCache.add(key, available);
            modifiableCache.add(key, available);
            applygray$exactOnlyKeysByPrimary.computeIfAbsent(primary, ignored -> new HashSet<>()).add(key);
            DynamicRecipePatternRegistry.recordExactDynamicInputCacheBypass();
            ci.cancel();
            return;
        }

        // A normal/fuzzy pattern can legally consume another variant of the same item or fluid. Complete the
        // ordinary cache once before handing control back to AE2, preserving quantities already consumed exactly.
        if (exactOnlyKeys != null && !exactOnlyKeys.isEmpty()) {
            applygray$exactOnlyKeysByPrimary.remove(primary);
            for (AEKey candidate : findFuzzyParent(key)) {
                if (candidate == null || exactOnlyKeys.contains(candidate)) continue;
                long available = simulateExtractParent(candidate);
                // Keep zero entries here as well, so a later fuzzy request does not rescan missing variants.
                unmodifiedCache.add(candidate, available);
                modifiableCache.add(candidate, available);
            }
            ci.cancel();
        }
    }
}
