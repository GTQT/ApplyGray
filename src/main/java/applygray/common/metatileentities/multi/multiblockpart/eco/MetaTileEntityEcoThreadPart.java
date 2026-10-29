package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multi.electric.eco.EcoTiers;

import net.minecraft.util.ResourceLocation;

/** 普通线程核心：给 CPU 增加普通线程。 */
public class MetaTileEntityEcoThreadPart extends MetaTileEntityEcoPart {

    public MetaTileEntityEcoThreadPart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.THREAD, tier);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoThreadPart(metaTileEntityId, hardwareTier);
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.HPCA_COMPUTATION_OVERLAY;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.thread.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.thread.tooltip.1"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.thread.tooltip.2"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.thread.tooltip.3", EcoTiers.threads(hardwareTier, false)));
    }
}
