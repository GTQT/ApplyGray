package applygray.mixins.ae2;

import gregtech.api.metatileentity.MetaTileEntity;

import ae2.crafting.execution.CraftingSupplierLocator;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets Supergiant's crafting-supplier tracker locate managed GregTech pattern providers. */
@Mixin(value = CraftingSupplierLocator.class, remap = false)
public abstract class MixinAETrack {

    @Inject(method = "resolveBlockPos", at = @At("HEAD"), cancellable = true)
    private static void applygray$resolveMetaTileEntity(Object owner, CallbackInfoReturnable<BlockPos> cir) {
        if (owner instanceof MetaTileEntity metaTileEntity) {
            cir.setReturnValue(metaTileEntity.getPos());
        }
    }
}
