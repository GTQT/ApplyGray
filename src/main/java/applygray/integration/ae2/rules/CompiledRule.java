package applygray.integration.ae2.rules;

import java.util.List;

final class CompiledRule {

    private final String id;
    private final int priority;
    private final RulePredicate predicate;
    private final List<RuleEffect> effects;

    CompiledRule(String id, int priority, RulePredicate predicate, List<RuleEffect> effects) {
        this.id = id;
        this.priority = priority;
        this.predicate = predicate;
        this.effects = effects;
    }

    String getId() {
        return id;
    }

    int getPriority() {
        return priority;
    }

    void evaluate(RuleContext context, RuleDecision decision) {
        if (!predicate.matches(context)) return;
        decision.explain(id + ":matched");
        for (RuleEffect effect : effects) {
            effect.apply(id, priority, context, decision);
        }
    }
}
