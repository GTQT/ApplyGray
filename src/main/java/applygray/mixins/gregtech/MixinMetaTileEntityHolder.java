package applygray.mixins.gregtech;

import applygray.api.IAEManagedMetaTileEntity;

import gregtech.api.cover.Cover;
import gregtech.api.cover.IAECover;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import ae2.api.AECapabilities;
import ae2.api.networking.GridHelper;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IInWorldGridNodeHost;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.security.IActionHost;
import ae2.api.util.AECableType;
import ae2.block.IOwnerAwareTile;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MetaTileEntityHolder.class, remap = false)
public abstract class MixinMetaTileEntityHolder implements IActionHost, IInWorldGridNodeHost, IOwnerAwareTile {

    @Unique
    private boolean applygray$nodeCreationScheduled;

    @Shadow
    public abstract MetaTileEntity getMetaTileEntity();

    @Nullable
    @Unique
    private IAEManagedMetaTileEntity applygray$getManagedMetaTileEntity() {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        return metaTileEntity instanceof IAEManagedMetaTileEntity managed ? managed : null;
    }

    @Override
    public @Nullable IGridNode getGridNode(@NotNull EnumFacing side) {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        if (metaTileEntity != null) {
            Cover cover = metaTileEntity.getCoverAtSide(side);
            if (cover instanceof IAECover aeCover) {
                IGridNode node = aeCover.getGridNode(side);
                if (node != null) return node;
            }
        }
        if (getCableConnectionType(side) == AECableType.NONE) return null;

        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        return managed == null ? null : managed.getMainNode().getNode();
    }

    @Override
    public @NotNull AECableType getCableConnectionType(@NotNull EnumFacing side) {
        MetaTileEntity metaTileEntity = getMetaTileEntity();
        if (metaTileEntity != null) {
            Cover cover = metaTileEntity.getCoverAtSide(side);
            if (cover instanceof IAECover aeCover) {
                AECableType type = aeCover.getCableConnectionType(side);
                if (type != AECableType.NONE) return type;
            }
            if (metaTileEntity instanceof IAEManagedMetaTileEntity managed) {
                return managed.getCableConnectionType(side);
            }
        }
        return AECableType.NONE;
    }

    @Override
    public @Nullable IGridNode getActionableNode() {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        return managed == null ? null : managed.getMainNode().getNode();
    }

    @Override
    public void setOwner(EntityPlayer owner) {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        if (managed != null) {
            managed.getMainNode().setOwningPlayer(owner);
        }
    }

    @Unique
    private void applygray$scheduleNodeCreation() {
        MetaTileEntityHolder holder = (MetaTileEntityHolder) (Object) this;
        if (applygray$nodeCreationScheduled || holder.getWorld() == null || holder.getWorld().isRemote ||
            applygray$getManagedMetaTileEntity() == null) {
            return;
        }

        applygray$nodeCreationScheduled = true;
        GridHelper.onFirstTick(holder, ignored -> {
            IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
            if (managed == null) return;

            IManagedGridNode node = managed.getMainNode();
            if (!node.isReady()) {
                node.create(holder.getWorld(), holder.getPos());
            }
        });
    }

    @Inject(method = "setMetaTileEntity", at = @At("HEAD"))
    private void applygray$discardPreviousNode(MetaTileEntity sampleMetaTileEntity, NBTTagCompound tagCompound,
                                               NBTTagCompound itemStackData,
                                               CallbackInfoReturnable<MetaTileEntity> cir) {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        if (managed != null) {
            managed.destroyMainNode();
        }
        applygray$nodeCreationScheduled = false;
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void applygray$readNode(NBTTagCompound data, CallbackInfo ci) {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        if (managed != null) {
            managed.getMainNode().loadFromNBT(data);
        }
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void applygray$writeNode(NBTTagCompound data, CallbackInfoReturnable<NBTTagCompound> cir) {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        if (managed != null) {
            managed.getMainNode().saveToNBT(data);
        }
    }

    @Inject(method = "onLoad", at = @At("TAIL"))
    private void applygray$scheduleLoadedNode(CallbackInfo ci) {
        applygray$scheduleNodeCreation();
    }

    @Inject(method = "setMetaTileEntity", at = @At("TAIL"))
    private void applygray$schedulePlacedNode(MetaTileEntity sampleMetaTileEntity, NBTTagCompound tagCompound,
                                              NBTTagCompound itemStackData,
                                              CallbackInfoReturnable<MetaTileEntity> cir) {
        applygray$scheduleNodeCreation();
    }

    @Inject(method = "onChunkUnload", at = @At("TAIL"))
    private void applygray$unloadNode(CallbackInfo ci) {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        if (managed != null) {
            managed.destroyMainNode();
        }
        applygray$nodeCreationScheduled = false;
    }

    @Inject(method = "invalidate", at = @At("TAIL"))
    private void applygray$invalidateNode(CallbackInfo ci) {
        IAEManagedMetaTileEntity managed = applygray$getManagedMetaTileEntity();
        if (managed != null) {
            managed.destroyMainNode();
        }
        applygray$nodeCreationScheduled = false;
    }

    @Inject(method = "hasCapability", at = @At("HEAD"), cancellable = true)
    private void applygray$hasInWorldNodeCapability(Capability<?> capability, @Nullable EnumFacing facing,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (capability == AECapabilities.IN_WORLD_GRID_NODE_HOST && applygray$getManagedMetaTileEntity() != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true)
    private <T> void applygray$getInWorldNodeCapability(Capability<T> capability, @Nullable EnumFacing facing,
                                                         CallbackInfoReturnable<T> cir) {
        if (capability == AECapabilities.IN_WORLD_GRID_NODE_HOST && applygray$getManagedMetaTileEntity() != null) {
            cir.setReturnValue(AECapabilities.IN_WORLD_GRID_NODE_HOST.cast(this));
        }
    }
}
