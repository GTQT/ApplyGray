package applygray.integration.ae2;

import gregtech.api.util.AE2PatternCompat;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.util.item.AEItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe virtual processing pattern used by the lazy RecipeMap lookup.
 *
 * <p>It deliberately does not depend on an encoded-pattern inventory slot. AE2's
 * planning thread can therefore inspect it without reading a world or tile entity.</p>
 */
public final class DynamicRecipePatternDetails implements ICraftingPatternDetails {

    private final String recipeKey;
    private final String recipeMapName;
    private final ItemStack[] inputStacks;
    private final ItemStack[] outputStacks;
    private final ItemStack[][] alternatives;
    private final IAEItemStack[] inputs;
    private final IAEItemStack[] outputs;
    private final IAEItemStack[] condensedInputs;
    private final IAEItemStack[] condensedOutputs;
    private final int circuitConfiguration;
    private final long rawMaterialCost;
    private final int stepCost;
    private final ItemStack patternStack;
    private volatile int priority;

    DynamicRecipePatternDetails(String recipeKey, String recipeMapName,
                                List<ItemStack> inputs, List<List<ItemStack>> alternatives,
                                List<ItemStack> outputs, int circuitConfiguration,
                                long rawMaterialCost, int stepCost) {
        this.recipeKey = recipeKey;
        this.recipeMapName = recipeMapName;
        this.inputStacks = copyStacks(inputs);
        this.outputStacks = copyStacks(outputs);
        this.alternatives = copyAlternatives(alternatives, this.inputStacks);
        this.inputs = toAeStacks(this.inputStacks);
        this.outputs = toAeStacks(this.outputStacks);
        this.condensedInputs = condense(this.inputs);
        this.condensedOutputs = condense(this.outputs);
        this.circuitConfiguration = circuitConfiguration;
        this.rawMaterialCost = rawMaterialCost;
        this.stepCost = stepCost;
        this.priority = toPriority(rawMaterialCost, stepCost);
        this.patternStack = createDisplayPattern(this.inputStacks, this.outputStacks);
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

    public boolean produces(ItemStack requested) {
        for (ItemStack output : outputStacks) {
            if (ItemStack.areItemsEqual(output, requested) && ItemStack.areItemStackTagsEqual(output, requested)) {
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
        data.setTag("Inputs", writeStacks(inputStacks));
        data.setTag("Outputs", writeStacks(outputStacks));
        NBTTagList alternativeList = new NBTTagList();
        for (ItemStack[] options : alternatives) {
            NBTTagCompound optionData = new NBTTagCompound();
            optionData.setTag("Options", writeStacks(options));
            alternativeList.appendTag(optionData);
        }
        data.setTag("Alternatives", alternativeList);
        return data;
    }

    public static DynamicRecipePatternDetails readFromNBT(NBTTagCompound data) {
        List<ItemStack> inputs = readStacks(data.getTagList("Inputs", 10));
        List<ItemStack> outputs = readStacks(data.getTagList("Outputs", 10));
        if (inputs.isEmpty() || outputs.isEmpty() || !data.hasKey("RecipeKey") || !data.hasKey("RecipeMap")) {
            return null;
        }
        List<List<ItemStack>> alternatives = new ArrayList<>();
        NBTTagList alternativeList = data.getTagList("Alternatives", 10);
        for (int i = 0; i < inputs.size(); i++) {
            if (i < alternativeList.tagCount()) {
                alternatives.add(readStacks(alternativeList.getCompoundTagAt(i).getTagList("Options", 10)));
            } else {
                alternatives.add(java.util.Collections.singletonList(inputs.get(i).copy()));
            }
        }
        return new DynamicRecipePatternDetails(data.getString("RecipeKey"), data.getString("RecipeMap"),
                inputs, alternatives, outputs,
                data.getInteger("Circuit"), data.getLong("RawCost"), data.getInteger("StepCost"));
    }

    private static NBTTagList writeStacks(ItemStack[] stacks) {
        NBTTagList result = new NBTTagList();
        for (ItemStack stack : stacks) result.appendTag(stack.writeToNBT(new NBTTagCompound()));
        return result;
    }

    private static List<ItemStack> readStacks(NBTTagList data) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < data.tagCount(); i++) {
            ItemStack stack = new ItemStack(data.getCompoundTagAt(i));
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    @Override
    public ItemStack getPattern() {
        return patternStack.copy();
    }

    @Override
    public boolean isValidItemForSlot(int slot, ItemStack stack, World world) {
        if (slot < 0 || slot >= alternatives.length || stack == null || stack.isEmpty()) return false;
        for (ItemStack candidate : alternatives[slot]) {
            if (ItemStack.areItemsEqual(candidate, stack) && ItemStack.areItemStackTagsEqual(candidate, stack)) {
                return stack.getCount() >= candidate.getCount();
            }
        }
        return false;
    }

    @Override
    public boolean isCraftable() {
        return false;
    }

    @Override
    public IAEItemStack[] getInputs() {
        return copyAeStacks(inputs);
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return copyAeStacks(condensedInputs);
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return copyAeStacks(condensedOutputs);
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return copyAeStacks(outputs);
    }

    @Override
    public boolean canSubstitute() {
        for (ItemStack[] candidates : alternatives) {
            if (candidates.length > 1) return true;
        }
        return false;
    }

    @Override
    public List<IAEItemStack> getSubstituteInputs(int slot) {
        List<IAEItemStack> result = new ArrayList<>();
        if (slot < 0 || slot >= alternatives.length) return result;
        for (ItemStack candidate : alternatives[slot]) {
            IAEItemStack aeStack = AEItemStack.fromItemStack(candidate);
            if (aeStack != null) result.add(aeStack);
        }
        return result;
    }

    @Override
    public ItemStack getOutput(net.minecraft.inventory.InventoryCrafting inventoryCrafting, World world) {
        return outputStacks.length == 0 ? ItemStack.EMPTY : outputStacks[0].copy();
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
    }

    private static int toPriority(long rawMaterialCost, int stepCost) {
        long penalty = Math.min(Integer.MAX_VALUE, rawMaterialCost * 1_000L + Math.max(0, stepCost));
        return (int) -penalty;
    }

    private static ItemStack createDisplayPattern(ItemStack[] inputs, ItemStack[] outputs) {
        ItemStack[] patternInputs = new ItemStack[9];
        ItemStack[] patternOutputs = new ItemStack[3];
        for (int i = 0; i < patternInputs.length; i++) {
            patternInputs[i] = i < inputs.length ? inputs[i].copy() : ItemStack.EMPTY;
        }
        for (int i = 0; i < patternOutputs.length; i++) {
            patternOutputs[i] = i < outputs.length ? outputs[i].copy() : ItemStack.EMPTY;
        }
        ItemStack pattern = AE2PatternCompat.createProcessingPattern(patternInputs, patternOutputs, true,
                AE2PatternCompat.containsFluid(patternInputs) || AE2PatternCompat.containsFluid(patternOutputs));
        return pattern.isEmpty() ? ItemStack.EMPTY : pattern;
    }

    private static ItemStack[] copyStacks(List<ItemStack> stacks) {
        ItemStack[] result = new ItemStack[stacks.size()];
        for (int i = 0; i < stacks.size(); i++) result[i] = stacks.get(i).copy();
        return result;
    }

    private static ItemStack[][] copyAlternatives(List<List<ItemStack>> source, ItemStack[] fallbacks) {
        ItemStack[][] result = new ItemStack[fallbacks.length][];
        for (int i = 0; i < fallbacks.length; i++) {
            List<ItemStack> options = i < source.size() ? source.get(i) : null;
            if (options == null || options.isEmpty()) {
                result[i] = new ItemStack[]{fallbacks[i].copy()};
                continue;
            }
            result[i] = copyStacks(options);
        }
        return result;
    }

    private static IAEItemStack[] toAeStacks(ItemStack[] stacks) {
        List<IAEItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            IAEItemStack aeStack = AEItemStack.fromItemStack(stack);
            if (aeStack != null) result.add(aeStack);
        }
        return result.toArray(new IAEItemStack[0]);
    }

    private static IAEItemStack[] condense(IAEItemStack[] stacks) {
        List<IAEItemStack> result = new ArrayList<>();
        for (IAEItemStack stack : stacks) {
            IAEItemStack found = null;
            for (IAEItemStack existing : result) {
                if (existing.isSameType(stack)) {
                    found = existing;
                    break;
                }
            }
            if (found == null) result.add(stack.copy());
            else found.setStackSize(found.getStackSize() + stack.getStackSize());
        }
        return result.toArray(new IAEItemStack[0]);
    }

    private static IAEItemStack[] copyAeStacks(IAEItemStack[] source) {
        IAEItemStack[] result = new IAEItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i].copy();
        return result;
    }
}
