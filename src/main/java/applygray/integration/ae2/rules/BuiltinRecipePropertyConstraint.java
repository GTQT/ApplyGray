package applygray.integration.ae2.rules;

import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.NormalizedRecipe;

/**
 * Conservative checks for GregTech's built-in recipe properties. Values that cannot be proven by the immutable
 * machine snapshot are rejected before a virtual pattern reaches AE2 planning.
 */
final class BuiltinRecipePropertyConstraint {

    private static final String RULE_ID = "core.recipe-property";

    private BuiltinRecipePropertyConstraint() {
    }

    static void evaluate(RuleContext context, RuleDecision decision) {
        NormalizedRecipe recipe = context.getRecipe();
        MachineCapabilityProfile machine = context.getMachine();

        if (recipe.getEUt() > 0 && recipe.getEUt() > machine.getMaxVoltage()) {
            denySafety(decision, "VOLTAGE_TOO_LOW");
        }
        checkCleanroom(recipe, machine, decision);
        checkDimension(recipe, machine, decision);
        requireNumeric(recipe, machine, decision, "temperature", "temperature", "TEMPERATURE_TOO_LOW");
        requireNumeric(recipe, machine, decision, "heat", "heat", "HEAT_TOO_LOW");
        requireNumeric(recipe, machine, decision, "computation_per_tick", "computationPerTick",
                "COMPUTATION_TOO_LOW");
        requireNumeric(recipe, machine, decision, "eu_to_start", "energyCapacity", "FUSION_START_ENERGY_TOO_LOW");

        if (recipe.hasProperty("total_computation") && !machine.hasCapability("computation")) {
            denySafety(decision, "MISSING_CAPABILITY_COMPUTATION");
        }
        requireCapabilityForProperty(recipe, machine, decision, "scan", "scan");
        requireCapabilityForProperty(recipe, machine, decision, "research", "research");
        requireCapabilityForProperty(recipe, machine, decision, "primitive_property", "primitive");
        requireCapabilityForProperty(recipe, machine, decision, "explosives", "implosion");
        requireCapabilityForProperty(recipe, machine, decision, "fog_multistep", "fog");
        requireCapabilityForProperty(recipe, machine, decision, "fog_plasma_tier", "fog");
        requireCapabilityForProperty(recipe, machine, decision, "fog_upgrade_name", "fog");
    }

    private static void checkCleanroom(NormalizedRecipe recipe, MachineCapabilityProfile machine,
                                       RuleDecision decision) {
        if (!recipe.hasProperty("cleanroom")) return;
        String required = recipe.getCleanroomRequirement();
        if (required == null || !machine.hasCleanroomType(required)) {
            denySafety(decision, "CLEANROOM_REQUIREMENT_UNMET");
        }
    }

    private static void checkDimension(NormalizedRecipe recipe, MachineCapabilityProfile machine,
                                       RuleDecision decision) {
        if (!recipe.hasProperty("dimension")) return;
        if (machine.getDimension() == Integer.MIN_VALUE || !recipe.isDimensionAllowed(machine.getDimension())) {
            denySafety(decision, "DIMENSION_REQUIREMENT_UNMET");
        }
    }

    private static void requireNumeric(NormalizedRecipe recipe, MachineCapabilityProfile machine,
                                       RuleDecision decision, String property, String fact, String failureCode) {
        if (!recipe.hasProperty(property)) return;
        Long required = recipe.getNumericProperty(property);
        Long available = machine.getNumericFact(fact);
        if (required == null || available == null || available < required) {
            denySafety(decision, failureCode);
        }
    }

    private static void requireCapabilityForProperty(NormalizedRecipe recipe, MachineCapabilityProfile machine,
                                                     RuleDecision decision, String property, String capability) {
        if (recipe.hasProperty(property) && !machine.hasCapability(capability)) {
            denySafety(decision, "MISSING_CAPABILITY_" + capability.toUpperCase());
        }
    }

    private static void denySafety(RuleDecision decision, String code) {
        decision.deny(RULE_ID, code, true);
    }
}
