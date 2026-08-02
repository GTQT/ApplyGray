package applygray.integration.ae2;

import applygray.integration.ae2.recipe.NonConsumableTokenLayout;
import applygray.integration.ae2.recipe.RecipeBinding;
import applygray.integration.ae2.rules.CyclePolicy;
import applygray.integration.ae2.rules.PlanningMode;

import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.google.common.math.LongMath;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable virtual processing pattern generated for a RecipeMap on demand.
 * Its data only uses Supergiant's GenericStack representation.
 */
public final class DynamicRecipePatternDetails implements IPatternDetails {

    private final String recipeKey;
    private final String recipeMapName;
    private final Input[] inputs;
    private final List<GenericStack> outputs;
    private final int circuitConfiguration;
    private final long rawMaterialCost;
    private final int stepCost;
    private final DynamicRecipePatternRegistry.CandidateRoutePriority routePriority;
    private final long ruleRoutePriority;
    private final CyclePolicy cyclePolicy;
    private final long cycleRiskPenalty;
    private final int maxPatternsForTarget;
    private final PlanningMode planningMode;
    private final String pinGroup;
    private final List<GenericStack> hiddenActualOutputs;
    private final RecipeBinding recipeBinding;
    private final NonConsumableTokenLayout tokenLayout;
    private final List<String> explanation;
    /** Number of ordinary RecipeMap executions represented by one AE2 pattern execution. */
    private final int recipeRunsPerPattern;
    /** Encoded AE2 item data is only needed once a plan or display actually asks for this detail. */
    @Nullable private volatile AEItemKey definition;

    DynamicRecipePatternDetails(String recipeKey, String recipeMapName,
                                List<GenericStack> inputs, List<List<GenericStack>> alternatives,
                                List<GenericStack> outputs, int circuitConfiguration,
                                long rawMaterialCost, int stepCost,
                                DynamicRecipePatternRegistry.CandidateRoutePriority routePriority,
                                long ruleRoutePriority, CyclePolicy cyclePolicy, long cycleRiskPenalty,
                                int maxPatternsForTarget, PlanningMode planningMode, String pinGroup,
                                List<GenericStack> hiddenActualOutputs, RecipeBinding recipeBinding,
                                NonConsumableTokenLayout tokenLayout, List<String> explanation) {
        this(recipeKey, recipeMapName, inputs, alternatives, outputs, circuitConfiguration,
                rawMaterialCost, stepCost, routePriority, ruleRoutePriority, cyclePolicy, cycleRiskPenalty,
                maxPatternsForTarget, planningMode, pinGroup, hiddenActualOutputs, recipeBinding, tokenLayout,
                explanation, 1);
    }

    private DynamicRecipePatternDetails(String recipeKey, String recipeMapName,
                                        List<GenericStack> inputs, List<List<GenericStack>> alternatives,
                                        List<GenericStack> outputs, int circuitConfiguration,
                                        long rawMaterialCost, int stepCost,
                                        DynamicRecipePatternRegistry.CandidateRoutePriority routePriority,
                                        long ruleRoutePriority, CyclePolicy cyclePolicy, long cycleRiskPenalty,
                                        int maxPatternsForTarget, PlanningMode planningMode, String pinGroup,
                                        List<GenericStack> hiddenActualOutputs, RecipeBinding recipeBinding,
                                        NonConsumableTokenLayout tokenLayout, List<String> explanation,
                                        int recipeRunsPerPattern) {
        if (inputs.isEmpty() || outputs.size() != 1 || recipeBinding == null) {
            throw new IllegalArgumentException("Dynamic RecipeMap pattern requires inputs, one output, and a binding");
        }

        this.recipeKey = recipeKey;
        this.recipeMapName = recipeMapName;
        this.inputs = createInputs(inputs, alternatives);
        this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
        this.circuitConfiguration = circuitConfiguration;
        this.rawMaterialCost = rawMaterialCost;
        this.stepCost = stepCost;
        this.routePriority = routePriority == null ?
                DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL : routePriority;
        this.ruleRoutePriority = ruleRoutePriority;
        this.cyclePolicy = cyclePolicy == null ? CyclePolicy.BREAK_AT_EXTERNAL_SEED : cyclePolicy;
        this.cycleRiskPenalty = Math.max(0, cycleRiskPenalty);
        this.maxPatternsForTarget = Math.max(1, maxPatternsForTarget);
        this.planningMode = planningMode == null ? PlanningMode.STOCK_FIRST : planningMode;
        this.pinGroup = pinGroup == null ? "" : pinGroup;
        this.hiddenActualOutputs = Collections.unmodifiableList(new ArrayList<>(hiddenActualOutputs));
        this.recipeBinding = recipeBinding;
        this.tokenLayout = tokenLayout == null ? NonConsumableTokenLayout.EMPTY : tokenLayout;
        this.explanation = Collections.unmodifiableList(new ArrayList<>(explanation));
        this.recipeRunsPerPattern = Math.max(1, recipeRunsPerPattern);
    }

