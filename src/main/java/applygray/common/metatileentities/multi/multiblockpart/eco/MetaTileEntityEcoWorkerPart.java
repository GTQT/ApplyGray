package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multi.electric.eco.EcoConfig;
import applygray.common.metatileentities.multi.electric.eco.logic.EcoAssemblerLane;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/** 分子装配工作核心：每个仓室一条独立批次队列。 */
public class MetaTileEntityEcoWorkerPart extends MetaTileEntityEcoPart {

    private final EcoAssemblerLane lane;

    public MetaTileEntityEcoWorkerPart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.WORKER, tier);
        this.lane = new EcoAssemblerLane(this);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoWorkerPart(metaTileEntityId, hardwareTier);
    }

    public EcoAssemblerLane lane() {
        return lane;
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.HPCA_COMPUTATION_OVERLAY;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.worker.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.worker.tooltip.1"));
    }

    @Override
    protected void writeExtra(NBTTagCompound tag) {
        tag.setTag("EcoLane", lane.save());
    }

    @Override
    protected void readExtra(NBTTagCompound tag) {
        lane.load(tag.getCompoundTag("EcoLane"));
    }
}
