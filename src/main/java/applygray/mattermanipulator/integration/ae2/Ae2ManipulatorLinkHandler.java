package applygray.mattermanipulator.integration.ae2;

import java.util.Objects;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.state.ManipulatorCapability;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorState;

import ae2.api.features.GridLinkables;
import ae2.api.features.IGridLinkableHandler;
import ae2.api.implementations.blockentities.IWirelessAccessPoint;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Registers the target-native AE2 security-terminal linking flow for Matter Manipulators. */
public final class Ae2ManipulatorLinkHandler {

    private static final IGridLinkableHandler HANDLER = new Handler();

    private Ae2ManipulatorLinkHandler() {}

    public static void register(ItemMatterManipulator item) {
        GridLinkables.register(item, HANDLER);
    }

    private static final class Handler implements IGridLinkableHandler {

        @Override
        public boolean canLink(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() instanceof ItemMatterManipulator item &&
                    item.hasCapability(stack, ManipulatorCapability.AE_NETWORK);
        }

        @Override
        public void link(ItemStack stack, World world, BlockPos position) {
            Objects.requireNonNull(stack, "stack");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
            if (!(stack.getItem() instanceof ItemMatterManipulator item)) return;
            TileEntity tile = world.getTileEntity(position);
            if (!(tile instanceof IWirelessAccessPoint)) return;

            ManipulatorState state = item.state(stack);
            if (!item.hasCapability(stack, ManipulatorCapability.AE_NETWORK)) return;
            state.setAe2NetworkLocation(ManipulatorLocation.fromWorld(world, position));
            item.saveState(stack, state);
        }

        @Override
        public void unlink(ItemStack stack) {
            if (!(stack.getItem() instanceof ItemMatterManipulator item)) return;
            ManipulatorState state = item.state(stack);
            state.setAe2NetworkLocation(null);
            item.saveState(stack, state);
        }
    }
}
