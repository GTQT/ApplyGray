package applygray.integration.ae2.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleSetLoaderTest {

    @TempDir
    Path rulesDirectory;

    @Test
    void rejectsUnknownRuleFields() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "rules": [
                    { "id": "test.rule", "effects": [{ "tag": "accepted" }], "typo": true }
                  ]
                }
                """);

        assertThrows(IOException.class, () -> RuleSetLoader.load(rulesDirectory));
    }

    @Test
    void rejectsAmbiguousPredicateStructure() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "rules": [
                    {
                      "id": "test.rule",
                      "when": { "all": [{ "recipeMap": "assembler" }], "category": "crafting" },
                      "effects": [{ "tag": "accepted" }]
                    }
                  ]
                }
                """);

        assertThrows(IOException.class, () -> RuleSetLoader.load(rulesDirectory));
    }

    @Test
    void rejectsFractionalIntegerSettings() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "rules": [
                    { "id": "test.rule", "priority": 1.5, "effects": [{ "tag": "accepted" }] }
                  ]
                }
                """);

        assertThrows(IOException.class, () -> RuleSetLoader.load(rulesDirectory));
    }

    @Test
    void acceptsPropertyAndFactSetPredicates() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "rules": [
                    {
                      "id": "test.rule",
                      "when": {
                        "all": [
                          { "property": { "key": "temperature", "in": ["1200", "1800"] } },
                          { "fact": { "key": "targetStoredAmount", "in": [0, 64] } }
                        ]
                      },
                      "effects": [{ "tag": "accepted" }]
                    }
                  ]
                }
                """);

        assertEquals(1, RuleSetLoader.load(rulesDirectory).getRuleCount());
    }

    @Test
    void acceptsCollectionMembershipFacts() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "rules": [
                    {
                      "id": "test.material-route",
                      "when": { "fact": { "key": "inputMaterials", "contains": "Polyethylene" } },
                      "effects": [{ "tag": "accepted" }]
                    }
                  ]
                }
                """);

        assertEquals(1, RuleSetLoader.load(rulesDirectory).getRuleCount());
    }

    @Test
    void mergesPlanningBudgetFieldsByPriority() throws IOException {
        Files.writeString(rulesDirectory.resolve("base.json"), """
                {
                  "version": 1,
                  "planningBudget": {
                    "priority": 10,
                    "maxRecipesPerTarget": 64,
                    "maxPlannerStatesPerTarget": 2048,
                    "onExhaustion": "REJECT",
                    "cycleSafetyOnExhaustion": "FALLBACK_NORMAL"
                  },
                  "rules": []
                }
                """);
        Files.writeString(rulesDirectory.resolve("override.json"), """
                {
                  "version": 1,
                  "planningBudget": { "priority": 20, "maxRecipesPerTarget": 128 },
                  "rules": []
                }
                """);

        PlanningBudget budget = RuleSetLoader.load(rulesDirectory).getPlanningBudget();

        assertEquals(128, budget.getMaxRecipesPerTarget());
        assertEquals(2048, budget.getMaxPlannerStatesPerTarget());
        assertEquals(BudgetExhaustionPolicy.REJECT, budget.getExhaustionPolicy());
        assertEquals(CycleSafetyExhaustionPolicy.FALLBACK_NORMAL, budget.getCycleSafetyExhaustionPolicy());
    }

    @Test
    void rejectsNonPositivePlanningBudgetValues() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "planningBudget": { "maxSccNodes": 0 },
                  "rules": []
                }
                """);

        assertThrows(IOException.class, () -> RuleSetLoader.load(rulesDirectory));
    }

    @Test
    void acceptsPlanningModeEffect() throws IOException {
        writeRules("""
                {
                  "version": 1,
                  "rules": [
                    { "id": "test.profile", "effects": [{ "planningMode": "SAFE_FIRST" }] }
                  ]
                }
                """);

        assertEquals(1, RuleSetLoader.load(rulesDirectory).getRuleCount());
    }

    private void writeRules(String contents) throws IOException {
        Files.writeString(rulesDirectory.resolve("rules.json"), contents);
    }
}
