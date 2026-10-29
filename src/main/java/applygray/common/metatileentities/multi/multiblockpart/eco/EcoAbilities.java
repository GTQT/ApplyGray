package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.multiblock.MultiblockAbility;

import applygray.common.metatileentities.multi.electric.eco.EcoControllerType;

import java.util.Locale;

/**
 * ECO 的仓室/接口能力。GT 把原版能力集中在 {@code MultiblockAbility}，附加模组在这里登记自己的能力，
 * 控制器与仓室只引用这些常量，避免用字符串在运行期查表。
 */
public final class EcoAbilities {

    public static final MultiblockAbility<MetaTileEntityEcoPart> MEMORY =
            ability("eco_memory", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> THREAD =
            ability("eco_thread", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> HYPER_THREAD =
            ability("eco_hyper_thread", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> PARALLEL =
            ability("eco_parallel", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> DRIVE =
            ability("eco_drive", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> ENERGY =
            ability("eco_energy", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> WORKER =
            ability("eco_worker", MetaTileEntityEcoPart.class);
    public static final MultiblockAbility<MetaTileEntityEcoPart> PATTERN_BUS =
            ability("eco_pattern_bus", MetaTileEntityEcoPart.class);

    private EcoAbilities() {}

    public static MultiblockAbility<MetaTileEntityEcoPart> byRole(EcoPartRole role) {
        return switch (role) {
            case MEMORY -> MEMORY;
            case THREAD -> THREAD;
            case HYPER_THREAD -> HYPER_THREAD;
            case PARALLEL -> PARALLEL;
            case DRIVE -> DRIVE;
            case ENERGY -> ENERGY;
            case WORKER -> WORKER;
            case PATTERN_BUS -> PATTERN_BUS;
        };
    }

    public static MultiblockAbility<MetaTileEntityEcoNetworkPart> network(EcoControllerType type) {
        return MetaTileEntityEcoNetworkPart.ability(type);
    }

    private static <T> MultiblockAbility<T> ability(String name, Class<T> type) {
        return new MultiblockAbility<>(name.toLowerCase(Locale.ROOT), type);
    }
}
