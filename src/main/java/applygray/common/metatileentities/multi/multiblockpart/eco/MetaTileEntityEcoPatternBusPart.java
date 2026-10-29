package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multi.electric.eco.EcoTiers;
import applygray.common.metatileentities.multi.electric.eco.logic.EcoAssemblerLane;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** 分子装配样板总线：只收工作台合成样板。 */
public class MetaTileEntityEcoPatternBusPart extends MetaTileEntityEcoPart {

    public MetaTileEntityEcoPatternBusPart(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, EcoPartRole.PATTERN_BUS, 1);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoPatternBusPart(metaTileEntityId);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return EcoAssemblerLane.isAssemblerPattern(stack, getWorld());
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.DATA_ACCESS_HATCH;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.pattern_bus.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.pattern_bus.tooltip.1"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.pattern_bus.tooltip.2"));
    }
}
