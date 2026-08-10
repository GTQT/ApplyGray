package applygray.mattermanipulator.building;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorRemovalMode;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

/** Immutable server-side context shared by target building adapters. */
public record BuildingContext(World world, EntityPlayer player, ItemStack manipulatorStack, EnumHand hand,
                              ManipulatorRemovalMode removalMode, boolean powerEfficiency, boolean removalAllowed,
                              boolean smartCopyEnabled) {

    public BuildingContext(World world, EntityPlayer player, ItemStack manipulatorStack, EnumHand hand,
                           ManipulatorRemovalMode removalMode, boolean powerEfficiency) {
        this(world, player, manipulatorStack, hand, removalMode, powerEfficiency, true, false);
    }

    public BuildingContext(World world, EntityPlayer player, ItemStack manipulatorStack, EnumHand hand,
                           ManipulatorRemovalMode removalMode, boolean powerEfficiency, boolean removalAllowed) {
        this(world, player, manipulatorStack, hand, removalMode, powerEfficiency, removalAllowed, false);
    }

    public BuildingContext {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(manipulatorStack, "manipulatorStack");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(removalMode, "removalMode");
    }
}
