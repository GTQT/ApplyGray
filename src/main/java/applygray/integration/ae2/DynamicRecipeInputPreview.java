package applygray.integration.ae2;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.crafting.execution.InputTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Supplies direct templates for frozen dynamic inputs without changing ordinary AE2 pattern semantics. */
public final class DynamicRecipeInputPreview {

    /** Templates created by the direct path, scoped to the AE2 calculation worker currently using them. */
    private static final ThreadLocal<Map<InputTemplate, AEKey>> EXACT_TEMPLATES =
            ThreadLocal.withInitial(IdentityHashMap::new);
    /** Nesting is normally one deep, but a stack keeps the extraction scope robust across nested AE2 calls. */
    private static final ThreadLocal<Deque<AEKey>> EXACT_EXTRACTION_KEYS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private DynamicRecipeInputPreview() {
    }

    /**
     * Returns the single exact template for a frozen RecipeMap input or a processing pattern decoded by an ApplyGray
     * provider, or {@code null} when AE2 must retain its normal fuzzy/alternative lookup. Both source kinds are
     * immutable single-key inputs, so this does not discard a valid alternative.
     */
    @Nullable
    public static Iterable<InputTemplate> getExactTemplates(IPatternDetails.IInput input) {
        if (input == null) return null;

        AEKey key;
        long amount;
        if (input instanceof ExactDynamicRecipeInput exact && exact.isExactDynamicRecipeInput()) {
            key = exact.getExactDynamicRecipeInputKey();
            amount = exact.getExactDynamicRecipeInputAmount();
        } else if (ExactPatternInputRegistry.isExact(input)) {
            GenericStack[] options = input.possibleInputs();
            if (options.length != 1 || options[0] == null || options[0].amount() <= 0) return null;
            key = options[0].what();
            amount = options[0].amount();
        } else {
            return null;
        }
        if (key == null || amount <= 0) return null;

        InputTemplate template = new InputTemplate(key, amount);
        if (DynamicRecipePatternRegistry.isLargePatternCalculationActive()) {
            EXACT_TEMPLATES.get().put(template, key);
        }
        DynamicRecipePatternRegistry.recordExactDynamicInputTemplateBypass();
        return List.of(template);
    }

    /** Starts the narrow exact-key cache scope for one direct template extraction. */
    public static boolean beginExactDynamicInputExtraction(InputTemplate template, AEKey key) {
        AEKey exact = EXACT_TEMPLATES.get().get(template);
        if (exact == null || !exact.equals(key)) return false;

        EXACT_EXTRACTION_KEYS.get().addFirst(key);
        return true;
    }

    /** Ends a scope started by {@link #beginExactDynamicInputExtraction(InputTemplate, AEKey)}. */
    public static void endExactDynamicInputExtraction() {
        Deque<AEKey> keys = EXACT_EXTRACTION_KEYS.get();
        if (!keys.isEmpty()) keys.removeFirst();
        if (keys.isEmpty()) EXACT_EXTRACTION_KEYS.remove();
    }

    /** True only while AE2 is extracting the exact key selected by a frozen dynamic RecipeMap input. */
    public static boolean isExtractingExactDynamicInput(AEKey key) {
        if (key == null) return false;
        Deque<AEKey> keys = EXACT_EXTRACTION_KEYS.get();
        return !keys.isEmpty() && key.equals(keys.peekFirst());
    }

    /** Clears task-local template identities after a calculation finishes or before the next one begins. */
    public static void clearCalculationState() {
        EXACT_TEMPLATES.remove();
        EXACT_EXTRACTION_KEYS.remove();
    }
}
