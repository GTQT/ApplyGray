package applygray.api;

import ae2.api.networking.IManagedGridNode;
import ae2.api.util.AECableType;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;

public interface IAEManagedMetaTileEntity {

    @NotNull
    IManagedGridNode getMainNode();

    @NotNull
    AECableType getCableConnectionType(@NotNull EnumFacing side);

    default void gridChanged() {}
}
