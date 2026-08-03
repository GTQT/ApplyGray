package applygray.mixins.gregtech;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.IRecipeBoundInput;
import applygray.integration.ae2.recipe.RecipeBinding;
import applygray.integration.ae2.recipe.RecipeBindingResolver;

import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.IPatternBufferIsolatedHandler;
import gregtech.api.capability.IRecipeMapBoundInput;
import gregtech.api.capability.impl.MultiblockRecipeLogic;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.util.GTUtility;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.items.IItemHandlerModifiable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents a bound dynamic buffer from falling back to an arbitrary same-map matching recipe. */
@Mixin(value = MultiblockRecipeLogic.class, remap = false)
public abstract class MixinMultiblockRecipeLogicRecipeBinding {

    private static final long WARN_INTERVAL_NANOS = 5_000_000_000L;
    private static final Map<String, Long> LAST_WARN_NANOS = new ConcurrentHashMap<>();

    @Shadow
    public abstract boolean checkRecipe(Recipe recipe);

    @Inject(method = "findRecipe", at = @At("HEAD"), cancellable = true)
    private void applygray$findExactBoundRecipe(RecipeMap<?> recipeMap, long maxVoltage,
                                                IItemHandlerModifiable itemInputs,
                                                IMultipleTankHandler fluidInputs,
                                                CallbackInfoReturnable<Recipe> callback) {
        if (!(itemInputs instanceof IRecipeBoundInput boundInput)) return;
        RecipeBinding binding = boundInput.getRecipeBinding();
        if (binding == null) return;

        if (!boundInput.isRecipeBindingCurrent()) {
            warn(binding, "BINDING_UNAVAILABLE");
            callback.setReturnValue(null);
            return;
        }

        RecipeBindingResolver.Resolution resolution = RecipeBindingResolver.resolve(binding, recipeMap);
        if (!resolution.isResolved()) {
            warn(binding, resolution.getReasonCode());
            callback.setReturnValue(null);
            return;
        }

        Recipe recipe = resolution.getRecipe();
        if (recipe == null || !recipe.matches(false, itemInputs, fluidInputs)) {
            warnInputMismatch(binding, recipe, itemInputs, fluidInputs);
            callback.setReturnValue(null);
            return;
        }
        if (!checkRecipe(recipe)) {
            warn(binding, "CONTROLLER_CAPABILITY_REJECTED");
            callback.setReturnValue(null);
            return;
        }
        callback.setReturnValue(recipe);
    }

    private void warn(RecipeBinding binding, String reasonCode) {
        if (!shouldWarn(binding, reasonCode)) return;
        ApplyGrayMod.LOGGER.warn("Rejected exact RecipeMap execution controller={} recipeMapId={} " +
                        "recipeFingerprint={} target={} ruleSetVersion={} decision=rejected reasonCode={}",
                describeController(), binding.getRecipeMapId(), binding.getRecipeFingerprint(), binding.getTargetKey(),
                binding.getRuleSetVersion(), reasonCode);
    }

    /**
     * A failed exact binding is unusual and otherwise difficult to reproduce from a player report. Keep this
     * diagnostic rate-limited, but retain the exact projection that GT's matcher received on the failed attempt.
     */
    private void warnInputMismatch(RecipeBinding binding, Recipe recipe, IItemHandlerModifiable itemInputs,
                                   IMultipleTankHandler fluidInputs) {
        if (!shouldWarn(binding, "BOUND_INPUT_MISMATCH")) return;

        List<ItemStack> actualItems = itemInputs == null ? new ArrayList<>() : GTUtility.itemHandlerToList(itemInputs);
        List<FluidStack> actualFluids = fluidInputs == null ? new ArrayList<>() :
                GTUtility.fluidHandlerToList(fluidInputs);
        ApplyGrayMod.LOGGER.warn("Rejected exact RecipeMap execution controller={} recipeMapId={} " +
                        "recipeFingerprint={} target={} ruleSetVersion={} decision=rejected reasonCode={} " +
                        "expectedItems={} expectedFluids={} actualItems={} actualFluids={} " +
                        "missingItems={} missingFluids={} itemHandlerState={} fluidHandlerState={}",
                describeController(), binding.getRecipeMapId(), binding.getRecipeFingerprint(), binding.getTargetKey(),
                binding.getRuleSetVersion(), "BOUND_INPUT_MISMATCH", describeInputs(recipe == null ? null : recipe.getInputs()),
                describeInputs(recipe == null ? null : recipe.getFluidInputs()), describeItemStacks(actualItems),
                describeFluidStacks(actualFluids), findMissingItems(recipe, actualItems), findMissingFluids(recipe, actualFluids),
                describeItemHandlerState(itemInputs), describeFluidHandlerState(itemInputs, fluidInputs));
    }

    private boolean shouldWarn(RecipeBinding binding, String reasonCode) {
        String key = describeController() + ':' + binding.describe() + ':' + reasonCode;
        long now = System.nanoTime();
        Long previous = LAST_WARN_NANOS.put(key, now);
        return previous == null || now - previous >= WARN_INTERVAL_NANOS;
    }

    private String describeController() {
        // MTETrait declares this public method above MultiblockRecipeLogic, so it cannot be shadowed on the
        // concrete target class. The transformed mixin instance is the target at runtime.
        MetaTileEntity controller = ((MultiblockRecipeLogic) (Object) this).getMetaTileEntity();
        if (controller == null) return "<unavailable>";

        BlockPos pos = controller.getPos();
        World world = controller.getWorld();
        String dimension = world == null || world.provider == null ? "?" : String.valueOf(world.provider.getDimension());
        return controller.getClass().getSimpleName() + '@' + (pos == null ? "?" : pos.toString()) + "/dim=" + dimension;
    }

