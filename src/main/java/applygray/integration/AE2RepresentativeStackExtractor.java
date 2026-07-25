package applygray.integration;

import gregtech.client.utils.ItemRenderCompat;

import net.minecraft.item.ItemStack;
import net.minecraft.client.Minecraft;

import ae2.crafting.pattern.EncodedPatternItem;
import org.jetbrains.annotations.NotNull;

public final class AE2RepresentativeStackExtractor implements ItemRenderCompat.RepresentativeStackExtractor {

    @Override
    public boolean canHandleStack(@NotNull ItemStack stack) {
        return stack.getItem() instanceof EncodedPatternItem;
    }

    @Override
    public @NotNull ItemStack getActualStack(@NotNull ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (!(stack.getItem() instanceof EncodedPatternItem<?> pattern)
                || Minecraft.getMinecraft().world == null) {
            return ItemStack.EMPTY;
        }
        return pattern.getOutput(stack, Minecraft.getMinecraft().world);
    }
}
