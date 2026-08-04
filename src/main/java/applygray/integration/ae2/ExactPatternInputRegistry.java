package applygray.integration.ae2;

import ae2.api.crafting.IPatternDetails;
import ae2.crafting.pattern.AEProcessingPattern;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tracks the inputs of AE2 processing patterns that were decoded by ApplyGray pattern providers.
 *
 * <p>AE2's own processing-pattern inputs are always single exact keys ({@link AEProcessingPattern} stores one
 * key per slot and validates candidates with exact equality). Registering them lets the shared exact-template
 * shortcut in {@link DynamicRecipeInputPreview} bypass AE2's fuzzy variant enumeration for those patterns, exactly
 * like frozen RecipeMap inputs. Patterns decoded by other mods' providers are never registered.</p>
 *
 * <p>Entries are weakly referenced because providers rebuild their decoded pattern lists frequently; stale input
 * instances become collectible once their decoded pattern is dropped.</p>
 */
public final class ExactPatternInputRegistry {

    private static final Set<IPatternDetails.IInput> EXACT_INPUTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<IPatternDetails> EXACT_PATTERNS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private ExactPatternInputRegistry() {
    }

    /**
     * Registers the pattern itself and every one of its inputs when it is an AE2 processing pattern. Crafting
     * patterns and any other details type are ignored because their inputs may legitimately accept alternatives.
     */
    public static void registerPattern(IPatternDetails details) {
        if (!(details instanceof AEProcessingPattern)) return;
        EXACT_PATTERNS.add(details);
        for (IPatternDetails.IInput input : details.getInputs()) {
            registerInput(input);
        }
    }

    /** Registers one input directly. Used by {@link #registerPattern} and by tests. */
    public static void registerInput(IPatternDetails.IInput input) {
        EXACT_INPUTS.add(input);
    }

    /** True when the input belongs to a processing pattern decoded by an ApplyGray provider. */
    public static boolean isExact(IPatternDetails.IInput input) {
        return EXACT_INPUTS.contains(input);
    }

    /** True when the pattern itself was decoded by an ApplyGray provider. */
    public static boolean isRegisteredPattern(IPatternDetails details) {
        return EXACT_PATTERNS.contains(details);
    }

    /** Removes all tracked patterns and inputs. Only used by tests. */
    static void clear() {
        EXACT_PATTERNS.clear();
        EXACT_INPUTS.clear();
    }
}
