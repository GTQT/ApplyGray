package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multi.electric.eco.EcoTiers;

import net.minecraft.util.ResourceLocation;

/** 并行核心：给同一控制器下的所有 CPU 增加合成加速器。 */
public class MetaTileEntityEcoParallelPart extends MetaTileEntityEcoPart {

    public MetaTileEntityEcoParallelPart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.PARALLEL, tier);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoParallelPart(metaTileEntityId, hardwareTier);
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.HPCA_BRIDGE_OVERLAY;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.parallel.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.parallel.tooltip.1"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.parallel.tooltip.2", EcoTiers.accelerators(hardwareTier)));
    }
}
