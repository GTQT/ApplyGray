package applygray.common.metatileentities.multiblock.eco.part;

import applygray.common.metatileentities.multiblock.eco.EcoTooltips;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multiblock.eco.EcoTiers;

import net.minecraft.util.ResourceLocation;

/** 超线程核心：额外线程按 +10% 内存计费。 */
public class MetaTileEntityEcoHyperThreadPart extends MetaTileEntityEcoPart {

    public MetaTileEntityEcoHyperThreadPart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.HYPER_THREAD, tier);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoHyperThreadPart(metaTileEntityId, hardwareTier);
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.HPCA_ADVANCED_COMPUTATION_OVERLAY;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(EcoTooltips.line(
                "applygray.eco.hyper_thread.tooltip.0"));
        tooltip.add(EcoTooltips.line(
                "applygray.eco.hyper_thread.tooltip.1"));
        tooltip.add(EcoTooltips.line(
                "applygray.eco.hyper_thread.tooltip.2"));
        tooltip.add(EcoTooltips.line(
                "applygray.eco.hyper_thread.tooltip.3", EcoTiers.threads(hardwareTier, true)));
    }
}
