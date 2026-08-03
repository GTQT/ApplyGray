package applygray.integration.ae2.recipe;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtech.api.recipes.properties.RecipeProperty;
import gregtech.api.recipes.properties.impl.CleanroomProperty;
import gregtech.api.recipes.properties.impl.DimensionProperty;
import gregtech.api.metatileentity.multiblock.CleanroomType;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable recipe facts used by asynchronous planning. This class deliberately contains no world or tile references.
 */
public final class NormalizedRecipe {

    private static final Set<String> BUILTIN_PROPERTY_TYPES = Set.of(
            "gregtech.api.recipes.properties.impl.CleanroomProperty",
            "gregtech.api.recipes.properties.impl.ComputationProperty",
            "gregtech.api.recipes.properties.impl.DimensionProperty",
            "gregtech.api.recipes.properties.impl.FogMultiStepProperty",
            "gregtech.api.recipes.properties.impl.FogPlasmaTierProperty",
            "gregtech.api.recipes.properties.impl.FogUpgradeNameProperty",
            "gregtech.api.recipes.properties.impl.FusionEUToStartProperty",
            "gregtech.api.recipes.properties.impl.HeatProperty",
            "gregtech.api.recipes.properties.impl.ImplosionExplosiveProperty",
            "gregtech.api.recipes.properties.impl.PrimitiveProperty",
            "gregtech.api.recipes.properties.impl.ResearchProperty",
            "gregtech.api.recipes.properties.impl.ScanProperty",
            "gregtech.api.recipes.properties.impl.TemperatureProperty",
            "gregtech.api.recipes.properties.impl.TotalComputationProperty");

    private final String recipeMapId;
    private final String recipeFingerprint;
    private final String recipeMapContentVersion;
    private final int registrationIndex;
    private final List<NormalizedInput> itemInputs;
    private final List<NormalizedInput> fluidInputs;
    private final List<GenericStack> deterministicOutputs;
    private final List<GenericStack> chancedOutputStacks;
    private final List<String> chancedOutputs;
    private final List<NormalizedInput> nonConsumableRequirements;
    private final Map<String, String> properties;
    private final Map<String, Long> numericProperties;
    private final Set<String> unknownPropertyKeys;
    private final Set<Integer> whiteListDimensions;
    private final Set<Integer> blackListDimensions;
    private final String cleanroomRequirement;
    private final String category;
    private final long eut;
    private final int duration;

    private NormalizedRecipe(String recipeMapId, String recipeFingerprint, String recipeMapContentVersion,
                             int registrationIndex, List<NormalizedInput> itemInputs,
                             List<NormalizedInput> fluidInputs, List<GenericStack> deterministicOutputs,
                             List<GenericStack> chancedOutputStacks, List<String> chancedOutputs,
                             List<NormalizedInput> nonConsumableRequirements,
                             Map<String, String> properties, Map<String, Long> numericProperties,
                             Set<String> unknownPropertyKeys, Set<Integer> whiteListDimensions,
                             Set<Integer> blackListDimensions, String cleanroomRequirement,
                             String category, long eut, int duration) {
        this.recipeMapId = recipeMapId;
        this.recipeFingerprint = recipeFingerprint;
        this.recipeMapContentVersion = recipeMapContentVersion;
        this.registrationIndex = registrationIndex;
        this.itemInputs = immutableCopy(itemInputs);
        this.fluidInputs = immutableCopy(fluidInputs);
        this.deterministicOutputs = Collections.unmodifiableList(new ArrayList<>(deterministicOutputs));
        this.chancedOutputStacks = Collections.unmodifiableList(new ArrayList<>(chancedOutputStacks));
        this.chancedOutputs = Collections.unmodifiableList(new ArrayList<>(chancedOutputs));
        this.nonConsumableRequirements = immutableCopy(nonConsumableRequirements);
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.numericProperties = Collections.unmodifiableMap(new LinkedHashMap<>(numericProperties));
        this.unknownPropertyKeys = Collections.unmodifiableSet(new LinkedHashSet<>(unknownPropertyKeys));
        this.whiteListDimensions = Collections.unmodifiableSet(new LinkedHashSet<>(whiteListDimensions));
        this.blackListDimensions = Collections.unmodifiableSet(new LinkedHashSet<>(blackListDimensions));
        this.cleanroomRequirement = cleanroomRequirement;
        this.category = category;
        this.eut = eut;
        this.duration = duration;
    }

