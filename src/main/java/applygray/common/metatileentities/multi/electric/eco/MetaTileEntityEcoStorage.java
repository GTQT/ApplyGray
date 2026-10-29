package applygray.common.metatileentities.multi.electric.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.pattern.element.StructureDefinition;

import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoDrivePart;

import ae2.api.storage.cells.StorageCell;

import net.minecraft.util.ResourceLocation;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/** 多方块存储器：驱动仓挂载 ECO 元件，由 ME 接口统一对外暴露。 */
public class MetaTileEntityEcoStorage extends MetaTileEntityEcoController {

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = EcoStructures.storage();

    public MetaTileEntityEcoStorage(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, EcoControllerType.STORAGE);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoStorage(metaTileEntityId);
    }

    @Override
    protected @NonNull StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.0"));
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.1", EcoTiers.MAX_SEGMENTS));
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.2"));
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.3"));
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.4"));
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.5"));
        tooltip.add(EcoTooltips.line("applygray.eco.storage.tooltip.6"));
    }

    @Override
    protected void onInvalidateStructure() {
        for (MetaTileEntityEcoDrivePart drive : parts(EcoPartRole.DRIVE, MetaTileEntityEcoDrivePart.class)) {
            drive.persistCell();
        }
    }

    public List<StorageCell> cells() {
        var result = new ArrayList<StorageCell>();
        for (MetaTileEntityEcoDrivePart drive : parts(EcoPartRole.DRIVE, MetaTileEntityEcoDrivePart.class)) {
            StorageCell cell = drive.cell();
            if (cell != null) result.add(cell);
        }
        return result;
    }
}
