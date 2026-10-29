package applygray.common.metatileentities.multi.electric.eco;

import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

/** ECO 机器/仓室的 tooltip 统一格式：复制自 NeoECO / PrismPlan 的灰色斜体说明。 */
public final class EcoTooltips {

    private EcoTooltips() {}

    public static String line(String key, Object... args) {
        return TextFormatting.GRAY.toString() + TextFormatting.ITALIC + I18n.format(key, args);
    }
}
