package applygray.integration.ae2.rules;

/** Behavior selected by the rule package when bounded route planning reaches a configured limit. */
public enum BudgetExhaustionPolicy {
    /** Keep the explicitly logged bounded fallback candidate. */
    DEGRADE,
    /** Conservatively withhold dynamic candidates whose complete route analysis could not finish. */
    REJECT
}