    private static String findMissingItems(Recipe recipe, List<ItemStack> actualItems) {
        if (recipe == null) return "<recipe missing>";
        int[] amounts = new int[actualItems.size()];
        for (int index = 0; index < actualItems.size(); index++) {
            ItemStack stack = actualItems.get(index);
            amounts[index] = stack == null || stack.isEmpty() ? 0 : stack.getCount();
        }

        List<String> missing = new ArrayList<>();
        for (GTRecipeInput input : recipe.getInputs()) {
            int remaining = input.getAmount();
            for (int index = 0; index < actualItems.size() && remaining > 0; index++) {
                ItemStack stack = actualItems.get(index);
                if (stack == null || stack.isEmpty() || !input.acceptsStack(stack)) continue;
                int matched = Math.min(amounts[index], remaining);
                remaining -= matched;
                if (!input.isNonConsumable()) amounts[index] -= matched;
            }
            if (remaining > 0) missing.add(describeInput(input) + " missing=" + remaining);
        }
        return missing.isEmpty() ? "<none>" : String.join("; ", missing);
    }

    private static String findMissingFluids(Recipe recipe, List<FluidStack> actualFluids) {
        if (recipe == null) return "<recipe missing>";
        int[] amounts = new int[actualFluids.size()];
        for (int index = 0; index < actualFluids.size(); index++) {
            FluidStack stack = actualFluids.get(index);
            amounts[index] = stack == null ? 0 : stack.amount;
        }

        List<String> missing = new ArrayList<>();
        for (GTRecipeInput input : recipe.getFluidInputs()) {
            int remaining = input.getAmount();
            for (int index = 0; index < actualFluids.size() && remaining > 0; index++) {
                FluidStack stack = actualFluids.get(index);
                if (stack == null || !input.acceptsFluid(stack)) continue;
                int matched = Math.min(amounts[index], remaining);
                remaining -= matched;
                if (!input.isNonConsumable()) amounts[index] -= matched;
            }
            if (remaining > 0) missing.add(describeInput(input) + " missing=" + remaining);
        }
        return missing.isEmpty() ? "<none>" : String.join("; ", missing);
    }

    private static String describeInputs(List<GTRecipeInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return "[]";
        List<String> described = new ArrayList<>(inputs.size());
        for (GTRecipeInput input : inputs) described.add(describeInput(input));
        return described.toString();
    }

    private static String describeInput(GTRecipeInput input) {
        if (input == null) return "<null>";
        FluidStack fluid = input.getInputFluidStack();
        String name;
        if (fluid != null && fluid.getFluid() != null) {
            name = "fluid:" + fluid.getFluid().getName();
        } else if (input.isOreDict()) {
            name = "oredict:" + input.getOreDict();
        } else {
            ItemStack[] stacks = input.getInputStacks();
            name = stacks == null || stacks.length == 0 ? input.getClass().getSimpleName() :
                    describeItemStack(stacks[0]);
        }
        return name + 'x' + input.getAmount() + (input.isNonConsumable() ? "(NC)" : "");
    }

    private static String describeItemStacks(List<ItemStack> stacks) {
        List<String> described = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) described.add(describeItemStack(stack));
        }
        return described.isEmpty() ? "[]" : described.toString();
    }

    private static String describeFluidStacks(List<FluidStack> stacks) {
        List<String> described = new ArrayList<>();
        for (FluidStack stack : stacks) {
            if (stack != null && stack.amount > 0 && stack.getFluid() != null) {
                described.add(stack.getFluid().getName() + 'x' + stack.amount);
            }
        }
        return described.isEmpty() ? "[]" : described.toString();
    }

    private static String describeItemHandlerState(IItemHandlerModifiable handler) {
        if (handler == null) return "<null>";
        return handler.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(handler)) +
                " slots=" + handler.getSlots() +
                " bound=" + (handler instanceof IRecipeMapBoundInput) +
                " isolated=" + (handler instanceof IPatternBufferIsolatedHandler);
    }

    /**
     * Keep the raw tank projection in the existing rate-limited mismatch log. This distinguishes a buffer that
     * failed to restore its contents from recipe logic being handed an unrelated or empty fluid handler.
     */
    private static String describeFluidHandlerState(IItemHandlerModifiable itemHandler, IMultipleTankHandler handler) {
        if (handler == null) return "<null>";

        List<String> tanks = new ArrayList<>();
        try {
            for (int index = 0; index < handler.getTanks(); index++) {
                IFluidTank tank = handler.getTankAt(index);
                FluidStack stack = tank == null ? null : tank.getFluid();
                tanks.add(index + ":" + (tank == null ? "<null>" : tank.getClass().getName()) + "=" +
                        (stack == null || stack.getFluid() == null ? "<empty>" :
                                stack.getFluid().getName() + 'x' + stack.amount));
            }
            return handler.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(handler)) +
                    " sameAsItemHandler=" + (handler == itemHandler) +
                    " tanks=" + handler.getTanks() + " entries=" + handler.getFluidTanks().size() +
                    " values=" + tanks;
        } catch (RuntimeException exception) {
            return handler.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(handler)) +
                    " inspectionFailed=" + exception.getClass().getSimpleName();
        }
    }

    private static String describeItemStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem().getRegistryName() == null) return "<empty>";
        return stack.getItem().getRegistryName() + "@" + stack.getMetadata() + 'x' + stack.getCount();
    }
}
