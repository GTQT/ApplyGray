package applygray.integration.ae2;

import applygray.integration.ae2.recipe.RecipeBinding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleMemoryStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsOnlyTheExactVersionedRecipeBinding() throws Exception {
        Path file = temporaryDirectory.resolve("cycle-memory.json");
        RecipeBinding observed = binding("rules-a", "machine-a", "map-a");
        CycleMemoryStore first = CycleMemoryStore.forPath(file);

        assertTrue(first.record(observed, "cycle-one"));
        first.flush();

        CycleMemoryStore reloaded = CycleMemoryStore.forPath(file);
        assertTrue(reloaded.isRemembered(observed));
        assertFalse(reloaded.isRemembered(binding("rules-b", "machine-a", "map-a")));
        assertFalse(reloaded.isRemembered(binding("rules-a", "machine-b", "map-a")));
        assertFalse(reloaded.isRemembered(binding("rules-a", "machine-a", "map-b")));
        assertTrue(Files.readString(file).contains("cycle-one"));
    }

    private static RecipeBinding binding(String rules, String machine, String mapContent) {
        return new RecipeBinding("extractor", RecipeBinding.FINGERPRINT_VERSION, "recipe-fingerprint",
                mapContent, "target-key", RecipeBinding.NORMALIZATION_VERSION, rules, machine);
    }
}
