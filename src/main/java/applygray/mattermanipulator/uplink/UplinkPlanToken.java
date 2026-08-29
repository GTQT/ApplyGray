package applygray.mattermanipulator.uplink;

import java.util.ArrayList;
import java.util.List;

import applygray.common.items.ApplyGrayMetaItems;
import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.FluidRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the order tokens and synthetic processing patterns that represent one Quantum Uplink material plan.
 *
 * <p>A plan is expressed to AE2 as a pattern whose inputs are the planned materials and whose only output is a
 * uniquely named order token. Advertising that pattern makes the plan visible, searchable and manually craftable from
 * any ME terminal; requesting the token is what gathers the materials.</p>
 */
final class UplinkPlanToken {

    /** Mirrors {@code AEProcessingPattern.MAX_INPUT_SLOTS}; wider plans are split across several patterns. */
    static final int MAX_PATTERN_INPUTS = 9 * 9;

    private static final String PLAN_NAME_PREFIX = "物质操纵者计划";
    private static final String ORDER_NAME_KEY = "order_name";
    private static final String DISCRIMINATOR_KEY = "UplinkPlan";
    private static final String DISPLAY_KEY = "display";
    private static final String DISPLAY_NAME_KEY = "Name";

    private UplinkPlanToken() {}

    /**
     * Flattens requirements into pattern-sized input chunks, preserving the planner's ordering so the pattern reads
     * the same way the material list does.
     */
    static List<List<GenericStack>> split(ResourceRequirements requirements) {
        List<GenericStack> inputs = new ArrayList<>();
        for (ResourceRequirement requirement : requirements.entries()) {
            GenericStack input = toGenericStack(requirement);
            if (input != null) inputs.add(input);
        }
        for (FluidRequirement requirement : requirements.fluidEntries()) {
            inputs.add(toGenericStack(requirement));
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("The plan has no material requirements AE2 can express");
        }

        List<List<GenericStack>> chunks = new ArrayList<>();
        for (int start = 0; start < inputs.size(); start += MAX_PATTERN_INPUTS) {
            chunks.add(List.copyOf(inputs.subList(start, Math.min(start + MAX_PATTERN_INPUTS, inputs.size()))));
        }
        return chunks;
    }

    /**
     * Creates the order token that identifies one pattern of a plan. The discriminator keeps otherwise identical
     * plans distinct, because AE2 matches patterns by their output key.
     */
    static ItemStack createToken(String planName, long discriminator, int part, int partCount) {
        ItemStack token = ApplyGrayMetaItems.ORDER.getStackForm();
        String label = planLabel(planName, discriminator, part, partCount);

        NBTTagCompound data = token.hasTagCompound() ? token.getTagCompound() : new NBTTagCompound();
        data.setString(ORDER_NAME_KEY, label);
        data.setLong(DISCRIMINATOR_KEY, discriminator);
        if (partCount > 1) data.setInteger(DISCRIMINATOR_KEY + "Part", part);
        NBTTagCompound display = data.getCompoundTag(DISPLAY_KEY);
        display.setString(DISPLAY_NAME_KEY, label);
        data.setTag(DISPLAY_KEY, display);
        token.setTagCompound(data);
        return token;
    }

    /** Encodes the processing pattern that converts the given materials into the given order token. */
    static ItemStack encodePattern(List<GenericStack> inputs, ItemStack token) {
        GenericStack output = GenericStack.fromItemStack(token);
        if (output == null) throw new IllegalArgumentException("The plan token cannot be expressed as an AE key");
        return PatternDetailsHelper.encodeProcessingPattern(inputs, List.of(output));
    }

    private static String planLabel(String planName, long discriminator, int part, int partCount) {
        StringBuilder label = new StringBuilder(PLAN_NAME_PREFIX);
        if (planName != null && !planName.isEmpty()) label.append(" - ").append(planName);
        label.append(" #").append(discriminator);
        if (partCount > 1) label.append(" (").append(part).append('/').append(partCount).append(')');
        return label.toString();
    }

    @Nullable
    private static GenericStack toGenericStack(ResourceRequirement requirement) {
        BlockSpec specification = requirement.specification();
        if (specification.isAir()) return null;

        GenericStack template;
        if (specification.isFluid()) {
            FluidStack fluid = specification.fluidStack();
            fluid.amount = 1;
            template = GenericStack.fromFluidStack(fluid);
        } else {
            template = GenericStack.fromItemStack(specification.toStack());
        }
        if (template == null) {
            throw new IllegalArgumentException("Requirement " + specification + " cannot be expressed as an AE key");
        }
        return new GenericStack(template.what(), boundedAmount(requirement.amount()));
    }

    private static GenericStack toGenericStack(FluidRequirement requirement) {
        GenericStack template = GenericStack.fromFluidStack(requirement.stack(1));
        if (template == null) {
            throw new IllegalArgumentException("Fluid requirement " + requirement.fluidName() + " is not an AE key");
        }
        return new GenericStack(template.what(), boundedAmount(requirement.amount()));
    }

    private static long boundedAmount(long amount) {
        if (amount <= 0L || amount > PatternDetailsHelper.MAX_PROCESSING_PATTERN_AMOUNT) {
            throw new IllegalArgumentException("A single plan requirement of " + amount + " exceeds one AE2 pattern");
        }
        return amount;
    }
}
