package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gregtech.api.mattermanipulator.SmartCopyLink;

/** Lifecycle-only registration for Pattern Provider Smart Copy source endpoints. */
final class SmartCopyPatternProviderRegistry {

    private static final Map<SmartCopyLink, List<MetaTileEntityMEPatternProvider>> TARGETS = new HashMap<>();

    private SmartCopyPatternProviderRegistry() {}

    static void registerTarget(SmartCopyLink source, MetaTileEntityMEPatternProvider target) {
        unregisterTarget(target);
        TARGETS.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
    }

    static void unregisterTarget(MetaTileEntityMEPatternProvider target) {
        TARGETS.entrySet().removeIf(entry -> {
            entry.getValue().remove(target);
            return entry.getValue().isEmpty();
        });
    }

    static void sourceAvailable(MetaTileEntityMEPatternProvider source) {
        notifySource(source, false);
    }

    static void sourcePatternsChanged(MetaTileEntityMEPatternProvider source) {
        notifySource(source, false);
    }

    static void sourceRemoved(MetaTileEntityMEPatternProvider source) {
        notifySource(source, true);
    }

    private static void notifySource(MetaTileEntityMEPatternProvider source, boolean removed) {
        SmartCopyLink identity = source.getSmartCopyEndpointIdentity();
        if (identity == null) return;
        List<MetaTileEntityMEPatternProvider> targets = TARGETS.get(identity);
        if (targets == null || targets.isEmpty()) return;
        for (MetaTileEntityMEPatternProvider target : List.copyOf(targets)) {
            if (removed) {
                target.onSmartCopySourceRemoved(source);
            } else {
                target.onSmartCopySourceChanged(source);
            }
        }
    }
}
