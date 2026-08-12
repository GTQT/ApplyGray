package applygray.mattermanipulator.item;

/** Stable target registry paths for the original Matter Manipulator component progression. */
public enum ManipulatorComponent {

    BLUEPRINT(0, "matter_manipulator_blueprint"),
    POWER_CORE_MK0(1, "matter_manipulator_power_core_mk0"),
    COMPUTER_CORE_MK0(2, "matter_manipulator_computer_core_mk0"),
    TELEPORTER_CORE_MK0(3, "matter_manipulator_teleporter_core_mk0"),
    FRAME_MK0(4, "matter_manipulator_frame_mk0"),
    LENS_ASSEMBLY_MK0(5, "matter_manipulator_lens_assembly_mk0"),
    POWER_CORE_MK1(6, "matter_manipulator_power_core_mk1"),
    COMPUTER_CORE_MK1(7, "matter_manipulator_computer_core_mk1"),
    TELEPORTER_CORE_MK1(8, "matter_manipulator_teleporter_core_mk1"),
    FRAME_MK1(9, "matter_manipulator_frame_mk1"),
    LENS_ASSEMBLY_MK1(10, "matter_manipulator_lens_assembly_mk1"),
    POWER_CORE_MK2(11, "matter_manipulator_power_core_mk2"),
    COMPUTER_CORE_MK2(12, "matter_manipulator_computer_core_mk2"),
    TELEPORTER_CORE_MK2(13, "matter_manipulator_teleporter_core_mk2"),
    FRAME_MK2(14, "matter_manipulator_frame_mk2"),
    LENS_ASSEMBLY_MK2(15, "matter_manipulator_lens_assembly_mk2"),
    POWER_CORE_MK3(16, "matter_manipulator_power_core_mk3"),
    COMPUTER_CORE_MK3(17, "matter_manipulator_computer_core_mk3"),
    TELEPORTER_CORE_MK3(18, "matter_manipulator_teleporter_core_mk3"),
    FRAME_MK3(19, "matter_manipulator_frame_mk3"),
    LENS_ASSEMBLY_MK3(20, "matter_manipulator_lens_assembly_mk3"),
    ME_DOWNLINK(21, "matter_manipulator_me_downlink"),
    QUANTUM_DOWNLINK(22, "matter_manipulator_quantum_downlink"),
    BLANK_UPGRADE(23, "matter_manipulator_upgrade_blank");

    private final int sourceTextureIndex;
    private final String registryPath;

    ManipulatorComponent(int sourceTextureIndex, String registryPath) {
        this.sourceTextureIndex = sourceTextureIndex;
        this.registryPath = registryPath;
    }

    public int sourceTextureIndex() {
        return sourceTextureIndex;
    }

    public String registryPath() {
        return registryPath;
    }
}
