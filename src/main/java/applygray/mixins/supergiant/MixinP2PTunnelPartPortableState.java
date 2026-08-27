package applygray.mixins.supergiant;

import applygray.mattermanipulator.integration.ae2.PortableP2PStateAccess;

import ae2.me.service.P2PService;
import ae2.parts.p2p.P2PTunnelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Restores both halves of P2P identity; AE2's playerless Memory Card import always creates an output. */
@Mixin(value = P2PTunnelPart.class, remap = false)
public abstract class MixinP2PTunnelPartPortableState implements PortableP2PStateAccess {

    @Shadow
    public abstract short getFrequency();

    @Shadow
    public abstract boolean isOutput();

    @Shadow
    abstract void setOutput(boolean output);

    @Shadow
    public abstract void setFrequency(short frequency);

    @Shadow
    public abstract void onTunnelNetworkChange();

    @Shadow
    public abstract void onTunnelConfigChange();

    @Override
    public short applygray$getFrequency() {
        return getFrequency();
    }

    @Override
    public boolean applygray$isOutput() {
        return isOutput();
    }

    @Override
    public void applygray$setP2PState(short frequency, boolean output) {
        P2PTunnelPart<?> self = (P2PTunnelPart<?>) (Object) this;
        setOutput(output);
        if (self.getMainNode().getGrid() == null) {
            setFrequency(frequency);
        } else {
            P2PService.get(self.getMainNode().getGrid()).updateFreq(self, frequency);
        }
        onTunnelNetworkChange();
        onTunnelConfigChange();
    }
}
