package applygray.mixins.gregtech;

import applygray.api.IAEManagedMetaTileEntity;
import applygray.integration.ae2.ApplyGrayGridNodeSupport;

import gregtech.common.metatileentities.electric.MetaTileEntityHull;

import ae2.api.networking.GridFlags;
import ae2.api.networking.IManagedGridNode;
import ae2.api.util.AECableType;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = MetaTileEntityHull.class, remap = false)
public abstract class MixinMetaTileEntityHull implements IAEManagedMetaTileEntity {

    @Unique
    private IManagedGridNode applygray$mainNode;

    @Override
    public @NotNull IManagedGridNode getMainNode() {
        if (applygray$mainNode == null) {
            MetaTileEntityHull hull = (MetaTileEntityHull) (Object) this;
            applygray$mainNode = ApplyGrayGridNodeSupport.createMainNode(hull)
                .setTagName("applygray_hull_node")
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setVisualRepresentation(hull.getStackForm());
        }
        return applygray$mainNode;
    }

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull EnumFacing side) {
        return AECableType.SMART;
    }
}
