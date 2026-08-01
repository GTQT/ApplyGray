package applygray.integration.ae2.rules;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable, atomically-swapped limits for one dynamic RecipeMap planning snapshot.
 *
 * <p>Values are intentionally positive and finite. A rule package may raise or lower a limit, but must make that
 * choice explicit in JSON so large pack-specific searches never become accidental unbounded work.</p>
 */
public final class PlanningBudget {

    public static final PlanningBudget DEFAULT = new PlanningBudget(
            512, 8, 8, 8, 32, 16, 16, 64, 512, 2_000,
            4_096, 8_000,
            512, 2_048, 1_000, 64, BudgetExhaustionPolicy.DEGRADE,
            CycleSafetyExhaustionPolicy.RUNTIME_RECOVERY);

    private final int maxRecipesPerTarget;
    private final int maxCandidatesPerTarget;
    private final int maxDynamicCandidatesForCost;
    private final int maxRefinedCandidates;
    private final int maxNormalPatternsPerTarget;
    private final int maxInputAlternatives;
    private final int maxRouteDepth;
    private final int maxRouteExpansionsPerTarget;
    private final int maxRouteExpansionsPerCalculation;
    private final long maxRouteCalculationMillis;
    private final int maxStandaloneRouteExpansionsPerCalculation;
    private final long maxStandaloneRouteCalculationMillis;
    private final int maxSccNodes;
    private final int maxSccEdges;
    private final long maxSccAnalysisMillis;
    private final int maxPersistedPatternsPerProvider;
    private final BudgetExhaustionPolicy exhaustionPolicy;
    private final CycleSafetyExhaustionPolicy cycleSafetyExhaustionPolicy;

    private PlanningBudget(int maxRecipesPerTarget, int maxCandidatesPerTarget,
                           int maxDynamicCandidatesForCost, int maxRefinedCandidates,
                           int maxNormalPatternsPerTarget, int maxInputAlternatives, int maxRouteDepth,
                           int maxRouteExpansionsPerTarget, int maxRouteExpansionsPerCalculation,
                           long maxRouteCalculationMillis, int maxStandaloneRouteExpansionsPerCalculation,
                           long maxStandaloneRouteCalculationMillis, int maxSccNodes, int maxSccEdges,
                           long maxSccAnalysisMillis, int maxPersistedPatternsPerProvider,
                           BudgetExhaustionPolicy exhaustionPolicy,
                           CycleSafetyExhaustionPolicy cycleSafetyExhaustionPolicy) {
        this.maxRecipesPerTarget = requirePositive(maxRecipesPerTarget, "maxRecipesPerTarget");
        this.maxCandidatesPerTarget = requirePositive(maxCandidatesPerTarget, "maxCandidatesPerTarget");
        this.maxDynamicCandidatesForCost = requirePositive(maxDynamicCandidatesForCost,
                "maxDynamicCandidatesForCost");
        this.maxRefinedCandidates = requirePositive(maxRefinedCandidates, "maxRefinedCandidates");
        this.maxNormalPatternsPerTarget = requirePositive(maxNormalPatternsPerTarget, "maxNormalPatternsPerTarget");
        this.maxInputAlternatives = requirePositive(maxInputAlternatives, "maxInputAlternatives");
        this.maxRouteDepth = requirePositive(maxRouteDepth, "maxRouteDepth");
        this.maxRouteExpansionsPerTarget = requirePositive(maxRouteExpansionsPerTarget,
                "maxRouteExpansionsPerTarget");
        this.maxRouteExpansionsPerCalculation = requirePositive(maxRouteExpansionsPerCalculation,
                "maxRouteExpansionsPerCalculation");
        this.maxRouteCalculationMillis = requirePositive(maxRouteCalculationMillis, "maxRouteCalculationMillis");
        this.maxStandaloneRouteExpansionsPerCalculation = requirePositive(maxStandaloneRouteExpansionsPerCalculation,
                "maxStandaloneRouteExpansionsPerCalculation");
        this.maxStandaloneRouteCalculationMillis = requirePositive(maxStandaloneRouteCalculationMillis,
                "maxStandaloneRouteCalculationMillis");
        this.maxSccNodes = requirePositive(maxSccNodes, "maxSccNodes");
        this.maxSccEdges = requirePositive(maxSccEdges, "maxSccEdges");
        this.maxSccAnalysisMillis = requirePositive(maxSccAnalysisMillis, "maxSccAnalysisMillis");
        this.maxPersistedPatternsPerProvider = requirePositive(maxPersistedPatternsPerProvider,
                "maxPersistedPatternsPerProvider");
        this.exhaustionPolicy = Objects.requireNonNull(exhaustionPolicy, "exhaustionPolicy");
        this.cycleSafetyExhaustionPolicy = Objects.requireNonNull(cycleSafetyExhaustionPolicy,
                "cycleSafetyExhaustionPolicy");
    }

    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    public int getMaxRecipesPerTarget() {
        return maxRecipesPerTarget;
    }

