package applygray.integration.ae2;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-calculation rejection state with deterministic progress reporting. */
final class CycleRecoveryTracker<K> {

    private final Map<K, Set<String>> rejectedRecipeKeysByTarget = new HashMap<>();
    private final Map<K, Set<K>> rejectedCycleMembersByTarget = new HashMap<>();
    private final Map<K, String> safetyUnknownTargets = new HashMap<>();
    private final Set<K> withheldSafetyUnknownTargets = new HashSet<>();

    int reject(K target, String recipeKey, Set<K> cycleMembers, boolean rejectEquivalentEdges) {
        if (target == null || recipeKey == null) return 0;
        int progress = rejectedRecipeKeysByTarget.computeIfAbsent(target, ignored -> new HashSet<>())
                .add(recipeKey) ? 1 : 0;
        if (!rejectEquivalentEdges || cycleMembers == null || cycleMembers.isEmpty()) return progress;

        Set<K> rejectedMembers = rejectedCycleMembersByTarget.computeIfAbsent(target, ignored -> new HashSet<>());
        for (K member : cycleMembers) {
            if (member != null && rejectedMembers.add(member)) {
                progress++;
            }
        }
        return progress;
    }

    boolean markSafetyUnknown(K target, String reason, boolean withhold) {
        if (target == null) return false;
        boolean firstObservation = safetyUnknownTargets.putIfAbsent(target,
                reason == null ? "UNKNOWN" : reason) == null;
        boolean firstWithholding = withhold && withheldSafetyUnknownTargets.add(target);
        return firstObservation || firstWithholding;
    }

    boolean hasFilters(K target) {
        return withheldSafetyUnknownTargets.contains(target) || rejectedRecipeKeysByTarget.containsKey(target) ||
                rejectedCycleMembersByTarget.containsKey(target);
    }

    boolean rejectsRecipeOrUnknown(K target, String recipeKey) {
        if (withheldSafetyUnknownTargets.contains(target)) return true;
        Set<String> rejectedRecipes = rejectedRecipeKeysByTarget.get(target);
        return rejectedRecipes != null && rejectedRecipes.contains(recipeKey);
    }

    Set<K> getRejectedCycleMembers(K target) {
        Set<K> rejected = rejectedCycleMembersByTarget.get(target);
        return rejected == null ? Collections.emptySet() : rejected;
    }

    int getSafetyUnknownTargetCount() {
        return safetyUnknownTargets.size();
    }

    static <K> boolean requiresCycleMember(Collection<? extends Collection<K>> inputAlternatives,
                                           Set<K> cycleMembers) {
        if (inputAlternatives == null || inputAlternatives.isEmpty() || cycleMembers == null ||
                cycleMembers.isEmpty()) {
            return false;
        }
        for (Collection<K> options : inputAlternatives) {
            if (options != null && !options.isEmpty() && cycleMembers.containsAll(options)) {
                return true;
            }
        }
        return false;
    }
}
