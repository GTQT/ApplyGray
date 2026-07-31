package applygray.integration.ae2.rules;

@FunctionalInterface
interface RulePredicate {

    boolean matches(RuleContext context);
}
