package applygray.mixins.ae2;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockPart;

import ae2.api.implementations.blockentities.PatternContainerGroup;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies the controller name for GregTech machines in Supergiant's pattern-terminal groups. */
@Mixin(value = PatternContainerGroup.class, remap = false)
public abstract class MixinPatternContainerGroup {

    @Inject(method = "fromMachine", at = @At("RETURN"), cancellable = true)
    private static void applygray$useGregTechMachineName(World level, BlockPos pos, EnumFacing side,
                                                         CallbackInfoReturnable<PatternContainerGroup> cir) {
        PatternContainerGroup group = cir.getReturnValue();
        TileEntity tileEntity = level.getTileEntity(pos);
        if (group == null || !(tileEntity instanceof MetaTileEntityHolder holder)) return;

        MetaTileEntity metaTileEntity = holder.getMetaTileEntity();
        if (metaTileEntity == null) return;
        String name = metaTileEntity.getMetaFullName();
        if (metaTileEntity instanceof MetaTileEntityMultiblockPart part && part.getController() != null) {
            name = part.getController().getMetaFullName();
        }
        cir.setReturnValue(new PatternContainerGroup(group.icon(), new TextComponentString(name), group.tooltip()));
    }
}