    public static NormalizedRecipe from(RecipeMap<?> recipeMap, Recipe recipe, int registrationIndex,
                                        String recipeMapContentVersion) {
        String recipeMapId = recipeMap.getUnlocalizedName();
        List<NormalizedInput> itemInputs = normalizeInputs(recipe.getInputs());
        List<NormalizedInput> fluidInputs = normalizeInputs(recipe.getFluidInputs());
        List<NormalizedInput> nonConsumable = new ArrayList<>();
        for (NormalizedInput input : itemInputs) {
            if (input.isNonConsumable()) nonConsumable.add(input);
        }
        for (NormalizedInput input : fluidInputs) {
            if (input.isNonConsumable()) nonConsumable.add(input);
        }

        List<GenericStack> outputs = new ArrayList<>();
        for (ItemStack output : recipe.getOutputs()) {
            GenericStack generic = GenericStack.fromItemStack(output);
            if (generic != null && generic.amount() > 0) outputs.add(generic);
        }
        for (FluidStack output : recipe.getFluidOutputs()) {
            GenericStack generic = GenericStack.fromFluidStack(output);
            if (generic != null && generic.amount() > 0) outputs.add(generic);
        }

        List<GenericStack> chancedStacks = new ArrayList<>();
        List<String> chanced = new ArrayList<>();
        for (ChancedItemOutput entry : recipe.getChancedOutputs().getChancedEntries()) {
            chanced.add(String.valueOf(entry));
            GenericStack generic = GenericStack.fromItemStack(entry.getIngredient());
            if (generic != null && generic.amount() > 0) chancedStacks.add(generic);
        }
        for (ChancedFluidOutput entry : recipe.getChancedFluidOutputs().getChancedEntries()) {
            chanced.add(String.valueOf(entry));
            GenericStack generic = GenericStack.fromFluidStack(entry.getIngredient());
            if (generic != null && generic.amount() > 0) chancedStacks.add(generic);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, Long> numericProperties = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        Set<Integer> whiteListDimensions = new LinkedHashSet<>();
        Set<Integer> blackListDimensions = new LinkedHashSet<>();
        String cleanroomRequirement = null;
        for (Map.Entry<RecipeProperty<?>, Object> entry : recipe.propertyStorage().entrySet()) {
            RecipeProperty<?> property = entry.getKey();
            String key = property.getKey();
            String value;
            NBTBase serialized = null;
            try {
                serialized = property.serialize(entry.getValue());
                value = String.valueOf(serialized);
            } catch (RuntimeException ignored) {
                value = String.valueOf(entry.getValue());
            }
            properties.put(key, value);
            if (entry.getValue() instanceof Number number) {
                numericProperties.put(key, number.longValue());
            }
            if (property instanceof CleanroomProperty && entry.getValue() instanceof CleanroomType type) {
                cleanroomRequirement = type.getName();
            }
            if (property instanceof DimensionProperty && serialized instanceof NBTTagCompound dimensions) {
                addDimensions(whiteListDimensions, dimensions.getIntArray("whiteListDimensions"));
                addDimensions(blackListDimensions, dimensions.getIntArray("blackListDimensions"));
            }
            if (!BUILTIN_PROPERTY_TYPES.contains(property.getClass().getName())) {
                unknown.add(key);
            }
        }

        return new NormalizedRecipe(recipeMapId,
                RecipeFingerprint.fingerprint(recipeMapId, recipe), recipeMapContentVersion,
                registrationIndex, itemInputs, fluidInputs, outputs, chancedStacks, chanced, nonConsumable,
                properties, numericProperties, unknown, whiteListDimensions, blackListDimensions, cleanroomRequirement,
                recipe.getRecipeCategory() == null ? "" : recipe.getRecipeCategory().getName(), recipe.getEUt(),
                recipe.getDuration());
    }

    public RecipeBinding createBinding(AEKey target, String ruleSetVersion, String machineProfileVersion) {
        return new RecipeBinding(recipeMapId, RecipeBinding.FINGERPRINT_VERSION, recipeFingerprint,
                recipeMapContentVersion, RecipeFingerprint.describeKey(target),
                RecipeBinding.NORMALIZATION_VERSION, ruleSetVersion, machineProfileVersion);
    }

    public String getRecipeMapId() {
        return recipeMapId;
    }

    public String getRecipeFingerprint() {
        return recipeFingerprint;
    }

    public String getRecipeMapContentVersion() {
        return recipeMapContentVersion;
    }

    public int getRegistrationIndex() {
        return registrationIndex;
    }

    public List<NormalizedInput> getItemInputs() {
        return itemInputs;
    }

    public List<NormalizedInput> getFluidInputs() {
        return fluidInputs;
    }

    public List<GenericStack> getDeterministicOutputs() {
        return deterministicOutputs;
    }

    public List<String> getChancedOutputs() {
        return chancedOutputs;
    }

    public List<GenericStack> getChancedOutputStacks() {
        return chancedOutputStacks;
    }

    public boolean hasChancedOutputs() {
        return !chancedOutputs.isEmpty();
    }

    public List<NormalizedInput> getNonConsumableRequirements() {
        return nonConsumableRequirements;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public boolean hasProperty(String key) {
        return properties.containsKey(key);
    }

    public Long getNumericProperty(String key) {
        return numericProperties.get(key);
    }

    public Set<String> getUnknownPropertyKeys() {
        return unknownPropertyKeys;
    }

    public boolean hasDimensionRequirement() {
        return !whiteListDimensions.isEmpty() || !blackListDimensions.isEmpty();
    }

    public boolean isDimensionAllowed(int dimension) {
        return !blackListDimensions.contains(dimension) &&
                (whiteListDimensions.isEmpty() || whiteListDimensions.contains(dimension));
    }

    public String getCleanroomRequirement() {
        return cleanroomRequirement;
    }

    public String getCategory() {
        return category;
    }

    public long getEUt() {
        return eut;
    }

    public int getDuration() {
        return duration;
    }

    public int getDistinctItemOutputTypes() {
        Set<AEKey> types = new LinkedHashSet<>();
        for (GenericStack output : deterministicOutputs) {
            if (output.what() instanceof AEItemKey) types.add(output.what());
        }
        for (GenericStack output : chancedOutputStacks) {
            if (output.what() instanceof AEItemKey) types.add(output.what());
        }
        return types.size();
    }

    public int getDistinctFluidOutputTypes() {
        Set<AEKey> types = new LinkedHashSet<>();
        for (GenericStack output : deterministicOutputs) {
            if (output.what() instanceof AEFluidKey) types.add(output.what());
        }
        for (GenericStack output : chancedOutputStacks) {
            if (output.what() instanceof AEFluidKey) types.add(output.what());
        }
        return types.size();
    }

    private static void addDimensions(Set<Integer> target, int[] dimensions) {
        if (dimensions == null) return;
        for (int dimension : dimensions) target.add(dimension);
    }

    private static List<NormalizedInput> normalizeInputs(List<GTRecipeInput> inputs) {
        List<NormalizedInput> normalized = new ArrayList<>(inputs.size());
        for (GTRecipeInput input : inputs) {
            List<ItemStack> itemChoices = new ArrayList<>();
            ItemStack[] stacks = input.getInputStacks();
            if (stacks != null) {
                for (ItemStack stack : stacks) {
                    if (stack == null || stack.isEmpty()) continue;
                    ItemStack copy = stack.copy();
                    copy.setCount(1);
                    itemChoices.add(copy);
                }
            }
            FluidStack fluid = input.getInputFluidStack();
            normalized.add(new NormalizedInput(itemChoices, fluid, input.getAmount(), input.isNonConsumable(),
                    input.hasNBTMatchingCondition(), input.isOreDict()));
        }
        return normalized;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class NormalizedInput {

        private final List<ItemStack> itemChoices;
        private final FluidStack fluid;
        private final int amount;
        private final boolean nonConsumable;
        private final boolean nbtSensitive;
        private final boolean oreDict;

        private NormalizedInput(List<ItemStack> itemChoices, FluidStack fluid, int amount, boolean nonConsumable,
                                boolean nbtSensitive, boolean oreDict) {
            List<ItemStack> copiedChoices = new ArrayList<>(itemChoices.size());
            for (ItemStack itemChoice : itemChoices) copiedChoices.add(itemChoice.copy());
            this.itemChoices = Collections.unmodifiableList(copiedChoices);
            this.fluid = fluid == null ? null : fluid.copy();
            this.amount = amount;
            this.nonConsumable = nonConsumable;
            this.nbtSensitive = nbtSensitive;
            this.oreDict = oreDict;
        }

        public List<ItemStack> getItemChoices() {
            List<ItemStack> copy = new ArrayList<>(itemChoices.size());
            for (ItemStack itemChoice : itemChoices) copy.add(itemChoice.copy());
            return Collections.unmodifiableList(copy);
        }

        public FluidStack getFluid() {
            return fluid == null ? null : fluid.copy();
        }

        public int getAmount() {
            return amount;
        }

        public boolean isNonConsumable() {
            return nonConsumable;
        }

        public boolean isNbtSensitive() {
            return nbtSensitive;
        }

        public boolean isOreDict() {
            return oreDict;
        }
    }
}
