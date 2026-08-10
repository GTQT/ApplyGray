package applygray.mattermanipulator.integration.ae2;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import ae2.parts.crafting.PatternProviderPart;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;

/** Lifecycle-scoped source-to-target registration for Pattern Provider Smart Copy links. */
public final class SmartCopyPatternProviderRegistry {

    private static final Map<SmartCopyPatternProviderLink, Set<SmartCopyPatternProviderLinkable>> TARGETS =
            new HashMap<>();

    private SmartCopyPatternProviderRegistry() {}

    public static void register(SmartCopyPatternProviderLink source, SmartCopyPatternProviderLinkable target) {
        TARGETS.computeIfAbsent(source, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(target);
    }

    public static void unregister(SmartCopyPatternProviderLinkable target) {
        TARGETS.entrySet().removeIf(entry -> {
            entry.getValue().remove(target);
            return entry.getValue().isEmpty();
        });
    }

    public static void sourceAvailable(PatternProviderPart source) {
        SmartCopyPatternProviderLink identity = sourceIdentity(source);
        if (identity != null) refreshTargets(identity);
    }

    public static void sourcePatternsChanged(PatternProviderPart source) {
        sourceAvailable(source);
    }

    public static void sourceUnavailable(PatternProviderPart source) {
        SmartCopyPatternProviderLink identity = sourceIdentity(source);
        if (identity != null) invalidateTargets(identity);
    }

    private static void refreshTargets(SmartCopyPatternProviderLink source) {
        Set<SmartCopyPatternProviderLinkable> targets = TARGETS.get(source);
        if (targets == null || targets.isEmpty()) return;
        for (SmartCopyPatternProviderLinkable target : Set.copyOf(targets)) {
            target.applygray$refreshSmartCopySource();
        }
    }

    private static void invalidateTargets(SmartCopyPatternProviderLink source) {
        Set<SmartCopyPatternProviderLinkable> targets = TARGETS.get(source);
        if (targets == null || targets.isEmpty()) return;
        for (SmartCopyPatternProviderLinkable target : Set.copyOf(targets)) {
            target.applygray$invalidateSmartCopySource();
        }
    }

    private static SmartCopyPatternProviderLink sourceIdentity(PatternProviderPart source) {
        TileEntity tile = source.getTileEntity();
        EnumFacing side = source.getSide();
        if (tile == null || tile.getWorld() == null || side == null) return null;
        return SmartCopyPatternProviderLink.forSource(tile.getWorld(), tile.getPos(), side);
    }
}
