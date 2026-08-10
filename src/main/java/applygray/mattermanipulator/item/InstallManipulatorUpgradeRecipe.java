package applygray.mattermanipulator.item;

import javax.annotation.Nullable;

import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.registries.IForgeRegistryEntry;

/** Dynamic two-item crafting recipe that installs one allowed upgrade into a manipulator's target-only NBT state. */
public final class InstallManipulatorUpgradeRecipe extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {

    public static final InstallManipulatorUpgradeRecipe INSTANCE = new InstallManipulatorUpgradeRecipe();

    private InstallManipulatorUpgradeRecipe() {
        setRegistryName(new ResourceLocation(ApplyGrayAPI.MODID, "install_matter_manipulator_upgrade"));
    }

    @Override
    public boolean matches(InventoryCrafting inventory, World world) {
        return findIngredients(inventory) != null;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inventory) {
        Ingredients ingredients = findIngredients(inventory);
        if (ingredients == null) return ItemStack.EMPTY;

        ItemStack result = ingredients.manipulatorStack.copy();
        ManipulatorState state = ingredients.manipulator.state(result);
        if (!state.installUpgrade(ingredients.upgrade)) return ItemStack.EMPTY;
        ingredients.manipulator.saveState(result, state);
        return result;
    }

    @Override
    public boolean canFit(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public ItemStack getRecipeOutput() {
        return ItemStack.EMPTY;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inventory) {
        return NonNullList.withSize(inventory.getSizeInventory(), ItemStack.EMPTY);
    }

    @Nullable
    private static Ingredients findIngredients(InventoryCrafting inventory) {
        ItemStack manipulatorStack = ItemStack.EMPTY;
        ItemMatterManipulator manipulator = null;
        ManipulatorUpgrade upgrade = null;
        int nonEmptyCount = 0;

        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            nonEmptyCount++;
            if (stack.getItem() instanceof ItemMatterManipulator candidate && manipulator == null) {
                manipulator = candidate;
                manipulatorStack = stack;
            } else if (stack.getItem() instanceof ItemManipulatorUpgrade candidate && upgrade == null) {
                upgrade = candidate.upgrade();
            } else {
                return null;
            }
        }
        if (nonEmptyCount != 2 || manipulator == null || upgrade == null) return null;

        ManipulatorState state = manipulator.state(manipulatorStack);
        if (!manipulator.tier().allowsUpgrade(upgrade) || state.hasUpgrade(upgrade)) return null;
        return new Ingredients(manipulatorStack, manipulator, upgrade);
    }

    private record Ingredients(ItemStack manipulatorStack, ItemMatterManipulator manipulator, ManipulatorUpgrade upgrade) {}
}
