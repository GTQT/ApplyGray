package applygray.mattermanipulator.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

/** Shared client/server targeting for coordinates which follow the player's crosshair. */
public final class ManipulatorTargeting {

    private ManipulatorTargeting() {}

    public static BlockPos lookingAt(EntityPlayer player, float partialTicks) {
        double reach = player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue();
        RayTraceResult hit = player.rayTrace(reach, partialTicks);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos block = hit.getBlockPos();
            return player.isSneaking() ? block : block.offset(hit.sideHit);
        }

        Vec3d start = player.getPositionEyes(partialTicks);
        Vec3d end = start.add(player.getLook(partialTicks).scale(reach));
        return new BlockPos(MathHelper.floor(end.x), MathHelper.floor(end.y), MathHelper.floor(end.z));
    }
}
