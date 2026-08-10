package applygray.mattermanipulator.uplink;

import java.util.Objects;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.MaterialSource;

/** Exact item source backed by the active Quantum Uplink bound to one manipulator. */
public final class UplinkMaterialSource implements MaterialSource {

    private final long address;
    private UplinkStatus lastStatus = UplinkStatus.OFFLINE;

    public UplinkMaterialSource(long address) {
        if (address == 0L) throw new IllegalArgumentException("Uplink addresses must be non-zero");
        this.address = address;
    }

    @Override
    public String id() {
        return "uplink:" + Long.toUnsignedString(address, 16);
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) {
        UplinkEndpoint endpoint = endpoint();
        if (endpoint == null) return 0L;
        long extracted = endpoint.extract(specification, amount, simulate);
        lastStatus = Objects.requireNonNull(endpoint.status(), "Uplink endpoints must provide a status");
        return extracted;
    }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) {
        UplinkEndpoint endpoint = endpoint();
        if (endpoint == null) return 0L;
        long inserted = endpoint.insert(specification, amount, simulate);
        lastStatus = Objects.requireNonNull(endpoint.status(), "Uplink endpoints must provide a status");
        return inserted;
    }

    public UplinkStatus lastStatus() {
        return lastStatus;
    }

    private UplinkEndpoint endpoint() {
        UplinkEndpoint endpoint = MatterManipulatorUplinkRegistry.find(address);
        if (endpoint == null) {
            lastStatus = UplinkStatus.OFFLINE;
            return null;
        }
        lastStatus = Objects.requireNonNull(endpoint.status(), "Uplink endpoints must provide a status");
        return lastStatus == UplinkStatus.OK ? endpoint : null;
    }
}
