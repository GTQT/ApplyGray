package applygray.mixins.gregtech;

import applygray.api.IAEManagedMetaTileEntity;

import gregtech.common.metatileentities.electric.MetaTileEntityHull;

import appeng.api.networking.GridFlags;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MetaTileEntityHull.class, remap = false)
public abstract class MixinMetaTileEntityHull implements IAEManagedMetaTileEntity {

    @Unique
    private AENetworkProxy applygray$gridProxy;

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull AEPartLocation part) {
        return AECableType.SMART;
    }

    @Override
    public @Nullable AENetworkProxy getProxy() {
        MetaTileEntityHull hull = (MetaTileEntityHull) (Object) this;
        if (applygray$gridProxy == null && hull.getHolder() instanceof IGridProxyable holder) {
            applygray$gridProxy = new AENetworkProxy(holder, "hull_proxy", hull.getStackForm(), true);
            applygray$gridProxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        }
        return applygray$gridProxy;
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void applygray$readyProxy(CallbackInfo ci) {
        MetaTileEntityHull hull = (MetaTileEntityHull) (Object) this;
        AENetworkProxy proxy = getProxy();
        if (hull.isFirstTick() && proxy != null) proxy.onReady();
    }
}
