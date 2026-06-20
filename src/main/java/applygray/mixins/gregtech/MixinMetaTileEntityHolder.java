package applygray.mixins.gregtech;

import applygray.api.IAEManagedMetaTileEntity;

import gregtech.api.cover.Cover;
import gregtech.api.cover.IAECover;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;

import net.minecraft.nbt.NBTTagCompound;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MetaTileEntityHolder.class, remap = false)
public abstract class MixinMetaTileEntityHolder implements IActionHost, IGridProxyable {

    @Shadow
    public abstract MetaTileEntity getMetaTileEntity();

    @Override
    public @Nullable IGridNode getGridNode(@NotNull AEPartLocation part) {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        if (metaTileEntity != null) {
            Cover cover = metaTileEntity.getCoverAtSide(part.getFacing());
            if (cover instanceof IAECover aeCover) {
                IGridNode node = aeCover.getGridNode(part);
                if (node != null) return node;
            }
        }
        if (getCableConnectionType(part) == AECableType.NONE) return null;
        AENetworkProxy proxy = getProxy();
        return proxy == null ? null : proxy.getNode();
    }

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull AEPartLocation part) {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        if (metaTileEntity != null) {
            Cover cover = metaTileEntity.getCoverAtSide(part.getFacing());
            if (cover instanceof IAECover aeCover) {
                AECableType type = aeCover.getCableConnectionType(part);
                if (type != AECableType.NONE) return type;
            }
            if (metaTileEntity instanceof IAEManagedMetaTileEntity managed) {
                return managed.getCableConnectionType(part);
            }
        }
        return AECableType.NONE;
    }

    @Override
    public void securityBreak() {}

    @Override
    public @Nullable IGridNode getActionableNode() {
        AENetworkProxy proxy = getProxy();
        return proxy == null ? null : proxy.getNode();
    }

    @Override
    public @Nullable AENetworkProxy getProxy() {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        return metaTileEntity instanceof IAEManagedMetaTileEntity managed ? managed.getProxy() : null;
    }

    @Override
    public @NotNull DimensionalCoord getLocation() {
        return new DimensionalCoord((MetaTileEntityHolder) (Object) this);
    }

    @Override
    public void gridChanged() {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        if (metaTileEntity instanceof IAEManagedMetaTileEntity managed) managed.gridChanged();
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void applygray$readProxy(NBTTagCompound data, CallbackInfo ci) {
        AENetworkProxy proxy = getProxy();
        if (proxy != null) proxy.readFromNBT(data);
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void applygray$writeProxy(NBTTagCompound data, CallbackInfoReturnable<NBTTagCompound> cir) {
        AENetworkProxy proxy = getProxy();
        if (proxy != null) proxy.writeToNBT(data);
    }

    @Inject(method = "onChunkUnload", at = @At("TAIL"))
    private void applygray$unloadProxy(CallbackInfo ci) {
        AENetworkProxy proxy = getProxy();
        if (proxy != null) proxy.onChunkUnload();
    }

    @Inject(method = "invalidate", at = @At("TAIL"))
    private void applygray$invalidateProxy(CallbackInfo ci) {
        AENetworkProxy proxy = getProxy();
        if (proxy != null) proxy.invalidate();
    }
}
