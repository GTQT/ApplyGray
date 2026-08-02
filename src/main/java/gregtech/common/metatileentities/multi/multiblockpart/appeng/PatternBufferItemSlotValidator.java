package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import net.minecraft.item.ItemStack;

/** Validates the material type assigned to an isolated pattern-buffer slot. */
final class PatternBufferItemSlotValidator {

    private PatternBufferItemSlotValidator() {
    }

    static boolean accepts(ItemStack expected, ItemStack candidate) {
        return expected != null && !expected.isEmpty() && candidate != null && !candidate.isEmpty() &&
                ItemStack.areItemsEqual(expected, candidate) &&
                ItemStack.areItemStackTagsEqual(expected, candidate);
    }
}
