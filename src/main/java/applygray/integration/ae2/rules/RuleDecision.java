package applygray.integration.ae2.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explainable merged result of all matching rules. */
public final class RuleDecision {

    private String denialCode;
    private String denialRuleId;
    private int denialPriority = Integer.MIN_VALUE;
    private boolean safetyDenial;
    private final Set<String> tags = new LinkedHashSet<>();
    private final Map<String, Long> scores = new LinkedHashMap<>();
    private int maxPatternsForTarget = Integer.MAX_VALUE;
    private OutputPolicy outputPolicy = OutputPolicy.DETERMINISTIC_ONLY;
    private CyclePolicy cyclePolicy = CyclePolicy.BREAK_AT_EXTERNAL_SEED;
    private PlanningMode planningMode;
    private String pinGroup;
    private int outputPolicyPriority = Integer.MIN_VALUE;
    private int cyclePolicyPriority = Integer.MIN_VALUE;
    private int planningModePriority = Integer.MIN_VALUE;
    private int pinGroupPriority = Integer.MIN_VALUE;
    private String outputPolicyRuleId;
    private String cyclePolicyRuleId;
    private String planningModeRuleId;
    private String pinGroupRuleId;
    private final List<String> explanation = new ArrayList<>();

    public boolean isAllowed() {
        return denialCode == null;
    }

    public String getDenialCode() {
        return denialCode;
    }

    public Set<String> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public long getScore(String name) {
        return scores.getOrDefault(name, 0L);
    }

    public Map<String, Long> getScores() {
        return Collections.unmodifiableMap(scores);
    }

    public int getMaxPatternsForTarget() {
        return maxPatternsForTarget == Integer.MAX_VALUE ? 1 : maxPatternsForTarget;
    }

    public OutputPolicy getOutputPolicy() {
        return outputPolicy;
    }

    public CyclePolicy getCyclePolicy() {
        return cyclePolicy;
    }

    /** Returns the route profile selected by matching rules, or {@code null} to retain the provider profile. */
    public PlanningMode getPlanningModeOverride() {
        return planningMode;
    }

    public String getPinGroup() {
        return pinGroup;
    }

    public List<String> getExplanation() {
        return Collections.unmodifiableList(explanation);
    }

    /** Adds a normal rejection that a later, higher-priority allow rule may lift. */
    public void deny(String ruleId, String code) {
        deny(ruleId, code, 0, false);
    }

    void deny(String ruleId, String code, boolean safety) {
        deny(ruleId, code, Integer.MAX_VALUE, safety);
    }

    void deny(String ruleId, String code, int priority, boolean safety) {
        boolean replaces = denialCode == null ||
                (!safetyDenial && (safety || priority > denialPriority ||
                        priority == denialPriority && compareRuleIds(ruleId, denialRuleId) < 0));
        if (replaces) {
            denialCode = code;
            denialRuleId = ruleId;
            denialPriority = priority;
            safetyDenial = safety;
        }
        explanation.add(ruleId + ':' + (safety ? "safety-deny:" : "deny:") + code);
    }

    /** Adds a named route or policy tag. */
    public void tag(String ruleId, String tag) {
        if (tags.add(tag)) explanation.add(ruleId + ":tag:" + tag);
    }

    /** Adds a signed score contribution to a named cost dimension. */
    public void score(String ruleId, String score, long value) {
        scores.merge(score, value, RuleDecision::saturatedAdd);
        explanation.add(ruleId + ":score:" + score + '=' + value);
    }

    /** Applies the most restrictive requested pattern cap. */
    public void capPatterns(String ruleId, int maxPatterns) {
        int normalized = Math.max(1, maxPatterns);
        maxPatternsForTarget = Math.min(maxPatternsForTarget, normalized);
        explanation.add(ruleId + ":maxPatterns=" + maxPatternsForTarget);
    }

    public void outputPolicy(String ruleId, OutputPolicy policy, int priority) {
        if (winsScalar(ruleId, priority, outputPolicyRuleId, outputPolicyPriority)) {
            outputPolicy = policy;
            outputPolicyPriority = priority;
            outputPolicyRuleId = ruleId;
            explanation.add(ruleId + ":outputPolicy=" + policy);
        }
    }

    public void cyclePolicy(String ruleId, CyclePolicy policy, int priority) {
        if (winsScalar(ruleId, priority, cyclePolicyRuleId, cyclePolicyPriority)) {
            cyclePolicy = policy;
            cyclePolicyPriority = priority;
            cyclePolicyRuleId = ruleId;
            explanation.add(ruleId + ":cyclePolicy=" + policy);
        }
    }

    public void planningMode(String ruleId, PlanningMode mode, int priority) {
        if (winsScalar(ruleId, priority, planningModeRuleId, planningModePriority)) {
            planningMode = mode;
            planningModePriority = priority;
            planningModeRuleId = ruleId;
            explanation.add(ruleId + ":planningMode=" + mode);
        }
    }

    public void pinGroup(String ruleId, String group, int priority) {
        if (winsScalar(ruleId, priority, pinGroupRuleId, pinGroupPriority)) {
            pinGroup = group;
            pinGroupPriority = priority;
            pinGroupRuleId = ruleId;
            explanation.add(ruleId + ":pinGroup=" + group);
        }
    }

    public void allow(String ruleId, int priority) {
        if (denialCode == null) {
            explanation.add(ruleId + ":allow:no-denial");
        } else if (safetyDenial) {
            explanation.add(ruleId + ":allow:blocked-by-safety:" + denialCode);
        } else if (priority >= denialPriority) {
            explanation.add(ruleId + ":allow:cleared:" + denialCode);
            denialCode = null;
            denialRuleId = null;
            denialPriority = Integer.MIN_VALUE;
        } else {
            explanation.add(ruleId + ":allow:lower-priority:" + denialCode);
        }
    }

    public void explain(String value) {
        explanation.add(value);
    }

    private static boolean winsScalar(String ruleId, int priority, String existingRuleId, int existingPriority) {
        return priority > existingPriority ||
                priority == existingPriority && compareRuleIds(ruleId, existingRuleId) < 0;
    }

    private static int compareRuleIds(String left, String right) {
        if (right == null) return -1;
        if (left == null) return 1;
        return left.compareTo(right);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }
}
