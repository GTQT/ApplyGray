package applygray.mattermanipulator.item;

import java.util.EnumSet;
import java.util.Set;

import javax.annotation.Nullable;

import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.state.ManipulatorTier;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;

/** Exact six-item tier upgrade which carries the target-only state and charge into the next tool. */
public final class UpgradeManipulatorTierRecipe extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {

    public static final UpgradeManipulatorTierRecipe MK1 = new UpgradeManipulatorTierRecipe(
            MatterManipulatorItems.MK0, MatterManipulatorItems.MK1, ManipulatorTier.MK1);
    public static final UpgradeManipulatorTierRecipe MK2 = new UpgradeManipulatorTierRecipe(
            MatterManipulatorItems.MK1, MatterManipulatorItems.MK2, ManipulatorTier.MK2);
    public static final UpgradeManipulatorTierRecipe MK3 = new UpgradeManipulatorTierRecipe(
            MatterManipulatorItems.MK2, MatterManipulatorItems.MK3, ManipulatorTier.MK3);

    private final ItemMatterManipulator source;
    private final ItemMatterManipulator target;
    private final Set<Item> components;

    private UpgradeManipulatorTierRecipe(ItemMatterManipulator source, ItemMatterManipulator target,
                                         ManipulatorTier targetTier) {
        this.source = source;
        this.target = target;
        this.components = componentsFor(targetTier);
        setRegistryName(new ResourceLocation(ApplyGrayAPI.MODID,
                "upgrade_matter_manipulator_" + targetTier.name().toLowerCase()));
    }

    @Override
    public boolean matches(InventoryCrafting inventory, World world) {
        return findSource(inventory) != null;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inventory) {
        ItemStack sourceStack = findSource(inventory);
        if (sourceStack == null) return ItemStack.EMPTY;

        ItemStack result = new ItemStack(target);
        if (sourceStack.hasTagCompound()) result.setTagCompound(sourceStack.getTagCompound().copy());
        return result;
    }

    @Override
    public boolean canFit(int width, int height) {
        return width * height >= 6;
    }

    @Override
    public ItemStack getRecipeOutput() {
        return new ItemStack(target);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inventory) {
        return NonNullList.withSize(inventory.getSizeInventory(), ItemStack.EMPTY);
    }

    @Nullable
    private ItemStack findSource(InventoryCrafting inventory) {
        ItemStack sourceStack = ItemStack.EMPTY;
        Set<Item> missingComponents = new java.util.HashSet<>(components);
        int nonEmptyCount = 0;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            nonEmptyCount++;
            if (stack.getItem() == source && sourceStack.isEmpty()) {
                sourceStack = stack;
            } else if (!missingComponents.remove(stack.getItem())) {
                return null;
            }
        }
        return nonEmptyCount == 6 && !sourceStack.isEmpty() && missingComponents.isEmpty() ? sourceStack : null;
    }

    private static Set<Item> componentsFor(ManipulatorTier tier) {
        int offset = 1 + tier.ordinal() * 5;
        ManipulatorComponent[] values = ManipulatorComponent.values();
        EnumSet<ManipulatorComponent> selected = EnumSet.noneOf(ManipulatorComponent.class);
        for (int index = 0; index < 5; index++) selected.add(values[offset + index]);

        Set<Item> result = new java.util.HashSet<>();
        for (ManipulatorComponent component : selected) result.add(MatterManipulatorItems.component(component));
        return Set.copyOf(result);
    }
}
