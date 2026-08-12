package applygray.mattermanipulator.state;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Installed upgrades are stored as a compact target-only bit mask. */
public enum ManipulatorUpgrade {

    POWER_P2P(0, EnumSet.noneOf(ManipulatorCapability.class)),
    MINING(1, EnumSet.of(ManipulatorCapability.REMOVAL)),
    SPEED(2, EnumSet.noneOf(ManipulatorCapability.class)),
    POWER_EFFICIENCY(3, EnumSet.noneOf(ManipulatorCapability.class));

    private final int bit;
    private final Set<ManipulatorCapability> providedCapabilities;

    ManipulatorUpgrade(int bit, EnumSet<ManipulatorCapability> providedCapabilities) {
        this.bit = bit;
        this.providedCapabilities = Collections.unmodifiableSet(EnumSet.copyOf(providedCapabilities));
    }

    public int bit() {
        return bit;
    }

    public Set<ManipulatorCapability> providedCapabilities() {
        return providedCapabilities;
    }

    public String translationKey() {
        return "item.applygray.matter_manipulator_upgrade_" + name().toLowerCase() + ".name";
    }

    public static int toMask(Set<ManipulatorUpgrade> upgrades) {
        int mask = 0;
        for (ManipulatorUpgrade upgrade : upgrades) {
            mask |= 1 << upgrade.bit;
        }
        return mask;
    }

    public static EnumSet<ManipulatorUpgrade> fromMask(int mask) {
        EnumSet<ManipulatorUpgrade> upgrades = EnumSet.noneOf(ManipulatorUpgrade.class);
        for (ManipulatorUpgrade upgrade : values()) {
            if ((mask & (1 << upgrade.bit)) != 0) {
                upgrades.add(upgrade);
            }
        }
        return upgrades;
    }
}
