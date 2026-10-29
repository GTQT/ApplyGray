package applygray.common.metatileentities.multi.multiblockpart.eco;

import applygray.common.metatileentities.multi.electric.eco.EcoTiers;

import java.util.Locale;

/** ECO 仓室类型；槽位数量与注册名都由这里决定。 */
public enum EcoPartRole {

    MEMORY(1),
    THREAD(0),
    HYPER_THREAD(0),
    PARALLEL(0),
    DRIVE(1),
    ENERGY(0),
    WORKER(0),
    PATTERN_BUS(EcoTiers.PATTERN_SLOTS);

    public final int slots;

    EcoPartRole(int slots) {
        this.slots = slots;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
