package applygray.integration;

import ae2.api.stacks.GenericStack;
import ae2.core.definitions.AEItems;
import ae2.crafting.pattern.AEProcessingPattern;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.pattern.StructureEncodeHandlers;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the build dialog's material list into an AE processing pattern: required blocks as inputs, the controller as
 * the single output. Falls back to dropping the pattern when the inventory is full.
 */
public final class AEStructureEncodeHandler implements StructureEncodeHandlers.Handler {

    @Override
    public boolean encode(@NotNull EntityPlayer player, @NotNull MultiblockControllerBase controller,
                          @NotNull List<ItemStack> requiredItems) {
        if (requiredItems.size() > AEProcessingPattern.MAX_INPUT_SLOTS) {
            return false;
        }
        List<GenericStack> inputs = new ArrayList<>(requiredItems.size());
        for (ItemStack stack : requiredItems) {
            GenericStack input = GenericStack.fromItemStack(stack);
            if (input == null) {
                return false;
            }
            inputs.add(input);
        }
        GenericStack output = GenericStack.fromItemStack(controller.getStackForm());
        if (output == null) {
            return false;
        }
        ItemStack pattern = AEItems.PROCESSING_PATTERN.stack();
        AEProcessingPattern.encode(pattern, inputs, Collections.singletonList(output));
        if (!player.inventory.addItemStackToInventory(pattern)) {
            player.dropItem(pattern, false);
        }
        return true;
    }
}
