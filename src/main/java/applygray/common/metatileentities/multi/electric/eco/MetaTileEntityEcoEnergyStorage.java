package applygray.common.metatileentities.multi.electric.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.pattern.element.StructureDefinition;

import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoEnergyPart;

import net.minecraft.util.ResourceLocation;

import org.jspecify.annotations.NonNull;

/** AE 多方块电池：只做网络能量存储，由蓄能单元决定容量。 */
public class MetaTileEntityEcoEnergyStorage extends MetaTileEntityEcoController {

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = EcoStructures.battery();

    public MetaTileEntityEcoEnergyStorage(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, EcoControllerType.BATTERY);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoEnergyStorage(metaTileEntityId);
    }

    @Override
    protected @NonNull StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(EcoTooltips.line("applygray.eco.battery.tooltip.0"));
        tooltip.add(EcoTooltips.line("applygray.eco.battery.tooltip.1"));
        tooltip.add(EcoTooltips.line("applygray.eco.battery.tooltip.2"));
        tooltip.add(EcoTooltips.line("applygray.eco.battery.tooltip.3"));
        tooltip.add(EcoTooltips.line("applygray.eco.battery.tooltip.4"));
    }

    public double storedEnergy() {
        double stored = 0;
        for (MetaTileEntityEcoEnergyPart part : parts(EcoPartRole.ENERGY, MetaTileEntityEcoEnergyPart.class)) {
            stored += part.storedEnergy();
        }
        return stored;
    }

    public double energyCapacity() {
        double capacity = 0;
        for (MetaTileEntityEcoEnergyPart part : parts(EcoPartRole.ENERGY, MetaTileEntityEcoEnergyPart.class)) {
            capacity += EcoTiers.energy(part.hardwareTier);
        }
        return capacity;
    }
}
