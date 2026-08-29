package applygray.mattermanipulator.building;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

/** Forge event bridges shared by all target-specific building adapters. */
public final class BuildingEventHooks {

    private BuildingEventHooks() {}

    @SuppressWarnings("deprecation")
    public static boolean isPlayerPlaceCanceled(BuildingContext context, BlockSnapshot snapshot) {
        return ForgeEventFactory.onPlayerBlockPlace(context.player(), snapshot, EnumFacing.UP, context.hand())
                .isCanceled();
    }
}
