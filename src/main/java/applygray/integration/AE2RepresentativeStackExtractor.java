package applygray.integration;

import gregtech.client.utils.ItemRenderCompat;

import net.minecraft.item.ItemStack;

import appeng.items.misc.ItemEncodedPattern;
import org.jetbrains.annotations.NotNull;

public final class AE2RepresentativeStackExtractor implements ItemRenderCompat.RepresentativeStackExtractor {

    @Override
    public boolean canHandleStack(@NotNull ItemStack stack) {
        return stack.getItem() instanceof ItemEncodedPattern;
    }

    @Override
    public @NotNull ItemStack getActualStack(@NotNull ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        return ((ItemEncodedPattern) stack.getItem()).getOutput(stack);
    }
}
