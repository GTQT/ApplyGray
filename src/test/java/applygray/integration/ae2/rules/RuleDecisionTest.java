package applygray.integration.ae2.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuleDecisionTest {

    @Test
    void higherPriorityAllowClearsNormalDenial() {
        RuleDecision decision = new RuleDecision();

        decision.deny("pack.default", "DEFAULT_DENIED", 10, false);
        decision.allow("provider.allow", 20);

        assertNull(decision.getDenialCode());
    }

    @Test
    void allowNeverClearsSafetyDenial() {
        RuleDecision decision = new RuleDecision();

        decision.deny("core.safety", "UNSAFE", true);
        decision.allow("admin.allow", Integer.MAX_VALUE);

        assertEquals("UNSAFE", decision.getDenialCode());
    }

    @Test
    void scalarPoliciesUseRuleIdAsStableSamePriorityTieBreaker() {
        RuleDecision decision = new RuleDecision();

        decision.cyclePolicy("z-last", CyclePolicy.PENALIZE, 100);
        decision.cyclePolicy("a-first", CyclePolicy.FORBID, 100);

        assertEquals(CyclePolicy.FORBID, decision.getCyclePolicy());
    }

    @Test
    void matchingRulesKeepTheMostRestrictivePatternCap() {
        RuleDecision decision = new RuleDecision();

        decision.capPatterns("pack.default", 4);
        decision.capPatterns("provider.safety", 2);

        assertEquals(2, decision.getMaxPatternsForTarget());
    }

    @Test
    void breakableCyclePolicyIsAValidScalarPolicy() {
        RuleDecision decision = new RuleDecision();

        decision.cyclePolicy("pack.breakable", CyclePolicy.BREAKABLE, 100);

        assertEquals(CyclePolicy.BREAKABLE, decision.getCyclePolicy());
    }

    @Test
    void planningModeUsesTheSameStableScalarMergeRule() {
        RuleDecision decision = new RuleDecision();

        decision.planningMode("z-last", PlanningMode.THROUGHPUT_FIRST, 100);
        decision.planningMode("a-first", PlanningMode.SAFE_FIRST, 100);

        assertEquals(PlanningMode.SAFE_FIRST, decision.getPlanningModeOverride());
    }
}
