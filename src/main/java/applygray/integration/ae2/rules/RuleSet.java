package applygray.integration.ae2.rules;

import applygray.ApplyGrayMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable, atomically swappable compiled rule package. */
public final class RuleSet {

    private final String version;
    private final List<CompiledRule> rules;
    private final PlanningBudget planningBudget;

    RuleSet(String version, List<CompiledRule> rules) {
        this(version, rules, PlanningBudget.DEFAULT);
    }

    RuleSet(String version, List<CompiledRule> rules, PlanningBudget planningBudget) {
        this.version = version;
        List<CompiledRule> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparingInt(CompiledRule::getPriority).thenComparing(CompiledRule::getId));
        this.rules = Collections.unmodifiableList(ordered);
        this.planningBudget = planningBudget == null ? PlanningBudget.DEFAULT : planningBudget;
    }

    public static RuleSet empty() {
        return new RuleSet("builtin-empty", Collections.emptyList());
    }

    public String getVersion() {
        return version;
    }

    public int getRuleCount() {
        return rules.size();
    }

    /** Returns the immutable operational limits selected with this rule-set version. */
    public PlanningBudget getPlanningBudget() {
        return planningBudget;
    }

    public RuleDecision evaluate(RuleContext context, List<RecipePatternConstraint> constraints) {
        RuleDecision decision = new RuleDecision();

        // Non-overridable conservative safety constraints.
        if (!context.getMachine().isStructureFormed()) {
            decision.deny("core.safety", "MACHINE_NOT_FORMED", true);
        }
        if (!context.getMachine().getRecipeMaps().contains(context.getRecipe().getRecipeMapId())) {
            decision.deny("core.safety", "RECIPE_MAP_NOT_ROUTABLE", true);
        }
        rejectUnsupportedRecipeProperties(context, constraints, decision);
        if (Boolean.TRUE.equals(context.getFact("nonConsumableFluid"))) {
            decision.deny("core.safety", "NON_CONSUMABLE_FLUID_UNREPRESENTABLE", true);
        }
        if (context.getTokenSlots() > 81) {
            decision.deny("core.safety", "NC_TOKEN_SLOT_CAPACITY", true);
        }
        if (!context.getMachine().canAcceptAllOutputs(context.getRecipe())) {
            decision.deny("core.safety", context.getHiddenOutputCount() > 0 ?
                    "HIDDEN_OUTPUT_CAPACITY_UNPROVEN" : "OUTPUT_CAPACITY_UNPROVEN", true);
        }

        BuiltinRecipePropertyConstraint.evaluate(context, decision);
        for (RecipePatternConstraint constraint : constraints) evaluateConstraint(context, decision, constraint);
        RuleContext effectiveContext = context;
        for (CompiledRule rule : rules) {
            rule.evaluate(effectiveContext, decision);
            effectiveContext = effectiveContext.withTags(decision.getTags());
        }
        if (decision.getOutputPolicy() == OutputPolicy.UNSAFE_EXPECTED_VALUE &&
                !RecipePatternRules.isUnsafeModeEnabled()) {
            decision.deny("core.safety", "UNSAFE_OUTPUT_POLICY_DISABLED", true);
        }
        return decision;
    }

    private static void rejectUnsupportedRecipeProperties(RuleContext context,
                                                           List<RecipePatternConstraint> constraints,
                                                           RuleDecision decision) {
        for (String propertyKey : context.getRecipe().getUnknownPropertyKeys()) {
            boolean supported = false;
            for (RecipePatternConstraint constraint : constraints) {
                try {
                    if (constraint.supportsRecipeProperty(context.getRecipe(), propertyKey)) {
                        supported = true;
                        break;
                    }
                } catch (RuntimeException exception) {
                    ApplyGrayMod.LOGGER.warn("Recipe-pattern constraint {} failed while checking recipe property {}",
                            constraint.getClass().getName(), propertyKey, exception);
                    decision.deny("core.safety", "RECIPE_PATTERN_CONSTRAINT_FAILED", true);
                }
            }
            if (!supported) decision.deny("core.safety", "UNKNOWN_RECIPE_PROPERTY", true);
        }
    }

    private static void evaluateConstraint(RuleContext context, RuleDecision decision,
                                           RecipePatternConstraint constraint) {
        try {
            constraint.evaluate(context, decision);
        } catch (RuntimeException exception) {
            ApplyGrayMod.LOGGER.warn("Recipe-pattern constraint {} failed while evaluating recipe {}",
                    constraint.getClass().getName(), context.getRecipe().getRecipeFingerprint(), exception);
            decision.deny("core.safety", "RECIPE_PATTERN_CONSTRAINT_FAILED", true);
        }
    }
}
