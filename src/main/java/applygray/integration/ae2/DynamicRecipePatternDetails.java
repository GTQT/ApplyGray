package applygray.integration.ae2;

import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final AEItemKey definition;

    DynamicRecipePatternDetails(String recipeKey, String recipeMapName,
                                List<GenericStack> inputs, List<List<GenericStack>> alternatives,
                                List<GenericStack> outputs, int circuitConfiguration,
                                long rawMaterialCost, int stepCost) {
        if (inputs.isEmpty() || outputs.isEmpty()) {
            throw new IllegalArgumentException("Dynamic RecipeMap pattern requires inputs and outputs");
        }

        this.recipeKey = recipeKey;
        this.recipeMapName = recipeMapName;
        this.inputs = createInputs(inputs, alternatives);
        this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
        this.circuitConfiguration = circuitConfiguration;
        this.rawMaterialCost = rawMaterialCost;
        this.stepCost = stepCost;

        ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(
                primaryInputs(inputs), this.outputs, recipeMapName);
        this.definition = AEItemKey.of(encoded);
        if (this.definition == null) {
            throw new IllegalStateException("Could not create a definition for dynamic RecipeMap pattern " + recipeKey);
        }
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

    public boolean produces(AEKey requested) {
        for (GenericStack output : outputs) {
            if (output.what().equals(requested)) {
                return true;
            }
        }
        return false;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("RecipeKey", recipeKey);
        data.setString("RecipeMap", recipeMapName);
        data.setInteger("Circuit", circuitConfiguration);
        data.setLong("RawCost", rawMaterialCost);
        data.setInteger("StepCost", stepCost);
        data.setTag("Inputs", GenericStack.writeList(primaryInputs()));
        data.setTag("Outputs", GenericStack.writeList(outputs));

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
        if (!data.hasKey("RecipeKey", 8) || !data.hasKey("RecipeMap", 8)) {
            return null;
        }

        List<GenericStack> inputs = nonNull(GenericStack.readList(data.getTagList("Inputs", 10)));
        List<GenericStack> outputs = nonNull(GenericStack.readList(data.getTagList("Outputs", 10)));
        if (inputs.isEmpty() || outputs.isEmpty()) {
            return null;
        }

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

        return new DynamicRecipePatternDetails(data.getString("RecipeKey"), data.getString("RecipeMap"),
                inputs, alternatives, outputs, data.getInteger("Circuit"), data.getLong("RawCost"),
                data.getInteger("StepCost"));
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
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

    private static List<GenericStack> primaryInputs(List<GenericStack> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
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

    private static List<GenericStack> nonNull(List<@Nullable GenericStack> source) {
        List<GenericStack> result = new ArrayList<>(source.size());
        for (GenericStack stack : source) {
            if (stack != null && stack.amount() > 0) {
                result.add(stack);
            }
        }
        return result;
    }

    private static final class Input implements IInput {

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
    }
}
