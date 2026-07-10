package applygray.mixins.ae2;

import applygray.integration.ae2.IRecipePatternRebuildable;

import net.minecraft.entity.player.EntityPlayer;

import appeng.core.sync.AppEngPacket;
import appeng.core.sync.network.INetworkInfo;
import appeng.core.sync.packets.PacketValueConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketValueConfig.class, remap = false)
public abstract class MixinPacketValueConfigLazyRecipeMap {

    @Shadow @Final private String Name;

    @Inject(method = "serverPacketData", at = @At("HEAD"), cancellable = true)
    private void applygray$rebuildLazyRecipePatterns(INetworkInfo network, AppEngPacket packet,
                                                     EntityPlayer player, CallbackInfo ci) {
        if (!"ApplyGray.RebuildRecipePatterns".equals(Name)) return;
        if (player.openContainer instanceof IRecipePatternRebuildable) {
            ((IRecipePatternRebuildable) player.openContainer).applygray$clearTargetPatternsAndRecalculate();
        }
        ci.cancel();
    }
}