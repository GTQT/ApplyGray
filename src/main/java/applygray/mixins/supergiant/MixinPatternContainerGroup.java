package applygray.mixins.supergiant;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockNotifiablePart;

import ae2.api.implementations.blockentities.PatternContainerGroup;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
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

        // A hatch inside a formed multiblock is named after its controller, so players do not have to rename every
        // hatch of the same machine by hand. Unformed hatches and other machines keep their own name.
        if (metaTileEntity instanceof MetaTileEntityMultiblockNotifiablePart part) {
            MultiblockControllerBase controller = part.getController();
            if (controller != null) {
                cir.setReturnValue(new PatternContainerGroup(group.icon(), applygray$displayName(controller),
                        group.tooltip()));
                return;
            }
        }

        cir.setReturnValue(new PatternContainerGroup(group.icon(), applygray$displayName(metaTileEntity),
                group.tooltip()));
    }

    private static ITextComponent applygray$displayName(MetaTileEntity metaTileEntity) {
        // A custom name is what the player sees in the machine GUI, so it wins over the default machine name.
        if (metaTileEntity.getHolder() instanceof MetaTileEntityHolder holder && holder.hasCustomName()) {
            return holder.getDisplayName();
        }
        return new TextComponentTranslation(metaTileEntity.getMetaFullName());
    }
}
