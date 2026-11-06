package applygray.integration.jei;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import applygray.integration.ae2.encoding.Util;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renames repeated HEI/JEI ingredients so that each copy stays a distinct pattern input.
 */
public final class HeiPatternItemAutoNamer {

    public static final int MAX_CUSTOM_NAME_LENGTH = 32;

    private HeiPatternItemAutoNamer() {}

    public static boolean isEnabled(boolean toggleOn, @Nullable String recipeCategoryUid) {
        return toggleOn || Util.isAssemblyLineRecipe(recipeCategoryUid);
    }

    public static List<GenericStack> autoNameRepeatedItems(List<GenericStack> orderedInputs) {
        Map<Object, Integer> occurrences = new LinkedHashMap<>();
        for (GenericStack stack : orderedInputs) {
            Object group = groupOf(stack);
            if (group != null) {
                occurrences.merge(group, 1, Integer::sum);
            }
        }

        Map<Object, Integer> nextIndexByGroup = new HashMap<>();
        List<GenericStack> renamed = new ObjectArrayList<>(orderedInputs.size());
        for (GenericStack stack : orderedInputs) {
            Object group = groupOf(stack);
            Integer occurrencesInGroup = group == null ? null : occurrences.get(group);
            if (occurrencesInGroup == null || occurrencesInGroup < 2) {
                renamed.add(stack);
                continue;
            }

            int index = nextIndexByGroup.merge(group, 0, (previous, ignored) -> previous + 1);
            String name = buildName(index);
            AEKey replacement = ItemKeyRenameStrategy.INSTANCE.rename(stack.what(), name);
            renamed.add(replacement == null ? stack : new GenericStack(replacement, stack.amount()));
        }
        return renamed;
    }

    static String buildName(int index) {
        return "#" + index;
    }

    @Nullable
    static Object groupOf(@Nullable GenericStack stack) {
        if (stack == null || stack.what() == null) {
            return null;
        }
        return ItemKeyRenameStrategy.INSTANCE.groupKey(stack.what());
    }

    enum ItemKeyRenameStrategy {
        INSTANCE;

        @Nullable
        public Object groupKey(AEKey key) {
            ItemStack stack = stripCustomName(key);
            return stack == null ? null : AEItemKey.of(stack);
        }

        @Nullable
        public AEKey rename(AEKey key, String name) {
            ItemStack stack = toPlainStack(key);
            if (stack == null) {
                return null;
            }
            stack.setStackDisplayName(name);
            return AEItemKey.of(stack);
        }

        @Nullable
        private static ItemStack stripCustomName(AEKey key) {
            ItemStack stack = toPlainStack(key);
            if (stack != null) {
                stack.clearCustomName();
            }
            return stack;
        }

        @Nullable
        private static ItemStack toPlainStack(AEKey key) {
            if (!(key instanceof AEItemKey itemKey)) {
                return null;
            }
            ItemStack stack = itemKey.toStack(1);
            return stack.isEmpty() ? null : stack;
        }
    }
}
