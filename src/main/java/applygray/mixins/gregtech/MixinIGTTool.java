package applygray.mixins.gregtech;

import gregtech.api.items.toolitem.ToolClasses;
import gregtech.api.items.toolitem.ToolHelper;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import ae2.api.util.IAEWrench;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "gregtech.api.items.toolitem.IGTTool", remap = false)
public interface MixinIGTTool extends IAEWrench {

    @Override
    default boolean isUsable(ItemStack wrench, EntityLivingBase user, BlockPos pos) {
        return ToolHelper.isTool(wrench, ToolClasses.WRENCH);
    }
}
