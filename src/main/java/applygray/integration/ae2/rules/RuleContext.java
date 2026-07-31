package applygray.integration.ae2.rules;

import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.NormalizedRecipe;
import applygray.integration.ae2.recipe.RecipeFingerprint;

import ae2.api.stacks.AEKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Read-only facts exposed to data rules and Java adapters. */
public final class RuleContext {

    private final NormalizedRecipe recipe;
    private final MachineCapabilityProfile machine;
    private final AEKey target;
    private final PlanningMode planningMode;
    private final Set<String> tags;
    private final Map<String, Object> facts;
    private final int hiddenOutputCount;
    private final int tokenSlots;

    public RuleContext(NormalizedRecipe recipe, MachineCapabilityProfile machine, AEKey target,
                       PlanningMode planningMode, Set<String> tags, Map<String, Object> facts,
                       int hiddenOutputCount, int tokenSlots) {
        this.recipe = recipe;
        this.machine = machine;
        this.target = target;
        this.planningMode = planningMode;
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        Map<String, Object> combinedFacts = new LinkedHashMap<>();
        if (facts != null) combinedFacts.putAll(facts);
        // These snapshot fields are always safe to expose to data rules and avoid duplicating them in every adapter.
        combinedFacts.putIfAbsent("controllerType", machine.getControllerType());
        combinedFacts.putIfAbsent("dimension", machine.getDimension());
        combinedFacts.putIfAbsent("recipeMap", recipe.getRecipeMapId());
        combinedFacts.putIfAbsent("recipeCategory", recipe.getCategory());
        if (target != null) combinedFacts.putIfAbsent("targetKey", RecipeFingerprint.describeKey(target));
        this.facts = Collections.unmodifiableMap(combinedFacts);
        this.hiddenOutputCount = hiddenOutputCount;
        this.tokenSlots = tokenSlots;
    }

    public NormalizedRecipe getRecipe() {
        return recipe;
    }

    public MachineCapabilityProfile getMachine() {
        return machine;
    }

    public AEKey getTarget() {
        return target;
    }

    public PlanningMode getPlanningMode() {
        return planningMode;
    }

    public Set<String> getTags() {
        return tags;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public Object getFact(String key) {
        return facts.get(key);
    }

    public boolean hasCapability(String capability) {
        return machine.hasCapability(capability);
    }

    public int getHiddenOutputCount() {
        return hiddenOutputCount;
    }

    public int getTokenSlots() {
        return tokenSlots;
    }

    RuleContext withTags(Set<String> updatedTags) {
        return new RuleContext(recipe, machine, target, planningMode, updatedTags, facts, hiddenOutputCount, tokenSlots);
    }
}
