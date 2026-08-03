package applygray.mixins.supergiant;

import applygray.integration.ae2.DynamicRecipeInputPreview;
import applygray.integration.ae2.DynamicRecipePatternRegistry;
import gregtech.integration.ae2.GTCircuitHelper;

import ae2.api.config.FuzzyMode;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.inv.CraftingSimulationState;
import com.google.common.collect.Iterables;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps frozen dynamic inputs and programmable circuits exact during AE2 crafting calculations. */
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

    /** Circuit keys that were directly cached, retained after an ordinary fuzzy cache finishes for their primary. */
    @Unique
    private final Map<Object, Set<AEKey>> applygray$programmableCircuitKeysByPrimary = new IdentityHashMap<>();

    @Inject(method = "cacheFuzzy", at = @At("HEAD"), cancellable = true)
    private void applygray$cacheFrozenDynamicInputExactly(AEKey key, CallbackInfo ci) {
        if (key == null) return;

        // A programmable circuit's NBT identifies its wrapped item, so variants must never share AE2's fuzzy cache.
        if (applygray$isProgrammableCircuit(key)) {
            applygray$cacheProgrammableCircuitExactly(key);
            ci.cancel();
            return;
        }

        Object primary = key.getPrimaryKey();
        Set<AEKey> exactOnlyKeys = applygray$exactOnlyKeysByPrimary.get(primary);
        if (DynamicRecipeInputPreview.isExtractingExactDynamicInput(key)) {
            if (applygray$cacheExactKey(key, true)) {
                DynamicRecipePatternRegistry.recordExactDynamicInputCacheBypass();
            }
            ci.cancel();
            return;
        }

        // A normal/fuzzy pattern can legally consume another variant of the same item or fluid. Complete the
        // ordinary cache once before handing control back to AE2, preserving quantities already consumed exactly.
        if (exactOnlyKeys != null && !exactOnlyKeys.isEmpty()) {
            applygray$exactOnlyKeysByPrimary.remove(primary);
            for (AEKey candidate : findFuzzyParent(key)) {
                if (candidate == null || exactOnlyKeys.contains(candidate) ||
                        applygray$isProgrammableCircuit(candidate)) continue;
                long available = simulateExtractParent(candidate);
                // Keep zero entries here as well, so a later fuzzy request does not rescan missing variants.
                unmodifiedCache.add(candidate, available);
                modifiableCache.add(candidate, available);
            }
            ci.cancel();
        }
    }

    /** Prevent cached circuit variants from being returned by AE2's later fuzzy-template lookup. */
    @Inject(method = "findFuzzyTemplates", at = @At("HEAD"), cancellable = true)
    private void applygray$findProgrammableCircuitTemplateExactly(AEKey key,
                                                                    CallbackInfoReturnable<Iterable<AEKey>> cir) {
        if (!applygray$isProgrammableCircuit(key)) return;

        applygray$cacheProgrammableCircuitExactly(key);
        cir.setReturnValue(modifiableCache.get(key) > 0 ? List.of(key) : Collections.emptyList());
    }

    /** Removes circuit variants from every ordinary fuzzy template result. */
    @Inject(method = "findFuzzyTemplates", at = @At("RETURN"), cancellable = true)
    private void applygray$excludeProgrammableCircuitsFromFuzzyTemplates(AEKey key,
                                                                           CallbackInfoReturnable<Iterable<AEKey>> cir) {
        if (applygray$isProgrammableCircuit(key)) return;

        Iterable<AEKey> templates = cir.getReturnValue();
        if (templates != null) {
            cir.setReturnValue(Iterables.filter(templates, candidate -> !applygray$isProgrammableCircuit(candidate)));
        }
    }

    /**
     * Filters circuit variants before AE2 fills a normal fuzzy cache. This is needed even for non-circuit requests,
     * because GT meta-items can share AE2's primary item key.
     */
    @Redirect(method = "cacheFuzzy", at = @At(value = "INVOKE",
            target = "Lae2/crafting/inv/CraftingSimulationState;findFuzzyParent(Lae2/api/stacks/AEKey;)Ljava/lang/Iterable;"))
    private Iterable<AEKey> applygray$excludeProgrammableCircuitsFromFuzzyCache(CraftingSimulationState state,
                                                                                   AEKey key) {
        return Iterables.filter(findFuzzyParent(key), candidate -> !applygray$isProgrammableCircuit(candidate));
    }

    /**
     * Caches only {@code key}, including a zero entry as AE2's negative-cache marker.
     *
     * @return whether this call performed a direct parent-inventory lookup
     */
    @Unique
    private boolean applygray$cacheExactKey(AEKey key, boolean retainCompletedFuzzyCache) {
        Object primary = key.getPrimaryKey();
        Set<AEKey> exactOnlyKeys = applygray$exactOnlyKeysByPrimary.get(primary);
        if (exactOnlyKeys != null && exactOnlyKeys.contains(key)) {
            return false;
        }

        // Dynamic inputs may reuse a cache that AE2 completed before the exact-only scope began. Programmable
        // circuits intentionally bypass this shortcut because every wrapped-item variant must remain isolated.
        if (retainCompletedFuzzyCache && exactOnlyKeys == null &&
                !unmodifiedCache.findFuzzy(key, FuzzyMode.IGNORE_ALL).isEmpty()) {
            return false;
        }

        long available = simulateExtractParent(key);
        unmodifiedCache.add(key, available);
        modifiableCache.add(key, available);
        applygray$exactOnlyKeysByPrimary.computeIfAbsent(primary, ignored -> new HashSet<>()).add(key);
        return true;
    }

    /** Caches one circuit variant directly and remembers it independently from temporary dynamic-input state. */
    @Unique
    private void applygray$cacheProgrammableCircuitExactly(AEKey key) {
        Object primary = key.getPrimaryKey();
        Set<AEKey> circuitKeys = applygray$programmableCircuitKeysByPrimary.get(primary);
        if (circuitKeys != null && circuitKeys.contains(key)) {
            return;
        }

        // If only direct exact entries exist, an ordinary fuzzy lookup still needs its remaining non-circuit
        // candidates cached once. A completed ordinary cache does not need that second pass.
        boolean hasCompletedFuzzyCache = !unmodifiedCache.findFuzzy(key, FuzzyMode.IGNORE_ALL).isEmpty();
        long available = simulateExtractParent(key);
        unmodifiedCache.add(key, available);
        modifiableCache.add(key, available);
        applygray$programmableCircuitKeysByPrimary.computeIfAbsent(primary, ignored -> new HashSet<>()).add(key);
        if (!hasCompletedFuzzyCache || applygray$exactOnlyKeysByPrimary.containsKey(primary)) {
            applygray$exactOnlyKeysByPrimary.computeIfAbsent(primary, ignored -> new HashSet<>()).add(key);
        }
    }

    @Unique
    private static boolean applygray$isProgrammableCircuit(AEKey key) {
        return key instanceof AEItemKey itemKey && GTCircuitHelper.isProgrammableCircuit(itemKey.toStack());
    }
}
