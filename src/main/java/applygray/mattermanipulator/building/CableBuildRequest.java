package applygray.mattermanipulator.building;

import java.util.List;
import java.util.Objects;

import applygray.mattermanipulator.inventory.MaterialSource;
import applygray.mattermanipulator.inventory.PowerSource;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorTier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/** Server-authoritative input for a cable-line operation. */
public record CableBuildRequest(EntityPlayer player, ItemStack manipulatorStack, EnumHand hand, ManipulatorTier tier,
                                ManipulatorState state, List<? extends MaterialSource> materialSources,
                                PowerSource powerSource) {

    public CableBuildRequest {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(manipulatorStack, "manipulatorStack");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(state, "state");
        materialSources = List.copyOf(materialSources);
        Objects.requireNonNull(powerSource, "powerSource");
    }
}
