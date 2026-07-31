package applygray.integration.ae2.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A normalized physical recipe projected into exactly one AE-visible output. */
public final class TargetedRecipe {

    private final AEKey target;
    private final long targetAmount;
    private final List<GenericStack> patternInputs;
    private final List<List<GenericStack>> alternatives;
    private final List<GenericStack> hiddenActualOutputs;
    private final RecipeBinding binding;
    private final NonConsumableTokenLayout tokenLayout;
    private final List<String> explanation;

    public TargetedRecipe(AEKey target, long targetAmount, List<GenericStack> patternInputs,
                          List<List<GenericStack>> alternatives, List<GenericStack> hiddenActualOutputs,
                          RecipeBinding binding, NonConsumableTokenLayout tokenLayout, List<String> explanation) {
        if (target == null || targetAmount <= 0) {
            throw new IllegalArgumentException("A targeted recipe needs a positive deterministic target");
        }
        this.target = target;
        this.targetAmount = targetAmount;
        this.patternInputs = Collections.unmodifiableList(new ArrayList<>(patternInputs));
        List<List<GenericStack>> copiedAlternatives = new ArrayList<>(alternatives.size());
        for (List<GenericStack> options : alternatives) {
            copiedAlternatives.add(Collections.unmodifiableList(new ArrayList<>(options)));
        }
        this.alternatives = Collections.unmodifiableList(copiedAlternatives);
        this.hiddenActualOutputs = Collections.unmodifiableList(new ArrayList<>(hiddenActualOutputs));
        this.binding = binding;
        this.tokenLayout = tokenLayout == null ? NonConsumableTokenLayout.EMPTY : tokenLayout;
        this.explanation = Collections.unmodifiableList(new ArrayList<>(explanation));
    }

    public AEKey getTarget() {
        return target;
    }

    public long getTargetAmount() {
        return targetAmount;
    }

    public List<GenericStack> getPatternInputs() {
        return patternInputs;
    }

    public List<List<GenericStack>> getAlternatives() {
        return alternatives;
    }

    public List<GenericStack> getHiddenActualOutputs() {
        return hiddenActualOutputs;
    }

    public RecipeBinding getBinding() {
        return binding;
    }

    public NonConsumableTokenLayout getTokenLayout() {
        return tokenLayout;
    }

    public List<String> getExplanation() {
        return explanation;
    }

    public List<GenericStack> getAeOutputs() {
        return Collections.singletonList(new GenericStack(target, targetAmount));
    }
}
