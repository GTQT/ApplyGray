package gregtech.api.cover;

import ae2.api.networking.IGridNode;
import ae2.api.util.AECableType;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.Nullable;

public interface IAECover extends Cover {

    @Nullable
    default IGridNode getGridNode(EnumFacing side) {
        return null;
    }

    default AECableType getCableConnectionType(EnumFacing side) {
        return AECableType.NONE;
    }
}
