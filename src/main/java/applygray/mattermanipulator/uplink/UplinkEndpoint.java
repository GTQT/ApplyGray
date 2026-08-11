package applygray.mattermanipulator.uplink;

import applygray.mattermanipulator.building.BlockSpec;

/**
 * Server-side endpoint for a formed Quantum Uplink.
 *
 * <p>The registry exposes this small contract instead of a GregTech tile class so material transactions can be
 * tested without a loaded world and cannot retain an obsolete MTE API.</p>
 */
public interface UplinkEndpoint {

    long address();

    UplinkStatus status();

    long extract(BlockSpec specification, long amount, boolean simulate);

    long insert(BlockSpec specification, long amount, boolean simulate);

    /** Returns whether this controller is formed and drawing its normal running power. */
    default boolean isActive() {
        return false;
    }

    /** Draws EU from the controller's attached energy inputs for a Power P2P upgrade. */
    default long drainPower(long amount, boolean simulate) {
        return 0L;
    }

    /** Returns previously drawn P2P energy when charging the tool could not consume it. */
    default long restorePower(long amount) {
        return 0L;
    }
}
