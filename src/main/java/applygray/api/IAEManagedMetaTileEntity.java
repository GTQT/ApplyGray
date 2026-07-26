package applygray.api;

import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.util.AECableType;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;

public interface IAEManagedMetaTileEntity {

    @NotNull
    IManagedGridNode getMainNode();

    @NotNull
    AECableType getCableConnectionType(@NotNull EnumFacing side);

    default void gridChanged() {}

    /**
     * Called after AE2 changes a node property that may affect whether the node is active.
     */
    default void onMainNodeStateChanged(IGridNodeListener.State state) {}

    /**
     * Managed nodes are single-use after destruction. Implementations that cache a node must clear that cache here.
     */
    default void destroyMainNode() {
        getMainNode().destroy();
    }
}
