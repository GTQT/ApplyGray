package applygray.mattermanipulator.uplink;

import java.util.UUID;

import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.entity.player.EntityPlayerMP;

/** Target-native AE2 crafting endpoint exposed by a formed Quantum Uplink. */
public interface UplinkCraftingEndpoint {

    /** Queues server-validated requirements for direct AE2 crafting through this uplink. */
    UplinkCraftingRequestResult requestCrafting(EntityPlayerMP requester, String requestName,
                                                ResourceRequirements requirements);

    /** Cancels every queued or submitted request owned by the given player. */
    int cancelCraftingRequests(UUID requesterId);
}