    public String getRecipeKey() {
        return recipeKey;
    }

    public String getRecipeMapName() {
        return recipeMapName;
    }

    public int getCircuitConfiguration() {
        return circuitConfiguration;
    }

    public long getRawMaterialCost() {
        return rawMaterialCost;
    }

    public int getStepCost() {
        return stepCost;
    }

    public DynamicRecipePatternRegistry.CandidateRoutePriority getRoutePriority() {
        return routePriority;
    }

    public long getRuleRoutePriority() {
        return ruleRoutePriority;
    }

    public CyclePolicy getCyclePolicy() {
        return cyclePolicy;
    }

    public long getCycleRiskPenalty() {
        return cycleRiskPenalty;
    }

    public int getMaxPatternsForTarget() {
        return maxPatternsForTarget;
    }

    public PlanningMode getPlanningMode() {
        return planningMode;
    }

    public String getPinGroup() {
        return pinGroup;
    }

    public List<GenericStack> getHiddenActualOutputs() {
        return hiddenActualOutputs;
    }

    public RecipeBinding getRecipeBinding() {
        return recipeBinding;
    }

    public NonConsumableTokenLayout getTokenLayout() {
        return tokenLayout;
    }

    public List<String> getExplanation() {
        return explanation;
    }

    /** Returns the number of normal RecipeMap recipe executions represented by this detail. */
    public int getRecipeRunsPerPattern() {
        return recipeRunsPerPattern;
    }

    /** Large details are task-local and must not be expanded a second time. */
    public boolean isLargePattern() {
        return recipeRunsPerPattern > 1;
    }

    /** Returns whether this detail consumes the requested key as one of its own inputs. */
    public boolean consumes(AEKey requested) {
        if (requested == null) return false;
        for (Input input : inputs) {
            for (GenericStack possibleInput : input.possibleInputs()) {
                if (requested.equals(possibleInput.what())) return true;
            }
        }
        return false;
    }

    /**
     * Largest safe planning multiplier for this exact detail.
     *
     * <p>Pattern buffers aggregate equal keys across input slots and accept only signed-int stack amounts. The
     * calculation therefore uses the worst case for every input alternative, rather than checking each slot in
     * isolation. Non-consumable programmable-circuit tokens remain one token per large pattern execution.</p>
     */
    public int getMaximumLargePatternMultiplier() {
        if (isLargePattern()) return 1;

        Map<AEKey, Long> amountsByKey = new HashMap<>();
        boolean[] nonConsumableInputs = getNonConsumableInputSlots();
        for (int inputIndex = 0; inputIndex < inputs.length; inputIndex++) {
            if (nonConsumableInputs[inputIndex]) continue;
            Input input = inputs[inputIndex];
            for (GenericStack possibleInput : input.possibleInputs()) {
                addCapacityAmount(amountsByKey, possibleInput.what(), input.getMultiplier());
            }
        }
        for (GenericStack output : outputs) {
            addCapacityAmount(amountsByKey, output.what(), output.amount());
        }
        for (GenericStack hiddenOutput : hiddenActualOutputs) {
            addCapacityAmount(amountsByKey, hiddenOutput.what(), hiddenOutput.amount());
        }

        int maximum = Integer.MAX_VALUE;
        for (long amount : amountsByKey.values()) {
            if (amount <= 0) return 1;
            maximum = Math.min(maximum, (int) (Integer.MAX_VALUE / amount));
        }
        return Math.max(1, maximum);
    }

