package applygray.integration.ae2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicRecipePatternRegistryCacheTest {

    @Test
    void recoveryAppendPreservesExistingPatternsAndAddsOnlyMissingRecipeKeys() {
        Route originalFirst = new Route("first", "original-first");
        Route originalSecond = new Route("second", "original-second");
        List<Route> existing = List.of(originalFirst, originalSecond);

        List<Route> merged = DynamicRecipePatternRegistry.appendMissingByKey(existing, List.of(
                new Route("second", "replacement-must-not-win"),
                new Route("third", "new-third"),
                new Route("third", "duplicate-new-third")), Route::recipeKey);

        assertEquals(List.of(originalFirst, originalSecond, new Route("third", "new-third")), merged);
        assertSame(originalFirst, merged.get(0));
        assertSame(originalSecond, merged.get(1));
        assertThrows(UnsupportedOperationException.class, () -> merged.add(new Route("fourth", "new-fourth")));
    }

    @Test
    void recoveryAppendReturnsExistingListWhenNoRecipeKeyIsMissing() {
        List<Route> existing = List.of(new Route("first", "original"));

        List<Route> merged = DynamicRecipePatternRegistry.appendMissingByKey(existing,
                List.of(new Route("first", "replacement")), Route::recipeKey);

        assertSame(existing, merged);
    }

    private record Route(String recipeKey, String marker) {}
}
