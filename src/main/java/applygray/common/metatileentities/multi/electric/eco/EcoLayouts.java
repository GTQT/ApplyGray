package applygray.common.metatileentities.multi.electric.eco;

/**
 * ECO 多方块的结构行数据。
 *
 * <p>字符轴为 {@code BACK}：char 0 是控制器所在平面（正面），char 1 是控制器背后；
 * 行沿 UP（下→上），各 aisle 沿 RIGHT。</p>
 *
 * <p>Rack geometry reference: GTQT/PrismPlan (8dbed09, GPL-3.0); molecular assembler geometry
 * reference: NeoECOAEExtension (GPL-3.0).</p>
 */
final class EcoLayouts {

    // ## 货架类（CPU / 存储 / 电池）
    static final String[] RACK_CAP = { "XX", "XX", "XX" };
    static final String[] RACK_CONTROLLER = { "NX", "SX", "XX" };
    static final String[] COMPUTER_SECTION = { "TP", "UF", "TP" };
    static final String[] STORAGE_SECTION = { "TX", "TX", "TX" };
    static final String[] BATTERY_SECTION = { "JJ", "JX", "JJ" };
    static final String[] RACK_REAR = { "HH", "HH", "HH" };

    // ## 大分子装配室（照 NeoECO 的 crafting system：3 宽 × 3 高 × 2 深 + 可重复段）
    // 可重复段：正面中间是 Worker，背面中间是散热口，背面上下是样板总线。
    static final String[] WORKSHOP_SECTION = { "XB", "WH", "XB" };
    // 固定段：控制器一侧是外壳，另一侧背面是 ME 接口；工坊翼接在外壳一侧。
    static final String[] WORKSHOP_CASING = { "XX", "XX", "XX" };
    static final String[] WORKSHOP_CONTROLLER = { "XX", "SX", "XX" };
    static final String[] WORKSHOP_INTERFACE = { "XX", "XN", "XX" };

    private EcoLayouts() {}
}
