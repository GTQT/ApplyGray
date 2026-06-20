package applygray.mixins.gregtech;

import applygray.api.IAEManagedMetaTileEntity;

import gregtech.api.block.machines.BlockMachine;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import appeng.me.helpers.AENetworkProxy;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockMachine.class, remap = false)
public abstract class MixinBlockMachine {

    @Inject(method = "onBlockPlacedBy", at = @At("TAIL"))
    private void applygray$setNetworkOwner(World world, @NotNull BlockPos pos, @NotNull IBlockState state,
                                           @NotNull EntityLivingBase placer, ItemStack stack, CallbackInfo ci) {
        if (!(placer instanceof EntityPlayer player)) return;
        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof MetaTileEntityHolder holder)) return;
        MetaTileEntity metaTileEntity = holder.getMetaTileEntity();
        if (metaTileEntity instanceof IAEManagedMetaTileEntity managed) {
            AENetworkProxy proxy = managed.getProxy();
            if (proxy != null) proxy.setOwner(player);
        }
    }
}