    /**
     * Creates a non-published large-pattern detail. Consumable inputs and all advertised outputs scale together.
     * AE2 may use a rounded-up final execution, which intentionally creates normal recipe surplus.
     */
    @Nullable
    public DynamicRecipePatternDetails createLargePattern(int multiplier) {
        if (multiplier <= 1 || multiplier > getMaximumLargePatternMultiplier()) return null;

        boolean[] nonConsumableInputs = getNonConsumableInputSlots();
        List<GenericStack> scaledInputs = new ArrayList<>(inputs.length);
        List<List<GenericStack>> scaledAlternatives = new ArrayList<>(inputs.length);
        for (int inputIndex = 0; inputIndex < inputs.length; inputIndex++) {
            Input input = inputs[inputIndex];
            long amount = nonConsumableInputs[inputIndex] ? input.getMultiplier() :
                    multiplyPatternAmount(input.getMultiplier(), multiplier);
            if (amount <= 0) return null;

            GenericStack[] options = input.possibleInputs();
            List<GenericStack> alternatives = new ArrayList<>(options.length);
            for (GenericStack option : options) {
                alternatives.add(new GenericStack(option.what(), amount));
            }
            scaledInputs.add(new GenericStack(options[0].what(), amount));
            scaledAlternatives.add(alternatives);
        }

        List<GenericStack> scaledOutputs = scaleStacks(outputs, multiplier);
        List<GenericStack> scaledHiddenOutputs = scaleStacks(hiddenActualOutputs, multiplier);
        if (scaledOutputs == null || scaledHiddenOutputs == null) return null;

        return new DynamicRecipePatternDetails(recipeKey + "#batch=" + multiplier, recipeMapName,
                scaledInputs, scaledAlternatives, scaledOutputs, circuitConfiguration,
                LongMath.saturatedMultiply(rawMaterialCost, multiplier), scaleStepCost(stepCost, multiplier),
                routePriority, ruleRoutePriority, cyclePolicy, cycleRiskPenalty, maxPatternsForTarget,
                planningMode, pinGroup, scaledHiddenOutputs, recipeBinding, tokenLayout, explanation, multiplier);
    }

