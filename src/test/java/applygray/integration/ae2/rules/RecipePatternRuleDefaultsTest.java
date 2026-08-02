package applygray.integration.ae2.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePatternRuleDefaultsTest {

    @TempDir
    Path rulesDirectory;

    @Test
    void loadsRuleDrivenFallbackPolicies() throws IOException {
        try (InputStream defaults = getClass().getResourceAsStream(
                "/assets/applygray/recipe-pattern-rules/defaults.json")) {
            assertNotNull(defaults);
            Files.copy(defaults, rulesDirectory.resolve("defaults.json"));
        }

        RuleSet rules = RuleSetLoader.load(rulesDirectory);

        assertEquals(8, rules.getPlanningBudget().getMaxDynamicCandidatesForCost());
        assertEquals(4096, rules.getPlanningBudget().getMaxStandaloneRouteExpansionsPerCalculation());
        assertEquals(8000, rules.getPlanningBudget().getMaxStandaloneRouteCalculationMillis());
        assertEquals(CycleSafetyExhaustionPolicy.RUNTIME_RECOVERY,
                rules.getPlanningBudget().getCycleSafetyExhaustionPolicy());
        assertTrue(rules.getRuleCount() >= 9);
    }

    @Test
    void upgradesLegacyDefaultsAndAddsMissingRules() throws Exception {
        Path defaults = rulesDirectory.resolve("defaults.json");
        Files.writeString(defaults, """
                {
                  "version": 1,
                  "planningBudget": {
                    "maxDynamicCandidatesForCost": 2,
                    "maxPersistedPatternsPerProvider": 64
                  },
                  "rules": [
                    { "id": "core.elemental-compound-synthesis", "effects": [] }
                  ]
                }
                """);

        migrateDefaults(defaults);

        RuleSet rules = RuleSetLoader.load(rulesDirectory);
        String migrated = Files.readString(defaults);
        assertEquals(8, rules.getPlanningBudget().getMaxDynamicCandidatesForCost());
        assertEquals(8, rules.getPlanningBudget().getMaxRefinedCandidates());
        assertEquals(512, rules.getPlanningBudget().getMaxSccNodes());
        assertEquals(2048, rules.getPlanningBudget().getMaxSccEdges());
        assertEquals(1000, rules.getPlanningBudget().getMaxSccAnalysisMillis());
        assertEquals(4096, rules.getPlanningBudget().getMaxStandaloneRouteExpansionsPerCalculation());
        assertEquals(8000, rules.getPlanningBudget().getMaxStandaloneRouteCalculationMillis());
        assertEquals(CycleSafetyExhaustionPolicy.RUNTIME_RECOVERY,
                rules.getPlanningBudget().getCycleSafetyExhaustionPolicy());
        assertTrue(rules.getRuleCount() >= 9);
        assertTrue(migrated.contains("cycleSafetyOnExhaustion"));
        assertTrue(migrated.contains("maxStandaloneRouteExpansionsPerCalculation"));
        assertTrue(migrated.contains("maxStandaloneRouteCalculationMillis"));
        assertFalse(migrated.contains("maxPersistedPatternsPerProvider"));
        assertTrue(migrated.contains("core.polymer-dust-fallback"));
        assertTrue(migrated.contains("core.chemical-product-ingot-fallback"));
        assertTrue(migrated.contains("core.primary-compound-synthesis"));
        assertFalse(migrated.contains("core.elemental-compound-synthesis"));
        assertTrue(migrated.contains("core.cutter-form-change"));
    }

    @Test
    void preservesExplicitDynamicCandidateBudget() throws Exception {
        Path defaults = rulesDirectory.resolve("defaults.json");
        Files.writeString(defaults, """
                {
                  "version": 1,
                  "planningBudget": { "maxDynamicCandidatesForCost": 4 },
                  "rules": []
                }
                """);

        migrateDefaults(defaults);

        assertEquals(4, RuleSetLoader.load(rulesDirectory).getPlanningBudget().getMaxDynamicCandidatesForCost());
    }

    @Test
    void preservesExplicitRefinementAndSccBudgets() throws Exception {
        Path defaults = rulesDirectory.resolve("defaults.json");
        Files.writeString(defaults, """
                {
                  "version": 1,
                  "planningBudget": {
                    "maxRefinedCandidates": 4,
                    "maxSccNodes": 256,
                    "maxSccEdges": 768,
                    "maxSccAnalysisMillis": 400
                  },
                  "rules": []
                }
                """);

        migrateDefaults(defaults);

        PlanningBudget budget = RuleSetLoader.load(rulesDirectory).getPlanningBudget();
        assertEquals(4, budget.getMaxRefinedCandidates());
        assertEquals(256, budget.getMaxSccNodes());
        assertEquals(768, budget.getMaxSccEdges());
        assertEquals(400, budget.getMaxSccAnalysisMillis());
    }

    @Test
    void preservesExplicitCycleSafetyPolicy() throws Exception {
        Path defaults = rulesDirectory.resolve("defaults.json");
        Files.writeString(defaults, """
                {
                  "version": 1,
                  "planningBudget": { "cycleSafetyOnExhaustion": "FALLBACK_NORMAL" },
                  "rules": []
                }
                """);

        migrateDefaults(defaults);

        assertEquals(CycleSafetyExhaustionPolicy.FALLBACK_NORMAL,
                RuleSetLoader.load(rulesDirectory).getPlanningBudget().getCycleSafetyExhaustionPolicy());
    }

    private static void migrateDefaults(Path defaults) throws ReflectiveOperationException {
        var method = RecipePatternRules.class.getDeclaredMethod("migrateDefaultRules", Path.class);
        method.setAccessible(true);
        method.invoke(null, defaults);
    }
}
