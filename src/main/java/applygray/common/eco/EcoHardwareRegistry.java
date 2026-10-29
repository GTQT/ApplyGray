package applygray.common.eco;

import applygray.api.ApplyGrayAPI;
import gregtech.api.metatileentity.MetaTileEntity;
import java.util.ArrayList;
import java.util.List;
import static gregtech.common.metatileentities.MetaTileEntities.registerMetaTileEntity;

/** Stable, append-only MTE IDs, outside ApplyGray's pre-existing 0..104 range. */
public final class EcoHardwareRegistry {
    public static final List<MetaTileEntity> MACHINES = new ArrayList<>();
    private EcoHardwareRegistry() {}
    public static void init() {
        for (EcoController.Kind kind : EcoController.Kind.values()) {
            MACHINES.add(registerMetaTileEntity(200 + kind.ordinal(),
                    new EcoController(ApplyGrayAPI.id("eco." + kind.id), kind)));
            MACHINES.add(registerMetaTileEntity(203 + kind.ordinal(),
                    new EcoNetWork(ApplyGrayAPI.id("eco." + kind.id + ".interface"), kind)));
        }
        for (EcoPart.Role role : EcoPart.Role.values()) {
            int count = role == EcoPart.Role.PATTERN_BUS ? 1 : EcoTiers.COUNT;
            for (int tier = 1; tier <= count; tier++) {
                MACHINES.add(registerMetaTileEntity(210 + role.ordinal() * 5 + tier - 1,
                        new EcoPart(ApplyGrayAPI.id("eco." + role.name().toLowerCase(java.util.Locale.ROOT) + "." + tier),
                                role, tier)));
            }
        }
    }
}
