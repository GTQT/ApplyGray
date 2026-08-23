package applygray.mattermanipulator.state;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import applygray.mattermanipulator.config.MatterManipulatorConfig;
/** Target-native tier data for the four Matter Manipulator tools. */
public enum ManipulatorTier {

    MK0(32, 16, 20, 3, 10_000_000L, EnumSet.of(ManipulatorCapability.GEOMETRY),
            EnumSet.of(ManipulatorUpgrade.MINING, ManipulatorUpgrade.SPEED, ManipulatorUpgrade.POWER_EFFICIENCY)),
    MK1(64, 32, 10, 5, 100_000_000L, EnumSet.of(
            ManipulatorCapability.AE_NETWORK,
            ManipulatorCapability.REMOVAL,
            ManipulatorCapability.GEOMETRY,
            ManipulatorCapability.CONFIGURATION,
            ManipulatorCapability.EXCHANGING,
            ManipulatorCapability.CABLES), EnumSet.of(ManipulatorUpgrade.SPEED, ManipulatorUpgrade.POWER_EFFICIENCY)),
    MK2(128, 64, 5, 6, 1_000_000_000L, EnumSet.of(
            ManipulatorCapability.AE_NETWORK,
            ManipulatorCapability.REMOVAL,
            ManipulatorCapability.GEOMETRY,
            ManipulatorCapability.CONFIGURATION,
            ManipulatorCapability.COPYING,
            ManipulatorCapability.EXCHANGING,
            ManipulatorCapability.MOVING,
            ManipulatorCapability.CABLES), EnumSet.of(ManipulatorUpgrade.SPEED, ManipulatorUpgrade.POWER_EFFICIENCY)),
    MK3(-1, 256, 5, 7, 10_000_000_000L, EnumSet.of(
            ManipulatorCapability.AE_NETWORK,
            ManipulatorCapability.UPLINK,
            ManipulatorCapability.REMOVAL,
            ManipulatorCapability.GEOMETRY,
            ManipulatorCapability.CONFIGURATION,
            ManipulatorCapability.COPYING,
            ManipulatorCapability.SMART_COPY,
            ManipulatorCapability.EXCHANGING,
            ManipulatorCapability.MOVING,
            ManipulatorCapability.CABLES),
            EnumSet.of(ManipulatorUpgrade.POWER_P2P, ManipulatorUpgrade.POWER_EFFICIENCY));

    private final int maximumRange;
    private final int blocksPerBatch;
    private final int batchIntervalTicks;
    private final int voltageTier;
    private final long maximumCharge;
    private final Set<ManipulatorCapability> capabilities;
    private final Set<ManipulatorUpgrade> allowedUpgrades;

    ManipulatorTier(int maximumRange, int blocksPerBatch, int batchIntervalTicks, int voltageTier,
                    long maximumCharge, EnumSet<ManipulatorCapability> capabilities,
                    EnumSet<ManipulatorUpgrade> allowedUpgrades) {
        this.maximumRange = maximumRange;
        this.blocksPerBatch = blocksPerBatch;
        this.batchIntervalTicks = batchIntervalTicks;
        this.voltageTier = voltageTier;
        this.maximumCharge = maximumCharge;
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        this.allowedUpgrades = Collections.unmodifiableSet(EnumSet.copyOf(allowedUpgrades));
    }

    public int maximumRange() {
        return maximumRange;
    }

    public int blocksPerBatch() {
        return this == MK3 ? MatterManipulatorConfig.mk3BlocksPerPlace : blocksPerBatch;
    }

    public int batchIntervalTicks() {
        return batchIntervalTicks;
    }

    public int voltageTier() {
        return voltageTier;
    }

    public long maximumCharge() {
        return maximumCharge;
    }

    public boolean hasCapability(ManipulatorCapability capability) {
        return capabilities.contains(capability);
    }

    public Set<ManipulatorCapability> capabilities() {
        return capabilities;
    }

    public boolean allowsUpgrade(ManipulatorUpgrade upgrade) {
        return allowedUpgrades.contains(upgrade);
    }

    public Set<ManipulatorUpgrade> allowedUpgrades() {
        return allowedUpgrades;
    }
}
