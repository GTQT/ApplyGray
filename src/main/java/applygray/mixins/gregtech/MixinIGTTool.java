package applygray.mixins.gregtech;

import gregtech.api.items.toolitem.ToolClasses;
import gregtech.api.items.toolitem.ToolHelper;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import appeng.api.implementations.items.IAEWrench;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "gregtech.api.items.toolitem.IGTTool", remap = false)
public interface MixinIGTTool extends IAEWrench {

    @Override
    default boolean canWrench(ItemStack wrench, EntityPlayer player, BlockPos pos) {
        return ToolHelper.isTool(wrench, ToolClasses.WRENCH);
    }
}
