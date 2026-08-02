package applygray.integration.ae2.rules;

import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.NormalizedRecipe;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSetConstraintTest {

    @Test
    void declaredThirdPartyPropertyReachesAdapterWithoutUnknownPropertyRejection() {
        RuleDecision decision = RuleSet.empty().evaluate(createContext(), List.of(new RecipePatternConstraint() {
            @Override
            public boolean supportsRecipeProperty(NormalizedRecipe recipe, String propertyKey) {
                return "test_third_party_property".equals(propertyKey);
            }

            @Override
            public void evaluate(RuleContext context, RuleDecision result) {
                result.tag("test.adapter", "third-party-property-evaluated");
            }
        }));

        assertTrue(decision.getExplanation().contains("test.adapter:tag:third-party-property-evaluated"));
        assertFalse(decision.getExplanation().contains("core.safety:safety-deny:UNKNOWN_RECIPE_PROPERTY"));
    }

    @Test
    void constraintFailureBecomesSafetyRejection() {
        RuleDecision decision = RuleSet.empty().evaluate(createContext(), List.of(new RecipePatternConstraint() {
            @Override
            public boolean supportsRecipeProperty(NormalizedRecipe recipe, String propertyKey) {
                return true;
            }

            @Override
            public void evaluate(RuleContext context, RuleDecision result) {
                throw new IllegalStateException("test failure");
            }
        }));

        assertTrue(decision.getExplanation().contains("core.safety:safety-deny:RECIPE_PATTERN_CONSTRAINT_FAILED"));
    }

    @Test
    void propertySupportFailureBecomesSafetyRejection() {
        RuleDecision decision = RuleSet.empty().evaluate(createContext(), List.of(new RecipePatternConstraint() {
            @Override
            public boolean supportsRecipeProperty(NormalizedRecipe recipe, String propertyKey) {
                throw new IllegalStateException("test failure");
            }

            @Override
            public void evaluate(RuleContext context, RuleDecision result) {
            }
        }));

        assertTrue(decision.getExplanation().contains("core.safety:safety-deny:RECIPE_PATTERN_CONSTRAINT_FAILED"));
    }

    private static RuleContext createContext() {
        return new RuleContext(createRecipe(), createMachine(), null, PlanningMode.SAFE_FIRST, Set.of(), Map.of(),
                0, 0);
    }

    private static NormalizedRecipe createRecipe() {
        try {
            Constructor<NormalizedRecipe> constructor = NormalizedRecipe.class.getDeclaredConstructor(String.class,
                    String.class, String.class, int.class, List.class, List.class, List.class, List.class,
                    List.class, List.class, Map.class, Map.class, Set.class, Set.class, Set.class, String.class,
                    String.class, long.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance("test_rule_property_map", "test-fingerprint", "test-content-version", 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    Map.of("test_third_party_property", "value"), Map.of(), Set.of("test_third_party_property"),
                    Set.of(), Set.of(), null, "", 0L, 1);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not construct normalized recipe test fixture", exception);
        }
    }

    private static MachineCapabilityProfile createMachine() {
        try {
            Constructor<MachineCapabilityProfile> constructor = MachineCapabilityProfile.class.getDeclaredConstructor(
                    String.class, String.class, boolean.class, List.class, long.class, int.class, int.class,
                    int.class, int.class, int.class, Set.class, Set.class, Map.class);
            constructor.setAccessible(true);
            return constructor.newInstance("test-provider", "test-controller", true,
                    List.of("test_rule_property_map"), 0L, 0, 0, 0, 0, Integer.MIN_VALUE,
                    Set.of("structure"), Set.of("structure"), Map.of());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not construct machine capability test fixture", exception);
        }
    }
}