    public int getMaxCandidatesPerTarget() {
        return maxCandidatesPerTarget;
    }

    public int getMaxDynamicCandidatesForCost() {
        return Math.min(maxDynamicCandidatesForCost, maxCandidatesPerTarget);
    }

    public int getMaxRefinedCandidates() {
        return Math.min(maxRefinedCandidates, maxCandidatesPerTarget);
    }

    public int getMaxNormalPatternsPerTarget() {
        return maxNormalPatternsPerTarget;
    }

    public int getMaxInputAlternatives() {
        return maxInputAlternatives;
    }

    public int getMaxRouteDepth() {
        return maxRouteDepth;
    }

    public int getMaxRouteExpansionsPerTarget() {
        return maxRouteExpansionsPerTarget;
    }

    public int getMaxRouteExpansionsPerCalculation() {
        return maxRouteExpansionsPerCalculation;
    }

    public long getMaxRouteCalculationMillis() {
        return maxRouteCalculationMillis;
    }

    public long getMaxRouteCalculationNanos() {
        return TimeUnit.MILLISECONDS.toNanos(maxRouteCalculationMillis);
    }

    /**
     * Legacy-named node cap for standalone tree materialization.
     *
     * <p>It limits how many selected graph nodes a generated template may contain. Recursive route scoring itself is
     * deadline-bound and receives fair adaptive grants, so this value is intentionally not a route-score cap.</p>
     */
    public int getMaxStandaloneRouteExpansionsPerCalculation() {
        return maxStandaloneRouteExpansionsPerCalculation;
    }

    public long getMaxStandaloneRouteCalculationMillis() {
        return maxStandaloneRouteCalculationMillis;
    }

    public long getMaxStandaloneRouteCalculationNanos() {
        return TimeUnit.MILLISECONDS.toNanos(maxStandaloneRouteCalculationMillis);
    }

    public int getMaxSccNodes() {
        return maxSccNodes;
    }

    public int getMaxSccEdges() {
        return maxSccEdges;
    }

    public long getMaxSccAnalysisMillis() {
        return maxSccAnalysisMillis;
    }

    public long getMaxSccAnalysisNanos() {
        return TimeUnit.MILLISECONDS.toNanos(maxSccAnalysisMillis);
    }

    public int getMaxPersistedPatternsPerProvider() {
        return maxPersistedPatternsPerProvider;
    }

    public BudgetExhaustionPolicy getExhaustionPolicy() {
        return exhaustionPolicy;
    }

    public CycleSafetyExhaustionPolicy getCycleSafetyExhaustionPolicy() {
        return cycleSafetyExhaustionPolicy;
    }

