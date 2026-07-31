package applygray.integration.ae2.rules;

@FunctionalInterface
interface RuleEffect {

    void apply(String ruleId, int priority, RuleContext context, RuleDecision decision);
}
