package applygray.common.metatileentities.multi.electric.eco;

/** ECO 多方块的四个组分；同时作为 ME 接口能力的键。 */
public enum EcoControllerType {

    COMPUTER("computer"),
    STORAGE("storage"),
    ASSEMBLER("molecular_assembler"),
    BATTERY("battery");

    public final String id;

    EcoControllerType(String id) {
        this.id = id;
    }
}
