package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multi.electric.eco.EcoTiers;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/** 高密度蓄能单元：AE 电池多方块的容量来源。 */
public class MetaTileEntityEcoEnergyPart extends MetaTileEntityEcoPart {

    private double energy;

    public MetaTileEntityEcoEnergyPart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.ENERGY, tier);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoEnergyPart(metaTileEntityId, hardwareTier);
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.ENERGY_IN;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.energy.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.energy.tooltip.1", EcoTiers.energy(hardwareTier)));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.energy.tooltip.2"));
    }

    public double storedEnergy() {
        return energy;
    }

    public double transferEnergy(double amount, boolean inject, boolean simulate) {
        if (!Double.isFinite(amount) || amount <= 0) return 0;
        double moved = Math.min(amount, inject ? EcoTiers.energy(hardwareTier) - energy : energy);
        if (!simulate && moved > 0) {
            energy += inject ? moved : -moved;
            markDirty();
        }
        return moved;
    }

    @Override
    protected void writeExtra(NBTTagCompound tag) {
        tag.setDouble("EcoEnergy", energy);
    }

    @Override
    protected void readExtra(NBTTagCompound tag) {
        energy = Math.clamp(tag.getDouble("EcoEnergy"), 0, EcoTiers.energy(hardwareTier));
        if (!Double.isFinite(energy)) energy = 0;
    }
}
