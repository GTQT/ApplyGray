package applygray.mattermanipulator.item;

import applygray.api.ApplyGrayAPI;

import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Gives an armed material pick priority over the clicked block's own right-click behaviour.
 *
 * <p>1.12.2 runs {@code Block#onBlockActivated} before {@code Item#onItemUse}, and the blocks a player most wants to
 * sample are exactly the ones that consume the click: an AE2 cable bus opens the part's GUI and a GregTech machine
 * opens the machine's, so the manipulator's own {@code onItemUse} never runs. Denying the block use for the one click
 * that carries a pending pick keeps the sample working while leaving every other interaction untouched.</p>
 */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID)
public final class MatterManipulatorInteractionHooks {

    private MatterManipulatorInteractionHooks() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!ItemMatterManipulator.isPendingMaterialPick(stack)) return;
        // Only the block use is denied; the item use that follows is what resolves and stores the sampled material.
        event.setUseBlock(Event.Result.DENY);
    }
}
