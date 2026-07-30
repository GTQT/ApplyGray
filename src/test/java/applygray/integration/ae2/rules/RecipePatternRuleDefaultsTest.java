package applygray.integration.ae2.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(rules.getRuleCount() >= 9);
    }

    @Test
    void upgradesLegacyDefaultsAndAddsMissingRules() throws Exception {
        Path defaults = rulesDirectory.resolve("defaults.json");
        Files.writeString(defaults, """
                {
                  "version": 1,
                  "planningBudget": { "maxDynamicCandidatesForCost": 2 },
                  "rules": []
                }
                """);

        migrateDefaults(defaults);

        RuleSet rules = RuleSetLoader.load(rulesDirectory);
        String migrated = Files.readString(defaults);
        assertEquals(8, rules.getPlanningBudget().getMaxDynamicCandidatesForCost());
        assertTrue(rules.getRuleCount() >= 9);
        assertTrue(migrated.contains("core.polymer-dust-fallback"));
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

    private static void migrateDefaults(Path defaults) throws ReflectiveOperationException {
        var method = RecipePatternRules.class.getDeclaredMethod("migrateDefaultRules", Path.class);
        method.setAccessible(true);
        method.invoke(null, defaults);
    }
}
