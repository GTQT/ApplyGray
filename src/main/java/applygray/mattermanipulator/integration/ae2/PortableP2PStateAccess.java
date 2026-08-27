package applygray.mattermanipulator.integration.ae2;

/** Mixin bridge for restoring an AE2 P2P tunnel role without player-only Memory Card behavior. */
public interface PortableP2PStateAccess {

    short applygray$getFrequency();

    boolean applygray$isOutput();

    void applygray$setP2PState(short frequency, boolean output);
}
