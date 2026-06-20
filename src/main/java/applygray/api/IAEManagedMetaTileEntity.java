package applygray.api;

import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.me.helpers.AENetworkProxy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface IAEManagedMetaTileEntity {

    @NotNull
    AECableType getCableConnectionType(@NotNull AEPartLocation part);

    @Nullable
    AENetworkProxy getProxy();

    default void gridChanged() {}
}