    public String summarize() {
        return "recipes=" + maxRecipesPerTarget + ", candidates=" + maxCandidatesPerTarget +
                ", depth=" + maxRouteDepth + ", expansions=" + maxRouteExpansionsPerCalculation +
                ", routeMs=" + maxRouteCalculationMillis + ", scc=" + maxSccNodes + '/' + maxSccEdges +
                ", standaloneTreeNodes=" + maxStandaloneRouteExpansionsPerCalculation +
                ", standaloneRouteMs=" + maxStandaloneRouteCalculationMillis +
                ", sccMs=" + maxSccAnalysisMillis + ", persisted=" + maxPersistedPatternsPerProvider +
                ", onExhaustion=" + exhaustionPolicy +
                ", cycleSafetyOnExhaustion=" + cycleSafetyExhaustionPolicy;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /** Deterministically merges scalar settings by priority, then by source identifier. */
    public static final class Builder {

        private final Setting<Integer> maxRecipesPerTarget;
        private final Setting<Integer> maxCandidatesPerTarget;
        private final Setting<Integer> maxDynamicCandidatesForCost;
        private final Setting<Integer> maxRefinedCandidates;
        private final Setting<Integer> maxNormalPatternsPerTarget;
        private final Setting<Integer> maxInputAlternatives;
        private final Setting<Integer> maxRouteDepth;
        private final Setting<Integer> maxRouteExpansionsPerTarget;
        private final Setting<Integer> maxRouteExpansionsPerCalculation;
        private final Setting<Long> maxRouteCalculationMillis;
        private final Setting<Integer> maxStandaloneRouteExpansionsPerCalculation;
        private final Setting<Long> maxStandaloneRouteCalculationMillis;
        private final Setting<Integer> maxSccNodes;
        private final Setting<Integer> maxSccEdges;
        private final Setting<Long> maxSccAnalysisMillis;
        private final Setting<Integer> maxPersistedPatternsPerProvider;
        private final Setting<BudgetExhaustionPolicy> exhaustionPolicy;
        private final Setting<CycleSafetyExhaustionPolicy> cycleSafetyExhaustionPolicy;

        private Builder(PlanningBudget defaults) {
            maxRecipesPerTarget = new Setting<>(defaults.maxRecipesPerTarget);
            maxCandidatesPerTarget = new Setting<>(defaults.maxCandidatesPerTarget);
            maxDynamicCandidatesForCost = new Setting<>(defaults.maxDynamicCandidatesForCost);
            maxRefinedCandidates = new Setting<>(defaults.maxRefinedCandidates);
            maxNormalPatternsPerTarget = new Setting<>(defaults.maxNormalPatternsPerTarget);
            maxInputAlternatives = new Setting<>(defaults.maxInputAlternatives);
            maxRouteDepth = new Setting<>(defaults.maxRouteDepth);
            maxRouteExpansionsPerTarget = new Setting<>(defaults.maxRouteExpansionsPerTarget);
            maxRouteExpansionsPerCalculation = new Setting<>(defaults.maxRouteExpansionsPerCalculation);
            maxRouteCalculationMillis = new Setting<>(defaults.maxRouteCalculationMillis);
            maxStandaloneRouteExpansionsPerCalculation =
                    new Setting<>(defaults.maxStandaloneRouteExpansionsPerCalculation);
            maxStandaloneRouteCalculationMillis = new Setting<>(defaults.maxStandaloneRouteCalculationMillis);
            maxSccNodes = new Setting<>(defaults.maxSccNodes);
            maxSccEdges = new Setting<>(defaults.maxSccEdges);
            maxSccAnalysisMillis = new Setting<>(defaults.maxSccAnalysisMillis);
            maxPersistedPatternsPerProvider = new Setting<>(defaults.maxPersistedPatternsPerProvider);
            exhaustionPolicy = new Setting<>(defaults.exhaustionPolicy);
            cycleSafetyExhaustionPolicy = new Setting<>(defaults.cycleSafetyExhaustionPolicy);
        }

        public void maxRecipesPerTarget(int value, int priority, String source) {
            maxRecipesPerTarget.set(requirePositive(value, "maxRecipesPerTarget"), priority, source);
        }

        public void maxCandidatesPerTarget(int value, int priority, String source) {
            maxCandidatesPerTarget.set(requirePositive(value, "maxCandidatesPerTarget"), priority, source);
        }

        public void maxDynamicCandidatesForCost(int value, int priority, String source) {
            maxDynamicCandidatesForCost.set(requirePositive(value, "maxDynamicCandidatesForCost"), priority, source);
        }

        public void maxRefinedCandidates(int value, int priority, String source) {
            maxRefinedCandidates.set(requirePositive(value, "maxRefinedCandidates"), priority, source);
        }

        public void maxNormalPatternsPerTarget(int value, int priority, String source) {
            maxNormalPatternsPerTarget.set(requirePositive(value, "maxNormalPatternsPerTarget"), priority, source);
        }

        public void maxInputAlternatives(int value, int priority, String source) {
            maxInputAlternatives.set(requirePositive(value, "maxInputAlternatives"), priority, source);
        }

        public void maxRouteDepth(int value, int priority, String source) {
            maxRouteDepth.set(requirePositive(value, "maxRouteDepth"), priority, source);
        }

        public void maxRouteExpansionsPerTarget(int value, int priority, String source) {
            maxRouteExpansionsPerTarget.set(requirePositive(value, "maxRouteExpansionsPerTarget"), priority, source);
        }

        public void maxRouteExpansionsPerCalculation(int value, int priority, String source) {
            maxRouteExpansionsPerCalculation.set(requirePositive(value, "maxRouteExpansionsPerCalculation"),
                    priority, source);
        }

        public void maxRouteCalculationMillis(long value, int priority, String source) {
            maxRouteCalculationMillis.set(requirePositive(value, "maxRouteCalculationMillis"), priority, source);
        }

        public void maxStandaloneRouteExpansionsPerCalculation(int value, int priority, String source) {
            maxStandaloneRouteExpansionsPerCalculation.set(
                    requirePositive(value, "maxStandaloneRouteExpansionsPerCalculation"), priority, source);
        }

        public void maxStandaloneRouteCalculationMillis(long value, int priority, String source) {
            maxStandaloneRouteCalculationMillis.set(
                    requirePositive(value, "maxStandaloneRouteCalculationMillis"), priority, source);
        }

        public void maxSccNodes(int value, int priority, String source) {
            maxSccNodes.set(requirePositive(value, "maxSccNodes"), priority, source);
        }

        public void maxSccEdges(int value, int priority, String source) {
            maxSccEdges.set(requirePositive(value, "maxSccEdges"), priority, source);
        }

        public void maxSccAnalysisMillis(long value, int priority, String source) {
            maxSccAnalysisMillis.set(requirePositive(value, "maxSccAnalysisMillis"), priority, source);
        }

        public void maxPersistedPatternsPerProvider(int value, int priority, String source) {
            maxPersistedPatternsPerProvider.set(requirePositive(value, "maxPersistedPatternsPerProvider"),
                    priority, source);
        }

        public void exhaustionPolicy(BudgetExhaustionPolicy value, int priority, String source) {
            exhaustionPolicy.set(Objects.requireNonNull(value, "exhaustionPolicy"), priority, source);
        }

        public void cycleSafetyExhaustionPolicy(CycleSafetyExhaustionPolicy value, int priority, String source) {
            cycleSafetyExhaustionPolicy.set(Objects.requireNonNull(value, "cycleSafetyExhaustionPolicy"), priority,
                    source);
        }

        public PlanningBudget build() {
            return new PlanningBudget(maxRecipesPerTarget.value, maxCandidatesPerTarget.value,
                    maxDynamicCandidatesForCost.value, maxRefinedCandidates.value,
                    maxNormalPatternsPerTarget.value, maxInputAlternatives.value, maxRouteDepth.value,
                    maxRouteExpansionsPerTarget.value, maxRouteExpansionsPerCalculation.value,
                    maxRouteCalculationMillis.value, maxStandaloneRouteExpansionsPerCalculation.value,
                    maxStandaloneRouteCalculationMillis.value, maxSccNodes.value, maxSccEdges.value,
                    maxSccAnalysisMillis.value, maxPersistedPatternsPerProvider.value, exhaustionPolicy.value,
                    cycleSafetyExhaustionPolicy.value);
        }
    }

    private static final class Setting<T> {

        private T value;
        private int priority = Integer.MIN_VALUE;
        private String source;

        private Setting(T value) {
            this.value = value;
        }

        private void set(T value, int candidatePriority, String candidateSource) {
            String normalizedSource = candidateSource == null ? "" : candidateSource;
            if (candidatePriority > priority ||
                    candidatePriority == priority && normalizedSource.compareTo(source == null ? "" : source) < 0) {
                this.value = value;
                this.priority = candidatePriority;
                this.source = normalizedSource;
            }
        }
    }
}