    boolean matchesRecipeDefinition(String expectedRecipeMapName, List<GenericStack> expectedPrimaryInputs,
                                    List<List<GenericStack>> expectedAlternatives,
                                    List<GenericStack> expectedOutputs, int expectedCircuitConfiguration,
                                    long expectedRawMaterialCost, int expectedStepCost,
                                    DynamicRecipePatternRegistry.CandidateRoutePriority expectedRoutePriority,
                                    long expectedRuleRoutePriority, CyclePolicy expectedCyclePolicy,
                                    long expectedCycleRiskPenalty, int expectedMaxPatternsForTarget,
                                    PlanningMode expectedPlanningMode, String expectedPinGroup,
                                    List<GenericStack> expectedHiddenActualOutputs,
                                    RecipeBinding expectedBinding,
                                    NonConsumableTokenLayout expectedTokenLayout) {
        if (isLargePattern() || !recipeMapName.equals(expectedRecipeMapName) || !outputs.equals(expectedOutputs) ||
                circuitConfiguration != expectedCircuitConfiguration || rawMaterialCost != expectedRawMaterialCost ||
                stepCost != expectedStepCost || routePriority != expectedRoutePriority ||
                ruleRoutePriority != expectedRuleRoutePriority || cyclePolicy != expectedCyclePolicy ||
                cycleRiskPenalty != expectedCycleRiskPenalty ||
                maxPatternsForTarget != expectedMaxPatternsForTarget ||
                planningMode != expectedPlanningMode || !pinGroup.equals(expectedPinGroup == null ? "" : expectedPinGroup) ||
                !hiddenActualOutputs.equals(expectedHiddenActualOutputs) || !recipeBinding.equals(expectedBinding) ||
                !tokenLayout.equals(expectedTokenLayout)) {
            return false;
        }

        Input[] expectedInputs = createInputs(expectedPrimaryInputs, expectedAlternatives);
        if (inputs.length != expectedInputs.length) {
            return false;
        }
        for (int inputIndex = 0; inputIndex < inputs.length; inputIndex++) {
            Input actual = inputs[inputIndex];
            Input expected = expectedInputs[inputIndex];
            if (actual.multiplier != expected.multiplier ||
                    actual.possibleInputs.length != expected.possibleInputs.length) {
                return false;
            }
            for (int optionIndex = 0; optionIndex < actual.possibleInputs.length; optionIndex++) {
                if (!actual.possibleInputs[optionIndex].equals(expected.possibleInputs[optionIndex])) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean produces(AEKey requested) {
        for (GenericStack output : outputs) {
            if (output.what().equals(requested)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches Supergiant's recursive-cycle accounting for one requested key.
     *
     * <p>Every matching input alternative counts as consumption because AE2 may choose that alternative while
     * planning.</p>
     */
    public boolean netProduces(AEKey requested) {
        return getNetOutputAmount(requested) > 0;
    }

    /** Returns the requested key's output amount after subtracting every possible matching input. */
    public long getNetOutputAmount(AEKey requested) {
        return getNetOutputAmount(requested, inputs, outputs);
    }

    static boolean hasNetOutput(AEKey requested, List<GenericStack> primaryInputs,
                                List<List<GenericStack>> alternatives, List<GenericStack> outputs) {
        return getNetOutputAmount(requested, primaryInputs, alternatives, outputs) > 0;
    }

    static long getNetOutputAmount(AEKey requested, List<GenericStack> primaryInputs,
                                   List<List<GenericStack>> alternatives, List<GenericStack> outputs) {
        return getNetOutputAmount(requested, createInputs(primaryInputs, alternatives), outputs);
    }

    private static long getNetOutputAmount(AEKey requested, Input[] inputs, List<GenericStack> outputs) {
        long netOutput = 0;
        for (GenericStack output : outputs) {
            if (requested.matches(output)) {
                netOutput = LongMath.saturatedAdd(netOutput, output.amount());
            }
        }
        for (Input input : inputs) {
            for (GenericStack possibleInput : input.possibleInputs()) {
                if (requested.matches(possibleInput)) {
                    netOutput = LongMath.saturatedSubtract(netOutput,
                            LongMath.saturatedMultiply(possibleInput.amount(), input.getMultiplier()));
                    break;
                }
            }
        }
        return netOutput;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("RecipeKey", recipeKey);
        data.setString("RecipeMap", recipeMapName);
        data.setInteger("Circuit", circuitConfiguration);
        data.setLong("RawCost", rawMaterialCost);
        data.setInteger("StepCost", stepCost);
        data.setInteger("RoutePriority", routePriority.ordinal());
        data.setLong("RuleRoutePriority", ruleRoutePriority);
        data.setString("CyclePolicy", cyclePolicy.name());
        data.setLong("CycleRiskPenalty", cycleRiskPenalty);
        data.setInteger("RecipeRuns", recipeRunsPerPattern);
        data.setInteger("MaxPatterns", maxPatternsForTarget);
        data.setString("PlanningMode", planningMode.name());
        data.setString("PinGroup", pinGroup);
        data.setTag("Inputs", GenericStack.writeList(primaryInputs()));
        data.setTag("Outputs", GenericStack.writeList(outputs));
        data.setTag("HiddenOutputs", GenericStack.writeList(hiddenActualOutputs));
        data.setTag("Binding", recipeBinding.writeToNBT());
        data.setTag("TokenLayout", tokenLayout.writeToNBT());

        NBTTagList explanationList = new NBTTagList();
        for (String entry : explanation) {
            NBTTagCompound explanationData = new NBTTagCompound();
            explanationData.setString("Value", entry);
            explanationList.appendTag(explanationData);
        }
        data.setTag("Explanation", explanationList);

        NBTTagList alternativeList = new NBTTagList();
        for (Input input : inputs) {
            NBTTagCompound optionData = new NBTTagCompound();
            optionData.setTag("Options", GenericStack.writeList(List.of(input.possibleInputs())));
            alternativeList.appendTag(optionData);
        }
        data.setTag("Alternatives", alternativeList);
        return data;
    }

    @Nullable
    public static DynamicRecipePatternDetails readFromNBT(NBTTagCompound data) {
        if (!data.hasKey("RecipeKey", 8) || !data.hasKey("RecipeMap", 8) ||
                !data.hasKey("Binding", Constants.NBT.TAG_COMPOUND) ||
                !data.hasKey("TokenLayout", Constants.NBT.TAG_COMPOUND)) {
            return null;
        }

        List<GenericStack> inputs = nonNull(GenericStack.readList(data.getTagList("Inputs", 10)));
        List<GenericStack> outputs = nonNull(GenericStack.readList(data.getTagList("Outputs", 10)));
        if (inputs.isEmpty() || outputs.size() != 1) {
            return null;
        }

        RecipeBinding binding = RecipeBinding.readFromNBT(data.getCompoundTag("Binding"));
        NonConsumableTokenLayout tokenLayout = NonConsumableTokenLayout.readFromNBT(
                data.getCompoundTag("TokenLayout"));
        if (binding == null || tokenLayout == null) return null;

        List<List<GenericStack>> alternatives = new ArrayList<>();
        NBTTagList alternativeList = data.getTagList("Alternatives", 10);
        for (int i = 0; i < inputs.size(); i++) {
            if (i < alternativeList.tagCount()) {
                List<GenericStack> options = nonNull(GenericStack.readList(
                        alternativeList.getCompoundTagAt(i).getTagList("Options", 10)));
                alternatives.add(options.isEmpty() ? List.of(inputs.get(i)) : options);
            } else {
                alternatives.add(List.of(inputs.get(i)));
            }
        }

        List<GenericStack> hiddenOutputs = nonNull(GenericStack.readList(data.getTagList("HiddenOutputs", 10)));
        List<String> explanation = new ArrayList<>();
        NBTTagList explanationList = data.getTagList("Explanation", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < explanationList.tagCount(); i++) {
            explanation.add(explanationList.getCompoundTagAt(i).getString("Value"));
        }
        return new DynamicRecipePatternDetails(data.getString("RecipeKey"), data.getString("RecipeMap"),
                inputs, alternatives, outputs, data.getInteger("Circuit"), data.getLong("RawCost"),
                data.getInteger("StepCost"), readRoutePriority(data), data.getLong("RuleRoutePriority"),
                readCyclePolicy(data), data.getLong("CycleRiskPenalty"), Math.max(1, data.getInteger("MaxPatterns")),
                readPlanningMode(data), data.getString("PinGroup"),
                hiddenOutputs, binding, tokenLayout, explanation, Math.max(1, data.getInteger("RecipeRuns")));
    }

    private static DynamicRecipePatternRegistry.CandidateRoutePriority readRoutePriority(NBTTagCompound data) {
        if (!data.hasKey("RoutePriority", Constants.NBT.TAG_INT)) {
            return DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL;
        }
        int ordinal = data.getInteger("RoutePriority");
        DynamicRecipePatternRegistry.CandidateRoutePriority[] priorities =
                DynamicRecipePatternRegistry.CandidateRoutePriority.values();
        return ordinal >= 0 && ordinal < priorities.length ? priorities[ordinal] :
                DynamicRecipePatternRegistry.CandidateRoutePriority.GENERAL;
    }

    private static CyclePolicy readCyclePolicy(NBTTagCompound data) {
        if (!data.hasKey("CyclePolicy", Constants.NBT.TAG_STRING)) {
            return CyclePolicy.BREAK_AT_EXTERNAL_SEED;
        }
        try {
            return CyclePolicy.valueOf(data.getString("CyclePolicy"));
        } catch (IllegalArgumentException ignored) {
            return CyclePolicy.BREAK_AT_EXTERNAL_SEED;
        }
    }

    private static PlanningMode readPlanningMode(NBTTagCompound data) {
        if (!data.hasKey("PlanningMode", Constants.NBT.TAG_STRING)) return PlanningMode.STOCK_FIRST;
        try {
            return PlanningMode.valueOf(data.getString("PlanningMode"));
        } catch (IllegalArgumentException ignored) {
            return PlanningMode.STOCK_FIRST;
        }
    }

    @Override
    public AEItemKey getDefinition() {
        AEItemKey current = definition;
        if (current != null) return current;

        synchronized (this) {
            current = definition;
            if (current != null) return current;

            ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(
                    primaryInputs(), outputs, recipeMapName);
            current = AEItemKey.of(encoded);
            if (current == null) {
                throw new IllegalStateException("Could not create a definition for dynamic RecipeMap pattern " + recipeKey);
            }
            definition = current;
            return current;
        }
    }

    @Override
    public IInput[] getInputs() {
        return inputs.clone();
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    private List<GenericStack> primaryInputs() {
        List<GenericStack> result = new ArrayList<>(inputs.length);
        for (Input input : inputs) {
            GenericStack primary = input.possibleInputs()[0];
            result.add(new GenericStack(primary.what(), input.getMultiplier()));
        }
        return result;
    }

    private static Input[] createInputs(List<GenericStack> primary,
                                        List<List<GenericStack>> alternatives) {
        Input[] result = new Input[primary.size()];
        for (int i = 0; i < primary.size(); i++) {
            GenericStack fallback = primary.get(i);
            List<GenericStack> options = i < alternatives.size() ? alternatives.get(i) : List.of(fallback);
            if (options == null || options.isEmpty()) {
                options = List.of(fallback);
            }
            result[i] = new Input(options, fallback.amount());
        }
        return result;
    }

    /** Builds the normalized input view used for route scoring without encoding or materializing a pattern. */
    static IInput[] createScoringInputs(List<GenericStack> primary,
                                        List<List<GenericStack>> alternatives) {
        return createInputs(primary, alternatives);
    }

    private static List<GenericStack> nonNull(List<@Nullable GenericStack> source) {
        List<GenericStack> result = new ArrayList<>(source.size());
        for (GenericStack stack : source) {
            if (stack != null && stack.amount() > 0) {
                result.add(stack);
            }
        }
        return result;
    }

    private boolean[] getNonConsumableInputSlots() {
        boolean[] result = new boolean[inputs.length];
        for (NonConsumableTokenLayout.Slot slot : tokenLayout.getSlots()) {
            int index = slot.getInputIndex();
            if (index >= 0 && index < result.length) result[index] = true;
        }
        return result;
    }

    private static void addCapacityAmount(Map<AEKey, Long> amountsByKey, AEKey key, long amount) {
        if (key == null || amount <= 0) return;
        amountsByKey.merge(key, amount, LongMath::saturatedAdd);
    }

    private static long multiplyPatternAmount(long amount, int multiplier) {
        if (amount <= 0 || amount > Integer.MAX_VALUE / (long) multiplier) return -1;
        return amount * multiplier;
    }

    @Nullable
    private static List<GenericStack> scaleStacks(List<GenericStack> source, int multiplier) {
        List<GenericStack> result = new ArrayList<>(source.size());
        for (GenericStack stack : source) {
            if (stack == null) continue;
            long amount = multiplyPatternAmount(stack.amount(), multiplier);
            if (amount <= 0) return null;
            result.add(new GenericStack(stack.what(), amount));
        }
        return result;
    }

    private static int scaleStepCost(int value, int multiplier) {
        long scaled = LongMath.saturatedMultiply(Math.max(0, value), multiplier);
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }

    private static final class Input implements IInput, ExactDynamicRecipeInput {

        private final GenericStack[] possibleInputs;
        private final long multiplier;

        private Input(List<GenericStack> options, long fallbackMultiplier) {
            List<GenericStack> normalized = new ArrayList<>(options.size());
            long amount = Math.max(1, fallbackMultiplier);
            for (GenericStack option : options) {
                if (option == null || option.amount() <= 0) {
                    continue;
                }
                amount = Math.max(amount, option.amount());
                normalized.add(new GenericStack(option.what(), 1));
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Dynamic RecipeMap pattern contains an empty input");
            }
            this.possibleInputs = normalized.toArray(new GenericStack[0]);
            this.multiplier = amount;
        }

        @Override
        public GenericStack[] possibleInputs() {
            return possibleInputs.clone();
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, World level) {
            for (GenericStack option : possibleInputs) {
                if (option.what().equals(input)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }

        @Override
        public boolean isExactDynamicRecipeInput() {
            return possibleInputs.length == 1;
        }

        @Override
        public AEKey getExactDynamicRecipeInputKey() {
            return possibleInputs[0].what();
        }

        @Override
        public long getExactDynamicRecipeInputAmount() {
            return possibleInputs[0].amount();
        }
    }
}
