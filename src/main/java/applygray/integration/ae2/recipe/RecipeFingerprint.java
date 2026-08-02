package applygray.integration.ae2.recipe;

import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.chance.output.impl.ChancedFluidOutput;
import gregtech.api.recipes.chance.output.impl.ChancedItemOutput;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.recipes.properties.RecipeProperty;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Stable, content-based fingerprints for dynamic pattern bindings. */
public final class RecipeFingerprint {

    private RecipeFingerprint() {
    }

    /**
     * A recipe's content identity without its position in a map. This is used to establish a deterministic ordering
     * before an ordinal is added to distinguish otherwise identical recipes.
     */
    public static String contentFingerprint(String recipeMapId, Recipe recipe) {
        return sha256(recipeMapId + '\n' + describeRecipe(recipe));
    }

    public static String fingerprint(String recipeMapId, Recipe recipe, int registrationIndex) {
        return sha256(recipeMapId + '\n' + registrationIndex + '\n' + contentFingerprint(recipeMapId, recipe));
    }

    public static String contentVersion(RecipeMap<?> recipeMap, Collection<Recipe> recipes) {
        StringBuilder content = new StringBuilder(recipeMap.getUnlocalizedName()).append('\n');
        int index = 0;
        for (Recipe recipe : recipes) {
            content.append(fingerprint(recipeMap.getUnlocalizedName(), recipe, index++)).append('\n');
        }
        return sha256(content.toString());
    }

    public static String describeKey(ae2.api.stacks.AEKey key) {
        return key.getType().getId().toString() + ':' + canonicalNbt(key.toTag());
    }

    private static String describeRecipe(Recipe recipe) {
        StringBuilder result = new StringBuilder(512);
        appendInputs(result, "itemInputs", recipe.getInputs());
        appendStacks(result, "itemOutputs", recipe.getOutputs());
        appendChancedItemOutputs(result, recipe.getChancedOutputs().getChancedEntries());
        Object itemChanceLogic = recipe.getChancedOutputs().getChancedOutputLogic();
        result.append("itemChanceLogic=")
                .append(itemChanceLogic == null ? "<null>" : itemChanceLogic.getClass().getName()).append('\n');
        appendInputs(result, "fluidInputs", recipe.getFluidInputs());
        appendFluids(result, "fluidOutputs", recipe.getFluidOutputs());
        appendChancedFluidOutputs(result, recipe.getChancedFluidOutputs().getChancedEntries());
        Object fluidChanceLogic = recipe.getChancedFluidOutputs().getChancedOutputLogic();
        result.append("fluidChanceLogic=")
                .append(fluidChanceLogic == null ? "<null>" : fluidChanceLogic.getClass().getName()).append('\n');
        result.append("duration=").append(recipe.getDuration()).append('\n');
        result.append("eut=").append(recipe.getEUt()).append('\n');
        result.append("hidden=").append(recipe.isHidden()).append('\n');
        result.append("category=").append(recipe.getRecipeCategory()).append('\n');
        result.append("ct=").append(recipe.getIsCTRecipe()).append('\n');
        result.append("groovy=").append(recipe.isGroovyRecipe()).append('\n');
        List<String> properties = new ArrayList<>();
        for (Map.Entry<RecipeProperty<?>, Object> property : recipe.propertyStorage().entrySet()) {
            String value;
            try {
                value = canonicalNbt(property.getKey().serialize(property.getValue()));
            } catch (RuntimeException ignored) {
                Object rawValue = property.getValue();
                value = rawValue == null ? "<null>" : rawValue.getClass().getName() + ':' + rawValue;
            }
            properties.add(property.getKey().getClass().getName() + ':' + property.getKey().getKey() + ':' + value);
        }
        Collections.sort(properties);
        for (String property : properties) {
            result.append("property=").append(property).append('\n');
        }
        return result.toString();
    }

    private static void appendInputs(StringBuilder target, String name, Collection<GTRecipeInput> inputs) {
        target.append(name).append('=');
        for (GTRecipeInput input : inputs) {
            appendLengthDelimited(target, canonicalNbt(GTRecipeInput.writeToNBT(input)));
        }
        target.append('\n');
    }

    private static void appendStacks(StringBuilder target, String name, Collection<ItemStack> stacks) {
        target.append(name).append('=');
        for (ItemStack stack : stacks) {
            appendLengthDelimited(target, stack == null ? "<null>" :
                    canonicalNbt(stack.writeToNBT(new NBTTagCompound())));
        }
        target.append('\n');
    }

    private static void appendFluids(StringBuilder target, String name, Collection<FluidStack> stacks) {
        target.append(name).append('=');
        for (FluidStack stack : stacks) {
            appendLengthDelimited(target, stack == null ? "<null>" :
                    canonicalNbt(stack.writeToNBT(new NBTTagCompound())));
        }
        target.append('\n');
    }

    private static void appendChancedItemOutputs(StringBuilder target, Collection<ChancedItemOutput> outputs) {
        target.append("itemChanced=");
        for (ChancedItemOutput output : outputs) {
            String stack = output.getIngredient() == null ? "<null>" :
                    canonicalNbt(output.getIngredient().writeToNBT(new NBTTagCompound()));
            appendLengthDelimited(target, stack);
            target.append(output.getChance()).append(':').append(output.getChanceBoost()).append(';');
        }
        target.append('\n');
    }

    private static void appendChancedFluidOutputs(StringBuilder target, Collection<ChancedFluidOutput> outputs) {
        target.append("fluidChanced=");
        for (ChancedFluidOutput output : outputs) {
            String stack = output.getIngredient() == null ? "<null>" :
                    canonicalNbt(output.getIngredient().writeToNBT(new NBTTagCompound()));
            appendLengthDelimited(target, stack);
            target.append(output.getChance()).append(':').append(output.getChanceBoost()).append(';');
        }
        target.append('\n');
    }

    /**
     * NBT compounds use a map internally, so their {@code toString()} order is not a stable binding input.
     * Canonicalizing keys here makes fingerprints and NBT-sensitive AE target keys survive equivalent reloads.
     */
    static String canonicalNbt(NBTBase value) {
        if (value == null) return "<null>";
        StringBuilder result = new StringBuilder();
        appendCanonicalNbt(result, value);
        return result.toString();
    }

    private static void appendCanonicalNbt(StringBuilder target, NBTBase value) {
        if (value instanceof NBTTagCompound compound) {
            target.append('{');
            List<String> keys = new ArrayList<>(compound.getKeySet());
            Collections.sort(keys);
            for (String key : keys) {
                appendLengthDelimited(target, key);
                appendCanonicalNbt(target, compound.getTag(key));
            }
            target.append('}');
            return;
        }
        if (value instanceof NBTTagList list) {
            target.append('[').append(list.tagCount()).append(':');
            for (int index = 0; index < list.tagCount(); index++) {
                appendCanonicalNbt(target, list.get(index));
            }
            target.append(']');
            return;
        }
        appendLengthDelimited(target, value.getId() + ":" + value);
    }

    private static void appendLengthDelimited(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hexadecimal.append(Character.forDigit((valueByte >>> 4) & 0xF, 16));
                hexadecimal.append(Character.forDigit(valueByte & 0xF, 16));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
