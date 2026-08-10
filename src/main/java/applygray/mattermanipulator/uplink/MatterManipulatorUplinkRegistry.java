package applygray.mattermanipulator.uplink;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Live server registry for formed Quantum Uplinks.
 *
 * <p>Addresses are deliberately process-local handles. A controller re-registers after its chunk loads and removes
 * itself as soon as it becomes invalid, so an unloaded multiblock is never treated as remotely usable.</p>
 */
public final class MatterManipulatorUplinkRegistry {

    private static final Map<Long, UplinkEndpoint> ENDPOINTS = new HashMap<>();

    private MatterManipulatorUplinkRegistry() {}

    public static synchronized long newAddress() {
        long address;
        do {
            address = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
        } while (ENDPOINTS.containsKey(address));
        return address;
    }

    /**
     * Registers one formed controller. A copied controller item retains its address, so the caller must regenerate
     * that address when this method returns false.
     */
    public static synchronized boolean register(UplinkEndpoint endpoint) {
        if (endpoint == null || endpoint.address() == 0L) return false;
        UplinkEndpoint existing = ENDPOINTS.putIfAbsent(endpoint.address(), endpoint);
        return existing == null || existing == endpoint;
    }

    public static synchronized void unregister(UplinkEndpoint endpoint) {
        if (endpoint != null) ENDPOINTS.remove(endpoint.address(), endpoint);
    }

    public static synchronized UplinkEndpoint find(long address) {
        return ENDPOINTS.get(address);
    }
}
